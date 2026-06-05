package com.exogroup.qnag.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.worker.BackgroundPollingScheduler

/**
 * Schedules WorkManager fallback polling after device boot or package update.
 *
 * The foreground service is NOT auto-started here because:
 *  - Starting a foreground service from a boot receiver requires careful OS-version handling.
 *  - The user must open qNag to restart reliability-mode foreground polling.
 *
 * WorkManager polling (15-min minimum) is scheduled as fallback so qNag does not go
 * completely silent after a reboot — even if the foreground service hasn't restarted yet.
 *
 * The user is expected to see that the foreground service is not running via the
 * monitoring health section in Settings, and restart it from there.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MonitoringHealth.recordBootOrUpdate(context)
                val store = SecureInstanceStore(context)
                val settings = store.getAppSettings()
                val instances = store.getInstances()
                // Schedule WorkManager as fallback regardless of keepMonitoringActive,
                // so monitoring survives reboot even before the user reopens the app.
                BackgroundPollingScheduler.scheduleFallback(context, settings, instances)
            }
        }
    }
}
