package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal fun InstanceSummaryPanel(
    summaries: List<InstanceSummary>,
    isAllMode: Boolean,
    enabledInstances: List<NagiosInstance>,
    onSelectInstance: (NagiosInstance) -> Unit,
    expanded: Boolean = true,
    onExpandedChanged: (Boolean) -> Unit = {},
    // Quick filter — null means no active filter; clicking a chip toggles it
    quickFilter: QuickFilter? = null,
    onQuickFilterChanged: (QuickFilter?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (summaries.isEmpty()) return

    if (isAllMode) {
        AllModeSummary(
            summaries = summaries,
            enabledInstances = enabledInstances,
            onSelectInstance = onSelectInstance,
            expanded = expanded,
            onToggleExpand = { onExpandedChanged(!expanded) },
            quickFilter = quickFilter,
            onQuickFilterChanged = onQuickFilterChanged,
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
    quickFilter: QuickFilter? = null,
    onQuickFilterChanged: (QuickFilter?) -> Unit = {},
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
                // Severity badges — tappable quick filters. Active badge is outlined.
                // TODO: instance-specific chip filtering (tap instance card chip to filter to that instance+state)
                if (hasProblems) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (totalDown > 0) SummaryBadge(
                            label = "D$totalDown",
                            bgColor = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.onErrorContainer,
                            contentDescription = "$totalDown host${if (totalDown != 1) "s" else ""} down",
                            isActive = quickFilter == QuickFilter.HOST_DOWN,
                            onClick = { onQuickFilterChanged(if (quickFilter == QuickFilter.HOST_DOWN) null else QuickFilter.HOST_DOWN) },
                        )
                        if (totalUnreachable > 0) SummaryBadge(
                            label = "U$totalUnreachable",
                            bgColor = Color(0xFFFFF3E0),
                            textColor = Color(0xFFE65100),
                            contentDescription = "$totalUnreachable host${if (totalUnreachable != 1) "s" else ""} unreachable",
                            isActive = quickFilter == QuickFilter.HOST_UNREACHABLE,
                            onClick = { onQuickFilterChanged(if (quickFilter == QuickFilter.HOST_UNREACHABLE) null else QuickFilter.HOST_UNREACHABLE) },
                        )
                        if (totalCritical > 0) SummaryBadge(
                            label = "C$totalCritical",
                            bgColor = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.onErrorContainer,
                            contentDescription = "$totalCritical critical service${if (totalCritical != 1) "s" else ""}",
                            isActive = quickFilter == QuickFilter.SERVICE_CRITICAL,
                            onClick = { onQuickFilterChanged(if (quickFilter == QuickFilter.SERVICE_CRITICAL) null else QuickFilter.SERVICE_CRITICAL) },
                        )
                        if (totalWarning > 0) SummaryBadge(
                            label = "W$totalWarning",
                            bgColor = Color(0xFFFFF3CD),
                            textColor = Color(0xFF856404),
                            contentDescription = "$totalWarning warning service${if (totalWarning != 1) "s" else ""}",
                            isActive = quickFilter == QuickFilter.SERVICE_WARNING,
                            onClick = { onQuickFilterChanged(if (quickFilter == QuickFilter.SERVICE_WARNING) null else QuickFilter.SERVICE_WARNING) },
                        )
                        if (totalUnknown > 0) SummaryBadge(
                            label = "N$totalUnknown",
                            bgColor = Color(0xFFEDE7F6),
                            textColor = Color(0xFF4A148C),
                            contentDescription = "$totalUnknown unknown service${if (totalUnknown != 1) "s" else ""}",
                            isActive = quickFilter == QuickFilter.SERVICE_UNKNOWN,
                            onClick = { onQuickFilterChanged(if (quickFilter == QuickFilter.SERVICE_UNKNOWN) null else QuickFilter.SERVICE_UNKNOWN) },
                        )
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

/**
 * Compact severity badge in the ALL-mode summary header.
 *
 * When [onClick] is provided the badge acts as a quick filter toggle.
 * [isActive] adds a border to show the filter is active.
 * [contentDescription] is used for accessibility.
 */
@Composable
private fun SummaryBadge(
    label: String,
    bgColor: Color,
    textColor: Color,
    contentDescription: String? = null,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        contentColor = textColor,
        border = if (isActive) BorderStroke(2.dp, textColor) else null,
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (contentDescription != null) Modifier.semantics {
                this.contentDescription = contentDescription
            } else Modifier),
    ) {
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
