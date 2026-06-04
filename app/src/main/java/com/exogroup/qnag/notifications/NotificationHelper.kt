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
    const val CHANNEL_ALERTS = "qnag_alerts"                 // legacy fallback

    const val MONITORING_SERVICE_NOTIF_ID = 9001

    // When ≥ this many new problems of the same state arrive in one poll cycle,
    // collapse them into a single summary notification to prevent a sound storm.
    private const val SUMMARY_THRESHOLD = 3

    // In-memory per-channel last-sound timestamps.  Resets on process restart — intentional;
    // avoids stale SharedPreferences reads and is good enough for anti-flood.
    private val lastSoundTimestampMs = ConcurrentHashMap<String, Long>()

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
