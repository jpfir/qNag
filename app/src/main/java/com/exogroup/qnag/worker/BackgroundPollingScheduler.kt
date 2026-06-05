package com.exogroup.qnag.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.exogroup.qnag.data.AppSettings
import com.exogroup.qnag.data.NagiosInstance
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
 */
object BackgroundPollingScheduler {

    private const val WORK_NAME = "qnag_background_polling"
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

        // UPDATE: keeps existing work but updates its period/constraints if changed.
        // Requires WorkManager 2.8.0+. Fall back to CANCEL_AND_REENQUEUE if this version
        // is not available in the dependency.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Schedule WorkManager as fallback, bypassing the keepMonitoringActive guard.
     *
     * Used by:
     *  - [BootReceiver] — after reboot, before the user reopens the app.
     *  - [NagiosMonitoringService.onDestroy] — when the foreground service is killed by the OS.
     *
     * Only schedules if notifications are enabled and at least one eligible instance exists.
     */
    fun scheduleFallback(context: Context, settings: AppSettings, instances: List<NagiosInstance>) {
        val hasEligibleInstance = instances.any { it.enabled && it.notificationsEnabled }
        if (settings.notificationSettings.notificationsEnabled && hasEligibleInstance) {
            schedule(context, settings.notificationSettings.refreshIntervalMinutes)
        }
        // If not eligible, leave WorkManager as-is (don't cancel existing work)
    }
}
