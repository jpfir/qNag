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

class ProblemListWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot      = WidgetSnapshotStore.load(context)
        val tapAction     = actionStartActivity<MainActivity>()
        val refreshAction = actionRunCallback<RefreshWidgetsAction>()
        provideContent { ProblemListContent(snapshot, tapAction, refreshAction) }
    }
}

class ProblemListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProblemListWidget()
}

// ── Palette ───────────────────────────────────────────────────────────────────

private val pBg      = ColorProvider(Color(0xFF1A1D27))
private val pAccent  = ColorProvider(Color(0xFFBB86FC))
private val pTitle   = ColorProvider(Color(0xFFECEFF1))
private val pMuted   = ColorProvider(Color(0xFF78909C))
private val pSubtle  = ColorProvider(Color(0xFF546E7A))
private val pSuccess = ColorProvider(Color(0xFF81C784))
private val pStale   = ColorProvider(Color(0xFFFFB74D))
private val pWarn    = ColorProvider(Color(0xFFFFB74D))
private val pError   = ColorProvider(Color(0xFFEF5350))

private val pDownTx = ColorProvider(Color(0xFFE57373))
private val pCritTx = ColorProvider(Color(0xFFEF5350))
private val pWarnTx = ColorProvider(Color(0xFFFFB74D))
private val pUnkTx  = ColorProvider(Color(0xFF9E9E9E))

private val pDownBg = ColorProvider(Color(0x33E57373))
private val pCritBg = ColorProvider(Color(0x33EF5350))
private val pWarnBg = ColorProvider(Color(0x33FFB74D))
private val pUnkBg  = ColorProvider(Color(0x339E9E9E))

// ── Root composable ───────────────────────────────────────────────────────────
//
// GLANCE CONSTRAINT: Column/Row containers allow max 10 direct children.
// Every section is wrapped in its own Column/Row so children never cascade
// into the parent container. Root Column has exactly 3 children.

@Composable
private fun ProblemListContent(
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
    val anyFailure = isPartialFailure || isFullFailure
    val isStale    = snapshot != null
        && !snapshot.noEnabledInstances && !anyFailure
        && refreshState == WidgetRefreshState.IDLE
        && (now - snapshot.lastUpdated) > WidgetSnapshotStore.STALE_THRESHOLD_MS

    val headerTitle = when {
        anyFailure -> "qNag FAILURE"
        snapshot == null || snapshot.noEnabledInstances -> "qNag"
        snapshot.sourceTitle.isNotBlank() -> snapshot.sourceTitle
        else -> "All instances"
    }
    val headerColor = if (anyFailure) pError else pAccent

    // Root column: 3 direct children only
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(pBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(tapAction),
    ) {
        PLHeaderRow(headerTitle, headerColor, refreshAction)              // child 1
        PLSubHeaderRow(snapshot, refreshState, isStale, isPartialFailure, isFullFailure) // child 2
        PLBody(snapshot, isPartialFailure, isFullFailure)                 // child 3
    }
}

// ── Header row ────────────────────────────────────────────────────────────────

@Composable
private fun PLHeaderRow(title: String, titleColor: ColorProvider, refreshAction: Action) {
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
            Text("↻", style = TextStyle(color = pMuted, fontSize = 14.sp))
        }
    }
}

// ── Sub-header: failure detail / instance summary + timestamp ─────────────────

@Composable
private fun PLSubHeaderRow(
    snapshot: WidgetStatusSnapshot?,
    refreshState: WidgetRefreshState,
    isStale: Boolean,
    isPartialFailure: Boolean,
    isFullFailure: Boolean,
) {
    when (refreshState) {
        WidgetRefreshState.REFRESHING -> {
            Text("Refreshing…", style = TextStyle(color = pWarn, fontSize = 10.sp))
        }
        WidgetRefreshState.FAILED -> {
            val msg = if (!snapshot?.lastRefreshError.isNullOrBlank())
                "Refresh failed  ·  ${snapshot!!.lastRefreshError}"
            else "Refresh failed"
            Text(msg, style = TextStyle(color = pError, fontSize = 10.sp), maxLines = 1)
        }
        WidgetRefreshState.IDLE -> when {
            isFullFailure && snapshot != null -> {
                Text(
                    "0/${snapshot.instanceTotal} instances reachable",
                    style = TextStyle(color = pError, fontSize = 10.sp), maxLines = 1,
                )
            }
            isPartialFailure && snapshot != null -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.lastUpdated))
                Text(
                    "${snapshot.instanceOk} ok / ${snapshot.instanceFailed} failed  ·  Updated $time",
                    style = TextStyle(color = pError, fontSize = 10.sp), maxLines = 1,
                )
            }
            snapshot != null && !snapshot.noEnabledInstances && snapshot.lastUpdated > 0L -> {
                val time    = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snapshot.lastUpdated))
                val tsColor = if (isStale) pStale else pSubtle
                val instPfx = if (snapshot.instanceTotal > 0)
                    "${snapshot.instanceOk} ok / ${snapshot.instanceFailed} failed  ·  "
                else ""
                val tsText = if (isStale) "${instPfx}$time  ·  stale" else "${instPfx}Updated $time"
                Text(tsText, style = TextStyle(color = tsColor, fontSize = 10.sp), maxLines = 1)
            }
            else -> {}
        }
    }
}

// ── Body — has its own Column; root stays at 3 children ──────────────────────

@Composable
private fun PLBody(
    snapshot: WidgetStatusSnapshot?,
    isPartialFailure: Boolean,
    isFullFailure: Boolean,
) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        when {
            snapshot == null -> {
                Text("Open qNag to load status", style = TextStyle(color = pMuted, fontSize = 11.sp))
            }
            snapshot.noEnabledInstances -> {
                Text("No enabled instances", style = TextStyle(color = pMuted, fontSize = 11.sp))
                Text("Open qNag to add one", style = TextStyle(color = pSubtle, fontSize = 10.sp))
            }
            isFullFailure -> {
                // max 2 children
                Text("Open qNag for details", style = TextStyle(color = pSubtle, fontSize = 10.sp))
                if (snapshot.topProblems.isNotEmpty()) {
                    PLProblemsBlock(snapshot)
                }
            }
            isPartialFailure -> {
                // max 3 children
                PLSummaryBlock(snapshot)
                PLInstancesBlock(snapshot)  // shows FAILED for failed instances
                if (snapshot.topProblems.isNotEmpty()) {
                    PLProblemsBlock(snapshot)
                }
            }
            snapshot.totalProblems == 0 -> {
                Text("All clear", style = TextStyle(color = pSuccess, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                if (snapshot.instanceSummaries.isNotEmpty()) {
                    PLInstancesBlock(snapshot)
                }
            }
            else -> {
                // max 3 direct children in this branch
                PLSummaryBlock(snapshot)
                if (snapshot.instanceSummaries.isNotEmpty()) {
                    PLInstancesBlock(snapshot)
                }
                if (snapshot.topProblems.isNotEmpty()) {
                    PLProblemsBlock(snapshot)
                }
            }
        }
    }
}

// ── Summary block: Hosts + Svc rows ─────────────────────────────────────────

@Composable
private fun PLSummaryBlock(s: WidgetStatusSnapshot) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        PLHostsRow(s)   // child 1
        PLSvcRow(s)     // child 2
    }
}

@Composable
private fun PLHostsRow(s: WidgetStatusSnapshot) {
    // Row children (max 9 with both optional fields):
    // "Hosts:" · [T:N ·] [UP:N ·] DOWN:N · UNR:N
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hosts:", style = TextStyle(color = pSubtle, fontSize = 10.sp))
        Spacer(GlanceModifier.width(5.dp))
        if (s.hostTotal != null) {
            Text("T:${s.hostTotal}", style = TextStyle(color = pMuted, fontSize = 10.sp))
            Spacer(GlanceModifier.width(5.dp))
        }
        if (s.hostUp != null) {
            Text("UP:${s.hostUp}", style = TextStyle(color = pSuccess, fontSize = 10.sp))
            Spacer(GlanceModifier.width(5.dp))
        }
        val downColor = if (s.down > 0) pDownTx else pMuted
        Text("DOWN:${s.down}", style = TextStyle(color = downColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(5.dp))
        val unrColor = if (s.unreachable > 0) pWarnTx else pMuted
        Text("UNR:${s.unreachable}", style = TextStyle(color = unrColor, fontSize = 10.sp))
    }
}

@Composable
private fun PLSvcRow(s: WidgetStatusSnapshot) {
    // Row children (max 10 with both optional fields — no spacer after label):
    // "Svc:  " [T:N ·] [OK:N ·] CRIT:N · WARN:N · UNK:N
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Svc:  ", style = TextStyle(color = pSubtle, fontSize = 10.sp))
        if (s.serviceTotal != null) {
            Text("T:${s.serviceTotal}", style = TextStyle(color = pMuted, fontSize = 10.sp))
            Spacer(GlanceModifier.width(5.dp))
        }
        if (s.serviceOk != null) {
            Text("OK:${s.serviceOk}", style = TextStyle(color = pSuccess, fontSize = 10.sp))
            Spacer(GlanceModifier.width(5.dp))
        }
        val critColor = if (s.critical > 0) pCritTx else pMuted
        Text("CRIT:${s.critical}", style = TextStyle(color = critColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(5.dp))
        val warnColor = if (s.warning > 0) pWarnTx else pMuted
        Text("WARN:${s.warning}", style = TextStyle(color = warnColor, fontSize = 10.sp))
        Spacer(GlanceModifier.width(5.dp))
        val unkColor = if (s.unknown > 0) pUnkTx else pMuted
        Text("UNK:${s.unknown}", style = TextStyle(color = unkColor, fontSize = 10.sp))
    }
}

// ── Instances block ──────────────────────────────────────────────────────────

@Composable
private fun PLInstancesBlock(snapshot: WidgetStatusSnapshot) {
    val sorted = snapshot.instanceSummaries.sortedWith(
        compareByDescending<WidgetInstanceSummary> { if (it.failed) Int.MAX_VALUE else it.totalProblems }
            .thenBy { it.instanceName }
    )
    val show      = sorted.take(3)
    val remaining = sorted.size - show.size
    // Column children: 3 instance rows + optional "+N more" = max 4
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        show.forEach { inst ->
            PLInstanceRow(inst)
        }
        if (remaining > 0) {
            Text("+$remaining more", style = TextStyle(color = pSubtle, fontSize = 9.sp))
        }
    }
}

@Composable
private fun PLInstanceRow(inst: WidgetInstanceSummary) {
    Row(
        modifier          = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            inst.instanceName.take(16),
            style    = TextStyle(color = pMuted, fontSize = 10.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        when {
            inst.failed -> {
                Text("FAILED", style = TextStyle(color = pError, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
            inst.totalProblems == 0 -> {
                Text("OK", style = TextStyle(color = pSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
            else -> {
                // Single colored Text to avoid Row child overflow (Row limit = 10)
                val parts = buildList {
                    if (inst.down > 0)        add("DOWN:${inst.down}")
                    if (inst.critical > 0)    add("CRIT:${inst.critical}")
                    if (inst.unreachable > 0) add("UNR:${inst.unreachable}")
                    if (inst.warning > 0)     add("WARN:${inst.warning}")
                    if (inst.unknown > 0)     add("UNK:${inst.unknown}")
                }
                val color = when {
                    inst.down > 0        -> pDownTx
                    inst.critical > 0    -> pCritTx
                    inst.unreachable > 0 -> pWarnTx
                    inst.warning > 0     -> pWarnTx
                    else                 -> pUnkTx
                }
                Text(
                    parts.joinToString(" "),
                    style    = TextStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Problems block ────────────────────────────────────────────────────────────

@Composable
private fun PLProblemsBlock(snapshot: WidgetStatusSnapshot) {
    val show      = snapshot.topProblems.take(3)
    val remaining = snapshot.totalProblems - show.size
    // Column children: "Open problems" header + 3 rows + optional "+N more" = max 5
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Open problems", style = TextStyle(color = pSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        show.forEach { problem ->
            PLProblemRow(problem, multiInstance = snapshot.instanceSummaries.size > 1)
        }
        if (remaining > 0) {
            Text("+$remaining more  —  open qNag", style = TextStyle(color = pSubtle, fontSize = 9.sp))
        }
    }
}

@Composable
private fun PLProblemRow(problem: WidgetProblemSummary, multiInstance: Boolean) {
    val statusTx = when (problem.status) {
        "DOWN"  -> pDownTx
        "CRIT"  -> pCritTx
        "UNRCH" -> pWarnTx
        "WARN"  -> pWarnTx
        else    -> pUnkTx
    }
    val statusBg = when (problem.status) {
        "DOWN"  -> pDownBg
        "CRIT"  -> pCritBg
        "UNRCH" -> pWarnBg
        "WARN"  -> pWarnBg
        else    -> pUnkBg
    }
    val target = if (problem.serviceName != null)
        "${problem.hostName} / ${problem.serviceName}"
    else
        problem.hostName
    val displayTarget = if (multiInstance)
        "${problem.instanceName.take(8)}  $target"
    else
        target

    // Single Row per problem — compact, no Column nesting
    Row(
        modifier          = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.background(statusBg).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Text(problem.status, style = TextStyle(color = statusTx, fontSize = 9.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.width(5.dp))
        Text(displayTarget, style = TextStyle(color = pTitle, fontSize = 10.sp), maxLines = 1)
    }
}
