package com.exogroup.qnag.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.exogroup.qnag.data.AppSettings
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.UserMonitoringPause
import java.util.concurrent.TimeUnit

/**
 * Schedules or cancels the background [NagiosPollingWorker].
 *
 * Rules:
 *  - Schedule only when global notifications are enabled AND at least one instance has
 *    both [NagiosInstance.enabled] and [NagiosInstance.notificationsEnabled] true.
 *  - Cancel otherwise.
 *  - WorkManager enforces a hard minimum of 15 minutes for periodic work.
 *  - Call [scheduleOrCancel] whenever settings or the instance list change.
 *  - All schedule/cancel calls record health state so [MonitoringHealthSection] can show
 *    an honest WorkManager status (scheduled vs. idle standby vs. overdue).
 */
object BackgroundPollingScheduler {

    private const val WORK_NAME = "qnag_background_polling"
    private const val ONE_TIME_WORK_NAME = "qnag_one_time_check"
    private const val MIN_INTERVAL_MINUTES = 15L

    fun scheduleOrCancel(context: Context, settings: AppSettings, instances: List<NagiosInstance>) {
        // Never run WorkManager alongside an active foreground service — they would double-notify.
        if (settings.commandSettings.keepMonitoringActive) {
            cancel(context)
            return
        }
        val hasEligibleInstance = instances.any { it.enabled && it.notificationsEnabled }
        if (settings.notificationSettings.notificationsEnabled && hasEligibleInstance) {
            schedule(context, settings.notificationSettings.refreshIntervalMinutes)
            MonitoringHealth.recordWorkerScheduled(context, "periodic")
        } else {
            cancel(context)
        }
    }

    private fun schedule(context: Context, intervalMinutes: Int) {
        val interval = intervalMinutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)

        val request = PeriodicWorkRequestBuilder<NagiosPollingWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        MonitoringHealth.recordWorkerCanceled(context)
    }

    /**
     * Schedule WorkManager as fallback, bypassing the keepMonitoringActive guard.
     *
     * Used by:
     *  - [BootReceiver] — after reboot, before the user reopens the app.
     *  - [NagiosMonitoringService.onDestroy] — when the foreground service is killed by the OS.
     *  - [ExactAlarmWatchdogReceiver] — watchdog-triggered recovery.
     *
     * Only schedules if notifications are enabled and at least one eligible instance exists.
     */
    fun scheduleFallback(context: Context, settings: AppSettings, instances: List<NagiosInstance>) {
        if (UserMonitoringPause.isPaused(context)) return
        val hasEligibleInstance = instances.any { it.enabled && it.notificationsEnabled }
        if (settings.notificationSettings.notificationsEnabled && hasEligibleInstance) {
            schedule(context, settings.notificationSettings.refreshIntervalMinutes)
            MonitoringHealth.recordWorkerScheduled(context, "fallback")
        }
        // If not eligible, leave WorkManager as-is (don't cancel existing work)
    }

    /**
     * Enqueue a one-time immediate polling check.
     *
     * Uses unique work ([ExistingWorkPolicy.KEEP]) so repeated calls from the watchdog
     * receiver or the UI button do not stack up duplicate checks.
     * The periodic WorkManager job is unaffected.
     */
    fun scheduleOnce(context: Context) {
        if (UserMonitoringPause.isPaused(context)) return
        val request = OneTimeWorkRequestBuilder<NagiosPollingWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // KEEP: if a one-time check is already queued/running, do not enqueue another.
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
