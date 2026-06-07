package com.exogroup.qnag.reliability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.exogroup.qnag.data.AppSettings
import com.exogroup.qnag.data.MonitoringHealth

/**
 * Schedules and cancels the Exact Alarm Watchdog.
 *
 * The watchdog fires a periodic exact alarm to verify that the foreground service
 * is still healthy.  If the service has died or gone stale, the receiver attempts
 * best-effort recovery.
 *
 * Reliability Mode layers:
 *  1. Foreground service — primary high-frequency polling
 *  2. Exact Alarm Watchdog — recovery check if service is killed/stale  ← this file
 *  3. WorkManager — fallback after boot/package update or if exact alarms unavailable
 *
 * Android 12+ (API 31) requires the user to grant SCHEDULE_EXACT_ALARM permission.
 * If the permission is missing, scheduling is skipped and WorkManager remains the fallback.
 * No passwords, cookies, or Authorization headers are logged or stored here.
 */
object ExactAlarmWatchdogScheduler {

    const val ACTION = "com.exogroup.qnag.action.WATCHDOG_ALARM"
    private const val REQUEST_CODE = 7001

    /** Returns true if exact alarms can be scheduled on this device. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    /**
     * Schedule (or reschedule) the watchdog alarm.
     *
     * No-op if:
     *  - exactAlarmWatchdogEnabled is false
     *  - keepMonitoringActive is false
     *  - Android 12+ exact alarm permission is missing (recorded in MonitoringHealth)
     */
    fun schedule(context: Context, settings: AppSettings) {
        val cmd = settings.commandSettings
        if (!cmd.exactAlarmWatchdogEnabled || !cmd.keepMonitoringActive) {
            cancel(context)
            return
        }

        val am = context.getSystemService(AlarmManager::class.java) ?: return

        if (!canScheduleExactAlarms(context)) {
            MonitoringHealth.recordExactAlarmCanceled(context)
            android.util.Log.w("qNag", "[watchdog] exact alarm permission missing — relying on WorkManager fallback")
            return
        }

        val intervalMs = cmd.exactAlarmWatchdogIntervalMinutes.coerceAtLeast(1) * 60_000L
        val triggerAt = System.currentTimeMillis() + intervalMs

        val pi = pendingIntent(context, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return // If it's null, stop executing this function immediately
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        MonitoringHealth.recordExactAlarmScheduled(context, triggerAt)

        if (settings.commandSettings.debugCommandSubmission) {
            android.util.Log.d("qNag", "[watchdog] scheduled in ${cmd.exactAlarmWatchdogIntervalMinutes}m at $triggerAt")
        }
    }

    /** Cancel the watchdog alarm. */
    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        if (pi != null) am.cancel(pi)
        MonitoringHealth.recordExactAlarmCanceled(context)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? {
        val intent = Intent(context, ExactAlarmWatchdogReceiver::class.java).apply {
            action = ACTION
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
