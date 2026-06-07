package com.exogroup.qnag.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.service.NagiosMonitoringService
import com.exogroup.qnag.worker.BackgroundPollingScheduler

/**
 * Exact Alarm Watchdog receiver — fires on every scheduled watchdog alarm.
 *
 * Recovery logic:
 *  - If Reliability Mode is OFF → cancel watchdog and exit.
 *  - If no eligible instances → cancel watchdog and exit.
 *  - If service is running AND poll is fresh → record healthy, reschedule.
 *  - If service is stopped OR poll is stale → attempt service restart,
 *    enqueue WorkManager one-shot check, post stale notification, reschedule.
 *
 * The receiver always reschedules itself at the end so the watchdog stays active.
 * No passwords, cookies, or Authorization headers are logged or stored here.
 */
class ExactAlarmWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExactAlarmWatchdogScheduler.ACTION) return

        MonitoringHealth.recordExactAlarmFired(context, "fired")
        android.util.Log.d("qNag", "[watchdog] alarm fired")

        val store = SecureInstanceStore(context)
        val settings = store.getAppSettings()
        val instances = store.getInstances()
        val cmd = settings.commandSettings

        // Exit if Reliability Mode is disabled
        if (!cmd.keepMonitoringActive) {
            ExactAlarmWatchdogScheduler.cancel(context)
            MonitoringHealth.recordExactAlarmFired(context, "reliability_off_canceled")
            return
        }

        // Exit if no eligible instances
        val eligible = settings.notificationSettings.notificationsEnabled &&
                instances.any { it.enabled && it.notificationsEnabled }
        if (!eligible) {
            ExactAlarmWatchdogScheduler.cancel(context)
            MonitoringHealth.recordExactAlarmFired(context, "not_eligible_canceled")
            return
        }

        // Evaluate service health
        val snapshot = MonitoringHealth.getSnapshot(context)
        val now = System.currentTimeMillis()
        val staleThresholdMs = cmd.monitoringStaleThresholdMinutes * 60_000L
        val lastSuccess = snapshot.lastSuccessfulPollAt
        val isStale = lastSuccess == null || (now - lastSuccess) > staleThresholdMs
        val isServiceRunning = snapshot.isServiceRunning

        val serviceHealthy = isServiceRunning && !isStale

        if (serviceHealthy) {
            // Service is alive and polling — only reschedule watchdog
            MonitoringHealth.recordExactAlarmFired(context, "healthy_rescheduled")
            if (cmd.debugCommandSubmission) android.util.Log.d("qNag", "[watchdog] service healthy — rescheduling")
        } else {
            val action = when {
                !isServiceRunning -> "service_stopped_recovery"
                isStale           -> "stale_poll_recovery"
                else              -> "unknown_recovery"
            }
            MonitoringHealth.recordExactAlarmFired(context, action)
            android.util.Log.w("qNag", "[watchdog] recovery: $action isServiceRunning=$isServiceRunning isStale=$isStale")

            // Best-effort: try to restart the foreground service
            try {
                NagiosMonitoringService.start(context)
            } catch (e: Exception) {
                android.util.Log.w("qNag", "[watchdog] service start failed: ${e.javaClass.simpleName}")
            }

            // Enqueue a one-shot WorkManager check as additional fallback
            BackgroundPollingScheduler.scheduleOnce(context)

            // Post stale notification if stale monitoring alerts are enabled
            if (cmd.staleMonitoringAlertEnabled) {
                val minAgo = lastSuccess?.let { (now - it) / 60_000L }
                val msg = if (minAgo != null) "No successful poll in ${minAgo}m (watchdog recovery)"
                          else "No successful poll detected (watchdog recovery)"
                NotificationHelper.notifyStale(context, msg)
            }
        }

        // Always reschedule watchdog so it keeps firing
        ExactAlarmWatchdogScheduler.schedule(context, settings)
    }
}
