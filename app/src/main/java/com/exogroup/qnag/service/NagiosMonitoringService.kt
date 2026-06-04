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
import androidx.core.content.edit
import com.exogroup.qnag.MainActivity
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.instanceFingerprintPrefix
import com.exogroup.qnag.data.problemFingerprint
import com.exogroup.qnag.data.shouldNotify
import com.exogroup.qnag.notifications.NotificationHelper
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
        val notifEnabled = settings.notificationSettings.notificationsEnabled
        val hasTargets = instances.any { it.enabled && it.notificationsEnabled }

        // Stop immediately if there is nothing to monitor — avoids an empty foreground service.
        if (!notifEnabled || !hasTargets) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (pollingJob?.isActive != true) {
            pollingJob = serviceScope.launch { pollingLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Reschedule WorkManager so background polling resumes whenever this service stops,
        // whether stopped by the user, the OS, or onTimeout.
        val store = SecureInstanceStore(applicationContext)
        BackgroundPollingScheduler.scheduleOrCancel(applicationContext, store.getAppSettings(), store.getInstances())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15+ may call onTimeout for dataSync foreground services that run too long.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
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

            val intervalMs = notifSettings.refreshIntervalMinutes
                .coerceAtLeast(15) * 60_000L

            // If notifications were disabled while we were running, stop the service.
            if (!notifSettings.notificationsEnabled) {
                stopSelf()
                return
            }

            val targets = instances.filter { it.enabled && it.notificationsEnabled }
            if (targets.isEmpty()) {
                stopSelf()
                return
            }

            var fingerprints = loadFingerprints()
            var failedIds = loadFailedInstances()

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
                        val fp = problemFingerprint(instance.id, problem)
                        val isNew = fp !in knownForInstance
                        if (!cmdSettings.notifyOnlyNewProblems || isNew) {
                            NotificationHelper.notifyProblem(applicationContext, instance.id, instance.name, problem)
                        }
                    }

                    if (instance.id in failedIds) {
                        failedIds = failedIds - instance.id
                        NotificationHelper.cancelFetchFailure(applicationContext, instance.id)
                    }

                } catch (e: Exception) {
                    // Never log the exception verbatim — it may contain the Nagios URL which could include credentials
                    val safeError = sanitizeError(e.message)
                    if (cmdSettings.notifyOnFetchFailure && instance.id !in failedIds) {
                        NotificationHelper.notifyFetchFailure(applicationContext, instance.id, instance.name, safeError)
                        failedIds = failedIds + instance.id
                    }
                }
            }

            saveFingerprints(fingerprints)
            saveFailedInstances(failedIds)

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

    private fun buildPersistentNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("qNag monitoring active")
            .setContentText("Polling Nagios instances in the foreground")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
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
