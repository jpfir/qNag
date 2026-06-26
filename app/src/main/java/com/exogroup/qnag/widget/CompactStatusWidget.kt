package com.exogroup.qnag.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.exogroup.qnag.MainActivity
import com.exogroup.qnag.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompactStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot      = WidgetSnapshotStore.load(context)
        val tapAction     = actionStartActivity<MainActivity>()
        val refreshAction = actionRunCallback<RefreshWidgetsAction>()
        provideContent { CompactContent(snapshot, tapAction, refreshAction) }
    }
}

class CompactStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactStatusWidget()
}

// ── Palette ───────────────────────────────────────────────────────────────────

private val cBg      = ColorProvider(Color(0xFF1A1D27))
private val cAccent  = ColorProvider(Color(0xFFBB86FC))
private val cMuted   = ColorProvider(Color(0xFF78909C))
private val cSubtle  = ColorProvider(Color(0xFF546E7A))
private val cSuccess = ColorProvider(Color(0xFF81C784))
private val cStale   = ColorProvider(Color(0xFFFFB74D))
private val cWarn    = ColorProvider(Color(0xFFFFB74D))
private val cError   = ColorProvider(Color(0xFFEF5350))
private val cDown    = ColorProvider(Color(0xFFE57373))
private val cCrit    = ColorProvider(Color(0xFFEF5350))
private val cUnk     = ColorProvider(Color(0xFF9E9E9E))

// ── Root composable ───────────────────────────────────────────────────────────
//
// GLANCE CONSTRAINT: Column/Row max 10 direct children.
// Root Column has exactly 5 children in the worst normal case.

@Composable
private fun CompactContent(
    snapshot: WidgetStatusSnapshot?,
    tapAction: Action,
    refreshAction: Action,
) {
    val now              = System.currentTimeMillis()
    val refreshState     = snapshot?.refreshState ?: WidgetRefreshState.IDLE
    val isPartialFailure = snapshot != null && !snapshot.noEnabledInstances
        && snapshot.instanceFailed > 0 && snapshot.instanceOk > 0 && snapshot.instanceTotal > 0
    val isFullFailure    = snapshot != null && !snapshot.noEnabledInstances
        && snapshot.instanceFailed > 0 && snapshot.instanceOk == 0 && snapshot.instanceTotal > 0
    val isStale          = snapshot != null
        && !snapshot.noEnabledInstances
        && !isPartialFailure && !isFullFailure
        && refreshState == WidgetRefreshState.IDLE
        && (now - snapshot.lastUpdated) > WidgetSnapshotStore.STALE_THRESHOLD_MS
    val anyFailure = isPartialFailure || isFullFailure

    // Title and color for header
    val headerTitle = when {
        anyFailure -> "qNag FAILURE"
        snapshot == null || snapshot.noEnabledInstances -> "qNag"
        snapshot.sourceTitle.isNotBlank() -> snapshot.sourceTitle
        else -> "All instances"
    }
    val headerColor = if (anyFailure) cError else cAccent

    // Root column — max 5 children (Header + Spacer + SubHeader + Spacer + Body)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(cBg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(tapAction),
    ) {
        HeaderRow(headerTitle, headerColor, refreshAction)   // child 1
        Spacer(GlanceModifier.height(2.dp))                  // child 2
        SubHeaderRow(snapshot, refreshState, isStale, isPartialFailure, isFullFailure) // child 3
        Spacer(GlanceModifier.height(4.dp))                  // child 4
        BodySection(snapshot, isPartialFailure, isFullFailure) // child 5
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderRow(title: String, titleColor: ColorProvider, refreshAction: Action) {
    Row(
        modifier          = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider           = ImageProvider(R.drawable.ic_widget_qnag),
            contentDescription = null,
            modifier           = GlanceModifier.size(16.dp),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            title,
            style    = TextStyle(color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        Box(modifier = GlanceModifier.clickable(refreshAction).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("↻", style = TextStyle(color = cMuted, fontSize = 14.sp))
        }
    }
}

// ── Sub-header: failure detail / timestamp / state message ────────────────────

@Composable
private fun SubHeaderRow(
    snapshot: WidgetStatusSnapshot?,
    refreshState: WidgetRefreshState,
    isStale: Boolean,
    isPartialFailure: Boolean,
    isFullFailure: Boolean,
) {
    when (refreshState) {
        WidgetRefreshState.REFRESHING -> {
            Text("Refreshing…", style = TextStyle(color = cWarn, fontSize = 10.sp))
        }
        WidgetRefreshState.FAILED -> {
            val msg = if (!snapshot?.lastRefreshError.isNullOrBlank())
                "Refresh failed  ·  ${snapshot!!.lastRefreshError}"
            else "Refresh failed"
            Text(msg, style = TextStyle(color = cError, fontSize = 10.sp), maxLines = 1)
        }
        WidgetRefreshState.IDLE -> when {
            isFullFailure && snapshot != null -> {
                Text(
                    "0/${snapshot.instanceTotal} instances reachable",
                    style = TextStyle(color = cError, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
            isPartialFailure && snapshot != null -> {
                val text = "${snapshot.instanceOk} ok / ${snapshot.instanceFailed} failed  ·  partial"
                Text(text, style = TextStyle(color = cError, fontSize = 10.sp), maxLines = 1)
            }
            snapshot != null && !snapshot.noEnabledInstances && snapshot.lastUpdated > 0L -> {
                val time    = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.lastUpdated))
                val tsColor = if (isStale) cStale else cSubtle
                val tsText  = if (isStale) "Updated $time  ·  stale" else "Updated $time"
                Text(tsText, style = TextStyle(color = tsColor, fontSize = 10.sp))
            }
            else -> {}
        }
    }
}

// ── Body ──────────────────────────────────────────────────────────────────────
//
// Emits directly into root Column — max 5 children allowed here.

@Composable
private fun BodySection(
    snapshot: WidgetStatusSnapshot?,
    isPartialFailure: Boolean,
    isFullFailure: Boolean,
) {
    when {
        snapshot == null -> {
            Text("Open qNag to load status", style = TextStyle(color = cMuted, fontSize = 11.sp))
        }
        snapshot.noEnabledInstances -> {
            Text("No enabled instances", style = TextStyle(color = cMuted, fontSize = 11.sp))
            Spacer(GlanceModifier.height(1.dp))
            Text("Open qNag to add one", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        }
        isFullFailure -> {
            Text("Tap to open qNag", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        }
        isPartialFailure -> {
            // Show service counts + timestamp in one line
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.lastUpdated))
            val svcText = "S  C:${snapshot.critical}  W:${snapshot.warning}  U:${snapshot.unknown}  ·  $time"
            Text(svcText, style = TextStyle(color = cMuted, fontSize = 10.sp), maxLines = 1)
        }
        snapshot.totalProblems == 0 -> {
            Text("All clear", style = TextStyle(color = cSuccess, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            if (snapshot.instanceTotal > 0) {
                Spacer(GlanceModifier.height(3.dp))
                InstRow(snapshot)
            }
        }
        else -> {
            if (snapshot.instanceTotal > 0) {
                InstRow(snapshot)
                Spacer(GlanceModifier.height(3.dp))
            }
            HostsRow(snapshot)
            Spacer(GlanceModifier.height(3.dp))
            SvcRow(snapshot)
        }
    }
}

// ── Summary rows ──────────────────────────────────────────────────────────────

@Composable
private fun InstRow(s: WidgetStatusSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Inst: ", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        Text("${s.instanceOk} ok", style = TextStyle(color = cSuccess, fontSize = 10.sp))
        Text("  /  ", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        val failColor = if (s.instanceFailed > 0) cError else cMuted
        Text("${s.instanceFailed} failed", style = TextStyle(color = failColor, fontSize = 10.sp))
    }
}

@Composable
private fun HostsRow(s: WidgetStatusSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hosts: ", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        if (s.hostTotal != null) {
            Text("T:${s.hostTotal}", style = TextStyle(color = cMuted, fontSize = 10.sp))
            Spacer(GlanceModifier.width(6.dp))
        }
        if (s.hostUp != null) {
            Text("UP:${s.hostUp}", style = TextStyle(color = cSuccess, fontSize = 10.sp))
            Spacer(GlanceModifier.width(6.dp))
        }
        val downColor = if (s.down > 0) cDown else cMuted
        Text("DOWN:${s.down}", style = TextStyle(color = downColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(6.dp))
        val unrColor = if (s.unreachable > 0) cWarn else cMuted
        Text("UNR:${s.unreachable}", style = TextStyle(color = unrColor, fontSize = 10.sp))
    }
}

@Composable
private fun SvcRow(s: WidgetStatusSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Svc:   ", style = TextStyle(color = cSubtle, fontSize = 10.sp))
        if (s.serviceTotal != null) {
            Text("T:${s.serviceTotal}", style = TextStyle(color = cMuted, fontSize = 10.sp))
            Spacer(GlanceModifier.width(6.dp))
        }
        if (s.serviceOk != null) {
            Text("OK:${s.serviceOk}", style = TextStyle(color = cSuccess, fontSize = 10.sp))
            Spacer(GlanceModifier.width(6.dp))
        }
        val critColor = if (s.critical > 0) cCrit else cMuted
        Text("CRIT:${s.critical}", style = TextStyle(color = critColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(6.dp))
        val warnColor = if (s.warning > 0) cWarn else cMuted
        Text("WARN:${s.warning}", style = TextStyle(color = warnColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(6.dp))
        val unkColor = if (s.unknown > 0) cUnk else cMuted
        Text("UNK:${s.unknown}", style = TextStyle(color = unkColor, fontSize = 10.sp))
    }
}
