package com.exogroup.qnag.data

import android.content.Context

/**
 * Persistent guard for user-initiated monitoring pause ("Stop monitoring and exit").
 *
 * Separate from the Reliability Mode setting (keepMonitoringActive) — pausing does not
 * change the user's saved preference, only suppresses all auto-restart paths until the
 * user explicitly resumes from the Dashboard.
 */
object UserMonitoringPause {

    private const val PREFS     = "qnag_user_pause"
    private const val KEY_PAUSED = "paused_by_user"

    fun markPaused(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, true).apply()
    }

    fun clearPaused(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAUSED, false).apply()
    }

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED, false)
}
