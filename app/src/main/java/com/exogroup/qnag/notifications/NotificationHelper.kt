package com.exogroup.qnag.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exogroup.qnag.MainActivity
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.notificationId
import com.exogroup.qnag.data.notificationStatusLabel
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {

    // ── Channel IDs ───────────────────────────────────────────────────────────

    const val CHANNEL_MONITORING = "qnag_monitoring"         // foreground service persistent notif
    const val CHANNEL_FETCH_FAIL = "qnag_fetch_fail"         // instance connection failures
    const val CHANNEL_HOST_DOWN = "qnag_host_down"
    const val CHANNEL_HOST_UNREACHABLE = "qnag_host_unreachable"
    const val CHANNEL_SERVICE_CRITICAL = "qnag_service_critical"
    const val CHANNEL_SERVICE_WARNING = "qnag_service_warning"
    const val CHANNEL_SERVICE_UNKNOWN = "qnag_service_unknown"
    const val CHANNEL_ALERT_SUMMARY = "qnag_alert_summary"   // compact single-summary mode
    const val CHANNEL_STALE = "qnag_stale"                   // stale-monitoring self-alert
    const val CHANNEL_ALERTS = "qnag_alerts"                 // legacy fallback

    const val MONITORING_SERVICE_NOTIF_ID = 9001
    const val ALERT_SUMMARY_NOTIF_ID = 9002
    const val STALE_NOTIF_ID = 9003

    // When ≥ this many new problems of the same state arrive in one poll cycle,
    // collapse them into a single summary notification to prevent a sound storm.
    private const val SUMMARY_THRESHOLD = 3

    // In-memory per-channel last-sound timestamps — used by notifyBatch() for per-problem mode.
    // Resets on process restart; that's acceptable for the noisy per-problem mode.
    private val lastSoundTimestampMs = ConcurrentHashMap<String, Long>()

    // Summary-mode sound state is persisted in SharedPreferences (Goal 3) so it survives
    // process restarts.  Key: worst severity seen last poll + timestamp of last sound.
    private const val ALERT_SOUND_PREFS = "qnag_alert_sound_state"

    // ── Channel creation ──────────────────────────────────────────────────────

    /** Create all notification channels — safe to call multiple times. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)

        fun channel(id: String, name: String, importance: Int, desc: String) =
            NotificationChannel(id, name, importance).apply { description = desc }

        mgr.createNotificationChannel(
            channel(CHANNEL_MONITORING, "qNag Monitoring Service", NotificationManager.IMPORTANCE_LOW,
                "Persistent notification while foreground monitoring is active").apply { setShowBadge(false) }
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_FETCH_FAIL, "qNag: Unable to connect", NotificationManager.IMPORTANCE_DEFAULT,
                "Shown when polling fails to reach a Nagios instance")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_HOST_DOWN, "qNag: Host DOWN", NotificationManager.IMPORTANCE_HIGH,
                "Host is DOWN")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_HOST_UNREACHABLE, "qNag: Host UNREACHABLE", NotificationManager.IMPORTANCE_DEFAULT,
                "Host is UNREACHABLE")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_SERVICE_CRITICAL, "qNag: Service CRITICAL", NotificationManager.IMPORTANCE_HIGH,
                "Service is in CRITICAL state")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_SERVICE_WARNING, "qNag: Service WARNING", NotificationManager.IMPORTANCE_DEFAULT,
                "Service is in WARNING state")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_SERVICE_UNKNOWN, "qNag: Service UNKNOWN", NotificationManager.IMPORTANCE_LOW,
                "Service is in UNKNOWN state")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_ALERT_SUMMARY, "qNag Alert Summary", NotificationManager.IMPORTANCE_DEFAULT,
                "Compact audible summary of current Nagios problems — sounds on new/worse state")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_STALE, "qNag: Monitoring Stale", NotificationManager.IMPORTANCE_DEFAULT,
                "Shown when qNag has not successfully polled within the stale threshold")
        )
        mgr.createNotificationChannel(
            channel(CHANNEL_ALERTS, "qNag Alerts (legacy)", NotificationManager.IMPORTANCE_DEFAULT,
                "Legacy fallback channel")
        )
    }

    /** Returns true if POST_NOTIFICATIONS is granted (or Android < 13). */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** Returns the per-state channel ID for a problem. */
    fun channelForProblem(problem: NagiosProblem): String = when {
        problem is NagiosProblem.HostProblem && problem.status == NagiosStatus.HOST_DOWN -> CHANNEL_HOST_DOWN
        problem is NagiosProblem.HostProblem -> CHANNEL_HOST_UNREACHABLE
        problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_CRITICAL -> CHANNEL_SERVICE_CRITICAL
        problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_WARNING -> CHANNEL_SERVICE_WARNING
        else -> CHANNEL_SERVICE_UNKNOWN
    }

    // ── Summary content helpers (shared between foreground service and worker) ─

    /**
     * Build summary title and per-instance body lines without posting a notification.
     * Used by the foreground service to embed the summary into its own notification,
     * avoiding a duplicate alert-summary notification in the notification shade.
     *
     * @return Pair of (title, bodyLines) where bodyLines is one line per instance.
     */
    fun buildSummaryContent(
        allProblems: List<ProblemToNotify>,
        failedInstances: List<String>,
    ): Pair<String, List<String>> {
        val hostDown = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
        val hostUnr  = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
        val svcCrit  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
        val svcWarn  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
        val svcUnk   = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_UNKNOWN }
        val title = buildSummaryTitle(hostDown, hostUnr, svcCrit, svcWarn, svcUnk, failedInstances.size)
        val instanceGroups = allProblems.groupBy { it.instanceName.ifEmpty { it.instanceId } }
        val bodyLines = buildList {
            instanceGroups.forEach { (name, probs) ->
                val d = probs.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
                val u = probs.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
                val c = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
                val w = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
                val n = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_UNKNOWN }
                val counts = listOfNotNull(
                    if (d > 0) "D$d" else null, if (u > 0) "U$u" else null,
                    if (c > 0) "C$c" else null, if (w > 0) "W$w" else null, if (n > 0) "N$n" else null,
                )
                add("$name: ${if (counts.isEmpty()) "OK" else counts.joinToString(" · ")}")
            }
            failedInstances.forEach { add("$it: FAILED") }
        }
        return title to bodyLines
    }

    /**
     * Cancel the alert-summary notification and reset persistent sound state.
     * After a cancel, the next alert will sound again even if severity hasn't increased.
     */
    /**
     * Cancel the ALERT_SUMMARY_NOTIF_ID notification only.
     *
     * Does NOT reset sound state — calling this on foreground-service startup/reload must
     * not cause existing unchanged alerts to sound again.  Sound state is reset separately
     * in [notifySummary] when alerts genuinely clear (all-green).
     */
    fun cancelAlertSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(ALERT_SUMMARY_NOTIF_ID)
    }

    // ── Summary notification (SUMMARY_ONLY / GROUPED_DETAILS modes) ──────────

    /**
     * Posts or updates the single compact alert-summary notification (ALERT_SUMMARY_NOTIF_ID).
     *
     * This is SEPARATE from the quiet foreground service notification (MONITORING_SERVICE_NOTIF_ID).
     * In reliability mode the notification shade shows both:
     *   • "qNag monitoring active – Checking 2 instances every 30s"  (quiet, ongoing)
     *   • "qNag: 3 critical, 7 warning – EvoExads: C3·W6; Exo: W1"  (audible on new/worse state)
     *
     * @param newProblems   Problems that are new this poll cycle — drive sound decisions.
     * @param allProblems   All current problems passing notification filters — drive title/body.
     * @param failedInstances Instance names whose fetch failed this cycle.
     */
    @SuppressLint("MissingPermission")
    fun notifySummary(
        context: Context,
        newProblems: List<ProblemToNotify>,
        allProblems: List<ProblemToNotify>,
        failedInstances: List<String>,
        settings: NotificationSettings,
    ) {
        if (!hasPermission(context)) return

        // All-clear: cancel the summary notification AND reset sound state so the next
        // new alert sounds immediately regardless of any remaining cooldown.
        if (allProblems.isEmpty() && failedInstances.isEmpty()) {
            cancelAlertSummary(context)
            saveAlertSoundState(context, 0, false)
            com.exogroup.qnag.sound.AlertSoundController.resetState(context)
            return
        }

        val hostDown = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
        val hostUnr  = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
        val svcCrit  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
        val svcWarn  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
        val svcUnk   = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_UNKNOWN }
        val currentWorst = worstSeverity(hostDown, hostUnr, svcCrit, svcWarn, svcUnk)

        val title = buildSummaryTitle(hostDown, hostUnr, svcCrit, svcWarn, svcUnk, failedInstances.size)

        // Per-instance body lines: "Prod: D1 · C3 · W8"
        val instanceGroups = allProblems.groupBy { it.instanceName.ifEmpty { it.instanceId } }
        val bodyLines = buildList {
            instanceGroups.forEach { (name, probs) ->
                val d = probs.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
                val u = probs.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
                val c = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
                val w = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
                val n = probs.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_UNKNOWN }
                val counts = listOfNotNull(
                    if (d > 0) "D$d" else null, if (u > 0) "U$u" else null,
                    if (c > 0) "C$c" else null, if (w > 0) "W$w" else null, if (n > 0) "N$n" else null,
                )
                add("$name: ${counts.joinToString(" · ")}")
            }
            failedInstances.forEach { add("$it: FAILED") }
        }
        val body = bodyLines.joinToString("\n")

        val priority = if (hostDown > 0 || svcCrit > 0) NotificationCompat.PRIORITY_HIGH
                       else NotificationCompat.PRIORITY_DEFAULT

        // In in-app sound modes the notification is always silent — AlertSoundController
        // handles the actual sound, independent of channel importance settings.
        // In NOTIFICATION_CHANNEL_ONLY mode the channel sound decision is preserved.
        val isInAppMode = settings.alertSoundMode != AlertSoundMode.NOTIFICATION_CHANNEL_ONLY
        val channelSoundAllowed = if (isInAppMode) false else run {
            val (prevSeverity, lastSoundMs) = loadAlertSoundState(context)
            val allowed = shouldPlaySummarySound(
                hasNewProblems = newProblems.isNotEmpty(),
                currentSeverity = currentWorst,
                settings = settings,
                prevSeverity = prevSeverity,
                lastSoundMs = lastSoundMs,
            )
            if (allowed) saveAlertSoundState(context, currentWorst, true)
            allowed
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ALERT_SUMMARY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(bodyLines.firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(mainActivityIntent(context))
            .setOnlyAlertOnce(!channelSoundAllowed)   // true (silent) in in-app mode
            .setPriority(priority)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(context).notify(ALERT_SUMMARY_NOTIF_ID, notif)

        // For in-app mode: delegate sound to AlertSoundController so WorkManager and the
        // foreground service use the same logic (fingerprint tracking, cooldown, etc.)
        if (isInAppMode) {
            com.exogroup.qnag.sound.AlertSoundController.evaluateAndPlay(
                context              = context,
                allCurrentProblems   = allProblems,
                newProblems          = newProblems,
                failedInstanceNames  = failedInstances,
                settings             = settings,
            )
        }
    }

    // ── Stale monitoring alert ─────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun notifyStale(context: Context, message: String) {
        if (!hasPermission(context)) return
        val notif = NotificationCompat.Builder(context, CHANNEL_STALE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("qNag: monitoring stale")
            .setContentText(message)
            .setContentIntent(mainActivityIntent(context))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(STALE_NOTIF_ID, notif)
    }

    fun cancelStale(context: Context) {
        NotificationManagerCompat.from(context).cancel(STALE_NOTIF_ID)
    }

    private fun buildSummaryTitle(
        hostDown: Int, hostUnr: Int, svcCrit: Int, svcWarn: Int, svcUnk: Int, failedCount: Int,
    ): String {
        val parts = listOfNotNull(
            if (hostDown > 0) "$hostDown host${if (hostDown > 1) "s" else ""} down" else null,
            if (hostUnr  > 0) "$hostUnr unreachable" else null,
            if (svcCrit  > 0) "$svcCrit critical" else null,
            if (svcWarn  > 0) "$svcWarn warning" else null,
            if (svcUnk   > 0) "$svcUnk unknown" else null,
            if (failedCount > 0) "$failedCount instance${if (failedCount > 1) "s" else ""} failed" else null,
        )
        return if (parts.isEmpty()) "qNag: all green" else "qNag: ${parts.joinToString(", ")}"
    }

    private fun worstSeverity(hostDown: Int, hostUnr: Int, svcCrit: Int, svcWarn: Int, svcUnk: Int): Int = when {
        hostDown > 0 -> 5
        svcCrit  > 0 -> 4
        hostUnr  > 0 -> 3
        svcWarn  > 0 -> 2
        svcUnk   > 0 -> 1
        else         -> 0
    }

    // ── Persistent alert sound state (Goal 3) ────────────────────────────────
    // Stored in plain SharedPreferences — no secrets, just timestamps and integers.

    fun loadAlertSoundState(context: Context): Pair<Int, Long> {
        val p = context.getSharedPreferences(ALERT_SOUND_PREFS, Context.MODE_PRIVATE)
        return p.getInt("worst_severity", 0) to p.getLong("last_sound_ms", 0L)
    }

    fun saveAlertSoundState(context: Context, worstSeverity: Int, soundPlayed: Boolean) {
        context.getSharedPreferences(ALERT_SOUND_PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("worst_severity", worstSeverity)
            if (soundPlayed) putLong("last_sound_ms", System.currentTimeMillis())
            apply()
        }
    }

    private fun shouldPlaySummarySound(
        hasNewProblems: Boolean,
        currentSeverity: Int,
        settings: NotificationSettings,
        prevSeverity: Int,
        lastSoundMs: Long,
    ): Boolean {
        if (currentSeverity == 0) return false
        val now = System.currentTimeMillis()
        val cooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
        if (settings.globalSoundCooldownSeconds > 0 && (now - lastSoundMs) < cooldownMs) return false
        // Sound when: severity increased, transition from OK, or repeat setting allows
        return currentSeverity > prevSeverity ||
               (hasNewProblems && prevSeverity == 0) ||
               (hasNewProblems && settings.repeatSameProblemSound)
    }

    // ── Batch notification (anti-flood) ───────────────────────────────────────

    /**
     * Post notifications for a batch of new problems from a single poll cycle.
     *
     * Anti-flood behaviour:
     *  - Problems are grouped by state/channel.
     *  - If a state group has ≥ SUMMARY_THRESHOLD items, one summary notification replaces all
     *    individual ones: "12 CRITICAL service problems across 2 instances".
     *  - Sound plays at most once per state per [NotificationSettings.perStateSoundCooldownSeconds],
     *    and at most once globally per [NotificationSettings.globalSoundCooldownSeconds].
     *  - Within a group, only the first notification gets sound; the rest are silent.
     */
    @SuppressLint("MissingPermission")
    fun notifyBatch(
        context: Context,
        problems: List<ProblemToNotify>,
        settings: NotificationSettings,
    ) {
        if (problems.isEmpty() || !hasPermission(context)) return

        val now = System.currentTimeMillis()
        val globalCooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
        val perStateCooldownMs = settings.perStateSoundCooldownSeconds.toLong() * 1000L

        val globalLastSound = lastSoundTimestampMs["__global__"] ?: 0L
        var globalSoundBudget = settings.globalSoundCooldownSeconds <= 0 ||
                (now - globalLastSound) >= globalCooldownMs

        val grouped = problems.groupBy { channelForProblem(it.problem) }
        val mgr = NotificationManagerCompat.from(context)

        for ((channel, group) in grouped) {
            val lastSoundForChannel = lastSoundTimestampMs[channel] ?: 0L
            val channelSoundOk = settings.perStateSoundCooldownSeconds <= 0 ||
                    (now - lastSoundForChannel) >= perStateCooldownMs

            val soundAllowed = globalSoundBudget && channelSoundOk

            if (group.size >= SUMMARY_THRESHOLD) {
                val instances = group.map { it.instanceName }.filter { it.isNotEmpty() }.distinct()
                val where = when {
                    instances.size == 1 -> " in ${instances[0]}"
                    instances.size > 1 -> " across ${instances.size} instances"
                    else -> ""
                }
                val summaryId = ((channel.hashCode().toLong() and 0xFFFFFFFFL) xor 0x80000000L).toInt()
                val builder = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("${group.size} ${channelLabel(channel)} problems$where")
                    .setContentText("Tap to open qNag")
                    .setContentIntent(mainActivityIntent(context))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                if (!soundAllowed) builder.setSilent(true)
                mgr.notify(summaryId, builder.build())
            } else {
                group.forEachIndexed { i, ptn ->
                    val builder = buildProblemNotif(context, channel, ptn.instanceName, ptn.problem)
                    if (!soundAllowed || i > 0) builder.setSilent(true)
                    mgr.notify(notificationId(ptn.instanceId, ptn.problem), builder.build())
                }
            }

            if (soundAllowed) {
                lastSoundTimestampMs[channel] = now
                if (globalSoundBudget) {
                    lastSoundTimestampMs["__global__"] = now
                    globalSoundBudget = false // only first channel consumes global budget per cycle
                }
            }
        }
    }

    /** Single-problem notification (legacy / immediate post from ViewModel refresh). */
    @SuppressLint("MissingPermission")
    fun notifyProblem(context: Context, instanceId: String, instanceName: String, problem: NagiosProblem) {
        if (!hasPermission(context)) return
        val channel = channelForProblem(problem)
        NotificationManagerCompat.from(context)
            .notify(notificationId(instanceId, problem), buildProblemNotif(context, channel, instanceName, problem).build())
    }

    @SuppressLint("MissingPermission")
    fun notifyFetchFailure(context: Context, instanceId: String, instanceName: String, safeError: String) {
        if (!hasPermission(context)) return
        val notif = NotificationCompat.Builder(context, CHANNEL_FETCH_FAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("qNag: failed to reach $instanceName")
            .setContentText(safeError.take(256))
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(fetchFailureNotificationId(instanceId), notif)
    }

    fun cancelFetchFailure(context: Context, instanceId: String) {
        NotificationManagerCompat.from(context).cancel(fetchFailureNotificationId(instanceId))
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun buildProblemNotif(
        context: Context,
        channel: String,
        instanceName: String,
        problem: NagiosProblem,
    ): NotificationCompat.Builder {
        val statusLabel = notificationStatusLabel(problem)
        val title = when (problem) {
            is NagiosProblem.ServiceProblem -> "$statusLabel: ${problem.hostName} / ${problem.serviceName}"
            is NagiosProblem.HostProblem -> "$statusLabel: ${problem.hostName}"
        }
        val text = buildString {
            if (instanceName.isNotEmpty()) append("[$instanceName] ")
            append(problem.pluginOutput.take(240))
        }
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(mainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun channelLabel(channel: String): String = when (channel) {
        CHANNEL_HOST_DOWN -> "host DOWN"
        CHANNEL_HOST_UNREACHABLE -> "host UNREACHABLE"
        CHANNEL_SERVICE_CRITICAL -> "CRITICAL service"
        CHANNEL_SERVICE_WARNING -> "WARNING service"
        CHANNEL_SERVICE_UNKNOWN -> "UNKNOWN service"
        else -> "alert"
    }

    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** A problem queued for notification during a batch poll cycle. */
data class ProblemToNotify(
    val instanceId: String,
    val instanceName: String,
    val problem: NagiosProblem,
)
