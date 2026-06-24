package com.exogroup.qnag.data

import android.content.Context
import androidx.core.content.edit

/**
 * Plain-SharedPreferences health store.
 *
 * Records when qNag polls, when it succeeds, when the foreground service starts/stops,
 * when WorkManager runs/is scheduled/is canceled, and when the Exact Alarm Watchdog fires.
 * Data is non-sensitive (timestamps + reason strings only).
 */
object MonitoringHealth {

    private const val PREFS = "qnag_health"

    // ── Writers — called from service, worker, receiver, and boot receiver ────

    fun recordPollStart(context: Context) =
        prefs(context).edit { putLong("last_poll_start", now()) }

    fun recordPollFinished(context: Context) =
        prefs(context).edit { putLong("last_poll_finished", now()) }

    /** Call after at least one instance returned problems successfully (or was OK). */
    fun recordPollSuccess(context: Context) =
        prefs(context).edit { putLong("last_poll_success", now()) }

    fun recordServiceStart(context: Context) = prefs(context).edit {
        putLong("last_service_start", now())
        putBoolean("service_running", true)
        remove("last_service_stop_reason")
    }

    fun recordServiceStop(context: Context, reason: String) = prefs(context).edit {
        putLong("last_service_stop", now())
        putString("last_service_stop_reason", reason)
        putBoolean("service_running", false)
    }

    fun recordWorkerRun(context: Context) =
        prefs(context).edit { putLong("last_worker_run", now()) }

    fun recordWorkerSuccess(context: Context) =
        prefs(context).edit { putLong("last_worker_success", now()) }

    fun recordBootOrUpdate(context: Context) =
        prefs(context).edit { putLong("last_boot_or_update", now()) }

    // ── WorkManager schedule/cancel writers ──────────────────────────

    /** Record that the periodic WorkManager job has been scheduled. */
    fun recordWorkerScheduled(context: Context, reason: String = "periodic") = prefs(context).edit {
        putLong("last_worker_scheduled", now())
        putBoolean("worker_scheduled", true)
        putString("last_worker_schedule_reason", reason)
    }

    /** Record that the periodic WorkManager job has been explicitly canceled. */
    fun recordWorkerCanceled(context: Context) = prefs(context).edit {
        putLong("last_worker_canceled", now())
        putBoolean("worker_scheduled", false)
    }

    // ── Watchdog writers ──────────────────────────────────────────────────────

    fun recordExactAlarmFired(context: Context, action: String) = prefs(context).edit {
        putLong("last_exact_alarm_fired", now())
        putString("last_exact_alarm_action", action)
    }

    fun recordExactAlarmScheduled(context: Context, nextAlarmAt: Long) = prefs(context).edit {
        putLong("next_exact_alarm", nextAlarmAt)
        putBoolean("exact_alarm_scheduled", true)
    }

    fun recordExactAlarmCanceled(context: Context) = prefs(context).edit {
        putBoolean("exact_alarm_scheduled", false)
        remove("next_exact_alarm")
    }

    // ── Reader ────────────────────────────────────────────────────────────────

    fun getSnapshot(context: Context): HealthSnapshot {
        val p = prefs(context)
        return HealthSnapshot(
            lastPollStartedAt     = p.getLong("last_poll_start",          0).takeIf { it > 0 },
            lastPollFinishedAt    = p.getLong("last_poll_finished",        0).takeIf { it > 0 },
            lastSuccessfulPollAt  = p.getLong("last_poll_success",         0).takeIf { it > 0 },
            lastServiceStartedAt  = p.getLong("last_service_start",        0).takeIf { it > 0 },
            lastServiceStoppedAt  = p.getLong("last_service_stop",         0).takeIf { it > 0 },
            lastServiceStopReason = p.getString("last_service_stop_reason", null),
            isServiceRunning      = p.getBoolean("service_running",        false),
            lastWorkerRunAt       = p.getLong("last_worker_run",           0).takeIf { it > 0 },
            lastWorkerSuccessAt   = p.getLong("last_worker_success",       0).takeIf { it > 0 },
            lastBootOrUpdateAt    = p.getLong("last_boot_or_update",       0).takeIf { it > 0 },
            // WorkManager schedule state
            workerScheduled          = p.getBoolean("worker_scheduled",       false),
            lastWorkerScheduledAt    = p.getLong("last_worker_scheduled",     0).takeIf { it > 0 },
            lastWorkerCanceledAt     = p.getLong("last_worker_canceled",      0).takeIf { it > 0 },
            lastWorkerScheduleReason = p.getString("last_worker_schedule_reason", null),
            // Watchdog
            exactAlarmScheduled   = p.getBoolean("exact_alarm_scheduled",   false),
            nextExactAlarmAt      = p.getLong("next_exact_alarm",           0).takeIf { it > 0 },
            lastExactAlarmFiredAt = p.getLong("last_exact_alarm_fired",     0).takeIf { it > 0 },
            lastExactAlarmAction  = p.getString("last_exact_alarm_action",  null),
        )
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class HealthSnapshot(
        val lastPollStartedAt: Long?,
        val lastPollFinishedAt: Long?,
        val lastSuccessfulPollAt: Long?,
        val lastServiceStartedAt: Long?,
        val lastServiceStoppedAt: Long?,
        val lastServiceStopReason: String?,
        val isServiceRunning: Boolean,
        val lastWorkerRunAt: Long?,
        val lastWorkerSuccessAt: Long?,
        val lastBootOrUpdateAt: Long?,
        // WorkManager schedule state
        val workerScheduled: Boolean,
        val lastWorkerScheduledAt: Long?,
        val lastWorkerCanceledAt: Long?,
        val lastWorkerScheduleReason: String?,
        // Watchdog
        val exactAlarmScheduled: Boolean,
        val nextExactAlarmAt: Long?,
        val lastExactAlarmFiredAt: Long?,
        val lastExactAlarmAction: String?,
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = System.currentTimeMillis()
}
