package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.InstanceSummary
import com.exogroup.qnag.data.NagiosInstance

/**
 * Compact aNag-style instance status summary, shown above the problem list.
 *
 * ALL mode  — global aggregate card + horizontally-scrollable per-instance cards.
 *             Tapping a per-instance card switches the dashboard to that instance.
 *             The per-instance row can be collapsed via the chevron icon.
 *
 * Single mode — one compact status row for the selected instance.
 */
@Composable
fun InstanceSummaryPanel(
    summaries: List<InstanceSummary>,
    isAllMode: Boolean,
    enabledInstances: List<NagiosInstance>,
    onSelectInstance: (NagiosInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summaries.isEmpty()) return

    if (isAllMode) {
        var expanded by rememberSaveable { mutableStateOf(true) }
        AllModeSummary(
            summaries = summaries,
            enabledInstances = enabledInstances,
            onSelectInstance = onSelectInstance,
            expanded = expanded,
            onToggleExpand = { expanded = !expanded },
            modifier = modifier,
        )
    } else {
        SingleModeSummary(summary = summaries.first(), modifier = modifier)
    }
}

// ── ALL mode ──────────────────────────────────────────────────────────────────

@Composable
private fun AllModeSummary(
    summaries: List<InstanceSummary>,
    enabledInstances: List<NagiosInstance>,
    onSelectInstance: (NagiosInstance) -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalDown = summaries.sumOf { it.hostDown }
    val totalUnreachable = summaries.sumOf { it.hostUnreachable }
    val totalCritical = summaries.sumOf { it.serviceCritical }
    val totalWarning = summaries.sumOf { it.serviceWarning }
    val totalUnknown = summaries.sumOf { it.serviceUnknown }
    val failedCount = summaries.count { it.fetchError != null }
    val hasProblems = totalDown + totalUnreachable + totalCritical + totalWarning + totalUnknown > 0
    val newestUpdate = summaries.mapNotNull { it.lastUpdated }.maxOrNull()

    Column(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ALL instances",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (newestUpdate != null) {
                        Text(
                            relativeTime(newestUpdate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                // Severity badges — each state has its own color
                if (hasProblems) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (totalDown > 0)        SummaryBadge("D$totalDown", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        if (totalUnreachable > 0) SummaryBadge("U$totalUnreachable", Color(0xFFFFF3E0), Color(0xFFE65100))
                        if (totalCritical > 0)    SummaryBadge("C$totalCritical", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        if (totalWarning > 0)     SummaryBadge("W$totalWarning", Color(0xFFFFF3CD), Color(0xFF856404))
                        if (totalUnknown > 0)     SummaryBadge("N$totalUnknown", Color(0xFFEDE7F6), Color(0xFF4A148C))
                    }
                } else if (failedCount == 0) {
                    Text(
                        "All instances OK",
                        style = MaterialTheme.typography.bodySmall,
                        color = okGreen(),
                    )
                }
                if (failedCount > 0) {
                    Text(
                        "⚠ $failedCount instance${if (failedCount != 1) "s" else ""} unreachable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Per-instance card row (collapsible)
        AnimatedVisibility(visible = expanded) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(summaries, key = { it.instanceId }) { summary ->
                    val inst = enabledInstances.find { it.id == summary.instanceId }
                    InstanceCard(
                        summary = summary,
                        onClick = inst?.let { { onSelectInstance(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(summary: InstanceSummary, onClick: (() -> Unit)?) {
    val chips = instanceSeverityChips(summary)

    Card(
        modifier = Modifier
            .width(152.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                summary.instanceName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Severity-specific chips stacked vertically
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                chips.forEach { (bgColor, textColor, label) ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = bgColor,
                        contentColor = textColor,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (summary.fetchError != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Fetch failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                relativeTime(summary.lastUpdated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Single mode ───────────────────────────────────────────────────────────────

@Composable
private fun SingleModeSummary(summary: InstanceSummary, modifier: Modifier = Modifier) {
    val chips = instanceSeverityChips(summary)
    val counts = compactCounts(summary)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Most-important severity chip
            chips.firstOrNull()?.let { (bgColor, textColor, label) ->
                Surface(shape = RoundedCornerShape(4.dp), color = bgColor, contentColor = textColor) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // Secondary chip if there are both criticals/warnings
            if (chips.size > 1) {
                chips.getOrNull(1)?.let { (bgColor, textColor, label) ->
                    Surface(shape = RoundedCornerShape(4.dp), color = bgColor, contentColor = textColor) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (counts.isNotEmpty()) {
                Text(
                    counts,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                relativeTime(summary.lastUpdated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Severity chip model ───────────────────────────────────────────────────────

private data class ChipSpec(val bg: Color, val fg: Color, val label: String)

/**
 * Returns severity-specific chips for a summary.
 * Instead of a generic "PROBLEMS" label, each problem type gets its own colored badge:
 * host DOWN (red), UNREACHABLE (amber), CRITICAL (red), WARNING (amber), UNKNOWN (purple).
 */
@Composable
private fun instanceSeverityChips(summary: InstanceSummary): List<ChipSpec> = when {
    !summary.enabled ->
        listOf(ChipSpec(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.outline, "DISABLED"))
    summary.fetchError != null ->
        listOf(ChipSpec(Color(0xFFFFF3E0), Color(0xFFE65100), "ERROR"))
    else -> buildList {
        if (summary.hostDown > 0)
            add(ChipSpec(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                "HOSTS DOWN ${summary.hostDown}"))
        if (summary.hostUnreachable > 0)
            add(ChipSpec(Color(0xFFFFF3E0), Color(0xFFE65100),
                "UNREACHABLE ${summary.hostUnreachable}"))
        if (summary.serviceCritical > 0)
            add(ChipSpec(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                "CRITICALS ${summary.serviceCritical}"))
        if (summary.serviceWarning > 0)
            add(ChipSpec(Color(0xFFFFF3CD), Color(0xFF856404),
                "WARNINGS ${summary.serviceWarning}"))
        if (summary.serviceUnknown > 0)
            add(ChipSpec(Color(0xFFEDE7F6), Color(0xFF4A148C),
                "UNKNOWN ${summary.serviceUnknown}"))
        if (isEmpty())
            add(ChipSpec(Color(0xFFE8F5E9), Color(0xFF2E7D32), "OK"))
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────────

/** Compact severity badge shown in the collapsed ALL view header. */
@Composable
private fun SummaryBadge(label: String, bgColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor, contentColor = textColor) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun okGreen() = Color(0xFF2E7D32)

/** Non-zero counts only, separated by " · ". */
private fun compactCounts(s: InstanceSummary): String = buildList {
    if (s.hostDown > 0) add("D${s.hostDown}")
    if (s.hostUnreachable > 0) add("U${s.hostUnreachable}")
    if (s.serviceCritical > 0) add("C${s.serviceCritical}")
    if (s.serviceWarning > 0) add("W${s.serviceWarning}")
    if (s.serviceUnknown > 0) add("N${s.serviceUnknown}")
}.joinToString(" · ")

private fun relativeTime(epochMs: Long?): String {
    if (epochMs == null) return "N/A"
    val diffSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
    }
}
