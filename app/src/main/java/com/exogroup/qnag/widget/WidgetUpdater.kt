package com.exogroup.qnag.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Triggers a Glance re-render for all instances of both home screen widgets.
 * Errors are swallowed so a widget failure never fails a poll cycle.
 */
object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        runCatching { CompactStatusWidget().updateAll(context) }
        runCatching { ProblemListWidget().updateAll(context) }
    }
}
