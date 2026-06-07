package com.exogroup.qnag.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.exogroup.qnag.MainActivity
import com.exogroup.qnag.data.AckSuppressCache
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.sound.AlertSoundController
import com.exogroup.qnag.sound.AlertSoundPlayer
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NotificationMode
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.instanceFingerprintPrefix
import com.exogroup.qnag.data.problemFingerprint
import com.exogroup.qnag.data.shouldNotify
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.notifications.deriveVisualStateFromProblems
import com.exogroup.qnag.notifications.visualStateColor
import com.exogroup.qnag.notifications.ProblemToNotify
import com.exogroup.qnag.reliability.ExactAlarmWatchdogScheduler
import com.exogroup.qnag.worker.BackgroundPollingScheduler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import android.annotation.SuppressLint

/**
 * Optional foreground service for users who want more reliable background monitoring.
 *
 * This service is started ONLY when the user explicitly enables "Keep monitoring active"
 * in the app's Settings screen. It is NOT auto-started on boot or after being killed.
 *
 * When active:
 *  - The background WorkManager polling is canceled to avoid double notifications.
 *  - A persistent notification "qNag monitoring active" is shown.
 *  - All enabled+notificationsEnabled instances are polled on the configured interval.
 *
 * When the setting is disabled or the app is uninstalled:
 *  - The service is stopped and WorkManager is rescheduled.
 *
 * Android may still kill this service under extreme memory pressure.
 * START_NOT_STICKY means it won't auto-restart (honoring "do not auto-start from background").
 * onDestroy() reschedules WorkManager so background polling resumes after any stop.
 */
class NagiosMonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before startForeground() is called.
        NotificationHelper.createChannels(this)
        startForeground(NotificationHelper.MONITORING_SERVICE_NOTIF_ID, buildPersistentNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SecureInstanceStore(applicationContext)
        val settings = store.getAppSettings()
        val instances = store.getInstances()
        val cmdSettings = settings.commandSettings
        val notifEnabled = settings.notificationSettings.notificationsEnabled
        val hasTargets = instances.any { it.enabled && it.notificationsEnabled }
        val debug = cmdSettings.debugCommandSubmission

        if (debug) android.util.Log.d("qNag",
            "[service] onStartCommand: keepMonitoringActive=${cmdSettings.keepMonitoringActive} " +
            "notifEnabled=$notifEnabled hasTargets=$hasTargets " +
            "interval=${cmdSettings.foregroundPollingIntervalSeconds.coerceAtLeast(30)}s")

        if (!cmdSettings.keepMonitoringActive) {
            if (debug) android.util.Log.d("qNag", "[service] stopping: keepMonitoringActive=false")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!notifEnabled || !hasTargets) {
            if (debug) android.util.Log.d("qNag", "[service] stopping: notifEnabled=$notifEnabled hasTargets=$hasTargets")
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground service is active — cancel WorkManager to avoid double notifications
        BackgroundPollingScheduler.cancel(applicationContext)
        MonitoringHealth.recordServiceStart(applicationContext)
        // Cancel any lingering background alert-summary notification — in foreground mode
        // the ONE foreground notification carries both monitoring status and alert summary.
        NotificationHelper.cancelAlertSummary(applicationContext)

        // Schedule (or refresh) the Exact Alarm Watchdog so it monitors this service.
        ExactAlarmWatchdogScheduler.schedule(applicationContext, settings)

        // Always cancel + restart the polling job.  This acts as a reload: interval changes,
        // instance list changes, and repeated start() calls all take effect immediately.
        pollingJob?.cancel()
        pollingJob = serviceScope.launch { pollingLoop() }

        // START_STICKY: ask Android to restart the service if killed (best-effort).
        // onDestroy schedules WorkManager fallback so monitoring survives even if restart fails.
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val store = SecureInstanceStore(applicationContext)
        val settings = store.getAppSettings()
        val instances = store.getInstances()
        val debug = settings.commandSettings.debugCommandSubmission

        if (!settings.commandSettings.keepMonitoringActive) {
            // User disabled reliability mode → cancel watchdog, use normal WorkManager scheduling
            MonitoringHealth.recordServiceStop(applicationContext, "user_disabled")
            if (debug) android.util.Log.d("qNag", "[service] destroyed: scheduling WorkManager (reliability mode off)")
            ExactAlarmWatchdogScheduler.cancel(applicationContext)
            BackgroundPollingScheduler.scheduleOrCancel(applicationContext, settings, instances)
        } else {
            // Reliability mode still ON but service was killed (OS pressure, timeout, etc.).
            // Keep the watchdog active so it can attempt recovery, and schedule WorkManager
            // as additional fallback while START_STICKY restart is pending.
            MonitoringHealth.recordServiceStop(applicationContext, "killed_by_os")
            if (debug) android.util.Log.d("qNag", "[service] destroyed: scheduling WorkManager fallback (reliability mode still on)")
            BackgroundPollingScheduler.scheduleFallback(applicationContext, settings, instances)
            ExactAlarmWatchdogScheduler.schedule(applicationContext, settings)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 (API 35) calls onTimeout when the dataSync foreground service exceeds the
    // system-defined background run limit.  Log safely, schedule fallback, and stop cleanly.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        android.util.Log.w("qNag", "[service] onTimeout (Android 15 dataSync limit) — scheduling fallbacks")
        MonitoringHealth.recordServiceStop(applicationContext, "android_15_datasync_timeout")
        val store = SecureInstanceStore(applicationContext)
        val settings = store.getAppSettings()
        BackgroundPollingScheduler.scheduleFallback(applicationContext, settings, store.getInstances())
        // Keep watchdog active so it can restart the service later
        ExactAlarmWatchdogScheduler.schedule(applicationContext, settings)
        stopSelf()
    }

    // ── Polling loop ──────────────────────────────────────────────────────────

    private suspend fun pollingLoop() {
        val api = NagiosApi()

        while (currentCoroutineContext().isActive) {
            val store = SecureInstanceStore(applicationContext)
            val instances = store.getInstances()
            val settings = store.getAppSettings()
            val notifSettings = settings.notificationSettings
            val cmdSettings = settings.commandSettings

            // Foreground service uses its own interval (seconds), independent of WorkManager's
            // 15-minute minimum.  Minimum enforced at 30 seconds to avoid hammering the server.
            val intervalMs = cmdSettings.foregroundPollingIntervalSeconds.coerceAtLeast(30) * 1000L

            if (!notifSettings.notificationsEnabled) {
                stopSelf()
                return
            }

            val targets = instances.filter { it.enabled && it.notificationsEnabled }
            if (targets.isEmpty()) {
                if (cmdSettings.debugCommandSubmission)
                    android.util.Log.d("qNag", "[service] no eligible targets, stopping")
                stopSelf()
                return
            }

            val effectiveIntervalS = cmdSettings.foregroundPollingIntervalSeconds.coerceAtLeast(30)
            // Update notification with poll-in-progress text (full summary posted at end of poll)
            updateForegroundNotification(
                contentText = "${targets.size} instance${if (targets.size != 1) "s" else ""} · checking every ${effectiveIntervalS}s"
            )

            if (cmdSettings.debugCommandSubmission)
                android.util.Log.d("qNag", "[service] poll starting: targets=${targets.size} interval=${effectiveIntervalS}s")

            MonitoringHealth.recordPollStart(applicationContext)

            // Evict expired ACK-suppress entries before the poll cycle
            AckSuppressCache.evictExpired(applicationContext)

            var fingerprints = loadFingerprints()
            var failedIds = loadFailedInstances()
            val toNotify = mutableListOf<ProblemToNotify>()
            val allCurrentProblems = mutableListOf<ProblemToNotify>()
            val failedInstanceNames = mutableListOf<String>()

            for (instance in targets) {
                try {
                    val problems = api.fetchProblems(instance)
                    val filtered = applyFilters(problems, settings.filterSettings)

                    val currentFps = filtered.map { problemFingerprint(instance.id, it) }.toSet()
                    val prefix = instanceFingerprintPrefix(instance.id)
                    val knownForInstance = fingerprints.filter { it.startsWith(prefix) }.toSet()

                    fingerprints = (fingerprints - knownForInstance) + currentFps

                    for (problem in filtered) {
                        if (!shouldNotify(problem, notifSettings)) continue
                        if (notifSettings.notifyOnlyUnacknowledged &&
                            AckSuppressCache.isSuppressed(applicationContext, instance.id, problem)) continue
                        allCurrentProblems += ProblemToNotify(instance.id, instance.name, problem)
                        val fp = problemFingerprint(instance.id, problem)
                        val isNew = fp !in knownForInstance
                        if (!cmdSettings.notifyOnlyNewProblems || isNew) {
                            toNotify += ProblemToNotify(instance.id, instance.name, problem)
                        }
                    }

                    MonitoringHealth.recordPollSuccess(applicationContext)

                    if (instance.id in failedIds) {
                        failedIds = failedIds - instance.id
                        if (notifSettings.notificationMode == NotificationMode.PER_PROBLEM) {
                            NotificationHelper.cancelFetchFailure(applicationContext, instance.id)
                        }
                    }

                } catch (e: Exception) {
                    val safeError = sanitizeError(e.message)
                    when (notifSettings.notificationMode) {
                        NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS ->
                            failedInstanceNames += instance.name
                        NotificationMode.PER_PROBLEM ->
                            if (cmdSettings.notifyOnFetchFailure && instance.id !in failedIds) {
                                NotificationHelper.notifyFetchFailure(applicationContext, instance.id, instance.name, safeError)
                                failedIds = failedIds + instance.id
                            }
                    }
                }
            }

            // ── One-notification dispatch ─────────────────────────────────────────
            // In foreground/reliability mode there is EXACTLY ONE qNag notification:
            // the foreground notification itself, updated in-place with the alert summary.
            // Sound is produced by AlertSoundController (in-app, independent of channel settings).
            when (notifSettings.notificationMode) {
                NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS -> {
                    val (summaryTitle, bodyLines) = NotificationHelper.buildSummaryContent(
                        allCurrentProblems, failedInstanceNames
                    )
                    val subtitle = "checking every ${effectiveIntervalS}s"
                    val contentText = "${targets.size} instance${if (targets.size != 1) "s" else ""} · $subtitle"
                    val bigText = if (bodyLines.isNotEmpty())
                        bodyLines.joinToString("\n") + "\n$subtitle"
                    else subtitle
                    // Derive accent color from the current worst alert state
                    val notifColor = visualStateColor(
                        deriveVisualStateFromProblems(allCurrentProblems, failedInstanceNames.size)
                    )
                    updateForegroundNotification(summaryTitle, contentText, bigText, notifColor)

                    // Unified in-app sound decision — same logic used by foreground service and worker
                    AlertSoundController.evaluateAndPlay(
                        context              = applicationContext,
                        allCurrentProblems   = allCurrentProblems,
                        newProblems          = toNotify,
                        failedInstanceNames  = failedInstanceNames,
                        settings             = notifSettings,
                        debug                = cmdSettings.debugCommandSubmission,
                    )
                }
                NotificationMode.PER_PROBLEM ->
                    NotificationHelper.notifyBatch(applicationContext, toNotify, notifSettings)
            }

            MonitoringHealth.recordPollFinished(applicationContext)

            // ── Stale monitoring self-alert ───────────────────────────────────────
            if (cmdSettings.staleMonitoringAlertEnabled) {
                val lastSuccess = MonitoringHealth.getSnapshot(applicationContext).lastSuccessfulPollAt
                if (lastSuccess != null) {
                    val staleMs = cmdSettings.monitoringStaleThresholdMinutes * 60_000L
                    val ageMs = System.currentTimeMillis() - lastSuccess
                    if (ageMs > staleMs) {
                        val minAgo = ageMs / 60_000L
                        NotificationHelper.notifyStale(applicationContext, "No successful poll in ${minAgo}m")
                    } else {
                        NotificationHelper.cancelStale(applicationContext)
                    }
                }
            }

            saveFingerprints(fingerprints)
            saveFailedInstances(failedIds)

            if (cmdSettings.debugCommandSubmission)
                android.util.Log.d("qNag", "[service] poll finished, next delay ${effectiveIntervalS}s")

            delay(intervalMs.milliseconds)
        }
    }

    // ── State persistence (shared with NagiosPollingWorker) ───────────────────

    private fun loadFingerprints(): Set<String> {
        val json = prefs().getString(KEY_FINGERPRINTS, null) ?: return emptySet()
        return try {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type) ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    private fun saveFingerprints(fps: Set<String>) =
        prefs().edit { putString(KEY_FINGERPRINTS, gson.toJson(fps)) }

    private fun loadFailedInstances(): Set<String> {
        val json = prefs().getString(KEY_FAILED, null) ?: return emptySet()
        return try {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type) ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    private fun saveFailedInstances(failed: Set<String>) =
        prefs().edit { putString(KEY_FAILED, gson.toJson(failed)) }

    private fun prefs() =
        applicationContext.getSharedPreferences("qnag_polling_state", Context.MODE_PRIVATE)

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun buildPersistentNotification(
        title: String = "qNag monitoring active",
        contentText: String = "Starting…",
        bigText: String? = null,
        color: Int = 0,
    ): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(contentText)
            .apply {
                if (bigText != null) setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                // color == 0 means "use system default" (e.g. before first poll completes)
                if (color != 0) setColor(color)
            }
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Update the persistent foreground notification with an optional accent color.
     *
     * In SUMMARY_ONLY mode the title is the alert-summary title ("qNag: 3 critical, …"),
     * contentText shows instance count + interval, bigText shows per-instance lines, and
     * color reflects the current worst alert state (green/amber/red/purple/orange).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun updateForegroundNotification(
        title: String = "qNag monitoring active",
        contentText: String,
        bigText: String? = null,
        color: Int = 0,
    ) {
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(
                    NotificationHelper.MONITORING_SERVICE_NOTIF_ID,
                    buildPersistentNotification(title, contentText, bigText, color)
                )
        } catch (_: Exception) {
        }
    }

    private fun sanitizeError(msg: String?): String =
        (msg ?: "Unknown error")
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .take(200)

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val KEY_FINGERPRINTS = "fingerprints"
        private const val KEY_FAILED = "failed_instances"

        fun start(context: Context) {
            val intent = Intent(context, NagiosMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NagiosMonitoringService::class.java))
        }
    }
}
