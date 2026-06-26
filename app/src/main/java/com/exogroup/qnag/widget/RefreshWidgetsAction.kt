package com.exogroup.qnag.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters

/**
 * Glance [ActionCallback] wired to the refresh icon in both widgets.
 *
 * On tap: fetches current filtered problems from all enabled Nagios instances and
 * updates the widget snapshot. No monitoring commands (ACK/Recheck/Downtime) are sent.
 */
class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetRefresher.refresh(context)
    }
}
