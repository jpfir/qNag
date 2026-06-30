package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.HiddenReason
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus

// ── Classic / aNag-style row ──────────────────────────────────────────────────
//
// Dense flat rows matching aNag's look: no rounded cards, no chips in collapsed
// view. Severity shown via colored name text. Plugin output visible directly.
// Tap to expand inline details + ACK/Recheck/Downtime buttons.

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProblemClassicRow(
    problem: NagiosProblem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isAcknowledged: Boolean = false,
    isPendingAck: Boolean = false,
    instanceName: String = "",
    isRecheckPending: Boolean = false,
    isTier2Waiting: Boolean = false,
    hiddenReasons: List<HiddenReason> = emptyList(),
    relatedServiceCount: Int = 0,
    onOpenDetail: (() -> Unit)? = null,
    onCopyOutput: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onOpenInNagios: (() -> Unit)? = null,
    onScheduleDowntime: (() -> Unit)? = null,
    onAckAllServicesOnHost: (() -> Unit)? = null,
    onRecheckAllServicesOnHost: (() -> Unit)? = null,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onUnack: (() -> Unit)? = null,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
    // False while the list is scrolling or within 250 ms of scroll stop; also false in selection mode
    swipeAllowed: Boolean = true,
    // When true plugin output is always fully visible (Classic expanded style)
    alwaysShowFullOutput: Boolean = false,
) {
    var isExpanded by remember(problem.uniqueId, problem.instanceId) { mutableStateOf(false) }

    val dark = isSystemInDarkTheme()
    val accentColor = rowAccentColor(problem, dark)
    val isHidden = hiddenReasons.isNotEmpty()

    // Name text uses severity color; hidden rows are muted
    val nameColor = if (isHidden) accentColor.copy(alpha = 0.45f) else accentColor

    CommandSwipeContainer(
        problemKey = "${problem.uniqueId}|${problem.instanceId}",
        isSelectionMode = isSelectionMode,
        swipeAllowed = swipeAllowed,
        onAck = onAck,
        onRecheck = onRecheck,
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        isSelected -> accentColor.copy(alpha = 0.12f)
                        isHidden   -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        else       -> Color.Transparent
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (isSelectionMode) onToggleSelect()
                        else isExpanded = !isExpanded
                    },
                    onLongClick = onLongPress,
                )
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {

                    // ── Line 1: name, severity-colored ───────────────────────
                    val nameText = when (problem) {
                        is NagiosProblem.ServiceProblem -> problem.serviceName
                        is NagiosProblem.HostProblem -> when (problem.status) {
                            NagiosStatus.HOST_DOWN        -> "HOST DOWN  ${problem.hostName}"
                            NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE  ${problem.hostName}"
                            else                          -> problem.hostName
                        }
                    }
                    Text(
                        text = nameText,
                        color = nameColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // ── Line 2: context text + compact state tag pills ─────────
                    val contextText = when (problem) {
                        is NagiosProblem.ServiceProblem ->
                            if (instanceName.isNotEmpty()) "$instanceName > ${problem.hostName}"
                            else problem.hostName
                        is NagiosProblem.HostProblem ->
                            instanceName.takeIf { it.isNotEmpty() } ?: ""
                    }
                    // Compact tags matching Modern badge colors: hidden reason first, then state flags
                    val isNew = problem.lastStateChange?.let {
                        (System.currentTimeMillis() - it) < NEW_STATE_THRESHOLD_MS
                    } ?: false
                    val stateTags = buildList<String> {
                        if (isHidden) {
                            add(when {
                                hiddenReasons.isEmpty() -> "HIDDEN"
                                hiddenReasons.size <= 2 ->
                                    "HIDDEN: ${hiddenReasons.joinToString(" ") { it.label }}"
                                else -> "HIDDEN"
                            })
                        }
                        if (isNew) add("NEW")
                        if (isAcknowledged)
                            add(if (isPendingAck && !problem.acknowledged) "ACK…" else "ACK")
                        if (problem.scheduledDowntimeDepth > 0) add("DT")
                        if (problem is NagiosProblem.ServiceProblem &&
                            problem.hostScheduledDowntimeDepth > 0) add("HOST DT")
                        if (problem.isSoftState) add("SOFT")
                        if (problem.isFlapping) add("FLAP")
                        if (!problem.notificationsEnabled) add("NOTIF OFF")
                        if (!problem.checksEnabled) add("CHECKS OFF")
                        if (isRecheckPending) add("RECHECK")
                        if (isTier2Waiting) add("T2+")
                    }
                    if (contextText.isNotEmpty() || stateTags.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (contextText.isNotEmpty()) {
                                Text(
                                    text = contextText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHidden)
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (stateTags.isNotEmpty()) {
                                Spacer(Modifier.width(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    stateTags.forEach { text ->
                                        ClassicStateTag(text)
                                    }
                                }
                            }
                        }
                    }

                    // ── HOST DOWN: related service hint ───────────────────────
                    if (problem is NagiosProblem.HostProblem &&
                        problem.status == NagiosStatus.HOST_DOWN &&
                        relatedServiceCount > 0
                    ) {
                        Text(
                            text = "Root cause · $relatedServiceCount related service alert${if (relatedServiceCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }

                    // ── Plugin output: 2 lines collapsed, all when expanded ───
                    if (problem.pluginOutput.isNotBlank()) {
                        val showFullOutput = isExpanded || alwaysShowFullOutput
                        Text(
                            text = problem.pluginOutput,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isHidden)
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullOutput) Int.MAX_VALUE else 2,
                            overflow = if (showFullOutput) TextOverflow.Clip else TextOverflow.Ellipsis,
                        )
                    }
                }

                // ── Right: 3-dot overflow only ────────────────────────────────
                ClassicOverflowMenu(
                    problem = problem,
                    isAcknowledged = isAcknowledged,
                    onOpenDetail = onOpenDetail,
                    onCopyOutput = onCopyOutput,
                    onShare = onShare,
                    onOpenInNagios = onOpenInNagios,
                    onScheduleDowntime = onScheduleDowntime,
                    onAckAllServicesOnHost = onAckAllServicesOnHost,
                    onRecheckAllServicesOnHost = onRecheckAllServicesOnHost,
                    onUnack = onUnack,
                    onAck = onAck,
                    onRecheck = onRecheck,
                )
            }

            // ── Expanded inline details ───────────────────────────────────────
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))

                    if (isRecheckPending) {
                        Text(
                            "⏳ Recheck pending — waiting for fresh result",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    ClassicMetadataSection(problem, instanceName)

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onAck,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ACK", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onRecheck,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Recheck", style = MaterialTheme.typography.labelMedium)
                        }
                        if (onScheduleDowntime != null) {
                            OutlinedButton(
                                onClick = onScheduleDowntime,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text("Downtime…", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (onOpenDetail != null) {
                            TextButton(
                                onClick = onOpenDetail,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "Details →",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp,
        )
    }
    } // end CommandSwipeContainer
}

// ── Compact inline state tag pill ────────────────────────────────────────────

@Composable
private fun ClassicStateTag(text: String) {
    val (bgColor, textColor) = when {
        text == "ACK"        -> Color(0xFF388E3C) to Color.White
        text == "ACK…"       -> Color(0xAA388E3C) to Color.White
        text == "NEW"        -> Color(0xFF1565C0) to Color.White
        text == "DT" || text == "HOST DT" -> Color(0xFFE3F2FD) to Color(0xFF1565C0)
        text == "SOFT"       -> Color(0xFFECEFF1) to Color(0xFF455A64)
        text == "FLAP"       -> Color(0xFFEDE7F6) to Color(0xFF6A1B9A)
        text == "NOTIF OFF" || text == "CHECKS OFF" -> Color(0xFFF5F5F5) to Color(0xFF616161)
        text == "RECHECK" || text == "T2+" -> Color(0xFFE3F2FD) to Color(0xFF1565C0)
        text.startsWith("HIDDEN") -> Color(0xFFEFEBE9) to Color(0xFF5D4037)
        else                 -> Color(0xFFF5F5F5) to Color(0xFF616161)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private const val NEW_STATE_THRESHOLD_MS = 15 * 60 * 1_000L

// ── Inline check metadata ─────────────────────────────────────────────────────

@Composable
private fun ClassicMetadataSection(problem: NagiosProblem, instanceName: String) {
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (instanceName.isNotEmpty()) ClassicDetailRow("Instance", instanceName)

        problem.lastCheck?.let { ts ->
            ClassicDetailRow("Last check", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }
        problem.nextCheck?.let { ts ->
            val txt = if (ts > now) checkIn(ts - now) else checkOverdue(now - ts)
            ClassicDetailRow("Next check", txt)
        }
        problem.lastStateChange?.let { ts ->
            ClassicDetailRow("State for", checkDuration(now - ts))
        }
        problem.lastHardStateChange?.let { ts ->
            ClassicDetailRow("Hard state for", "${checkTime(ts)}  (${checkDuration(now - ts)})")
        }
        val attempt = problem.currentAttempt
        val maxAtt  = problem.maxAttempts
        if (attempt != null) {
            val stateLabel = if (problem.isSoftState) "SOFT" else "HARD"
            ClassicDetailRow(
                "Attempt",
                if (maxAtt != null) "$attempt/$maxAtt  $stateLabel" else "$attempt  $stateLabel",
            )
        }

        val hasFlags = problem.acknowledged || problem.scheduledDowntimeDepth > 0 ||
            !problem.notificationsEnabled || !problem.checksEnabled || problem.isFlapping
        if (hasFlags) {
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(2.dp))
            if (problem.acknowledged) ClassicDetailRow("Acknowledged", "yes")
            if (problem.scheduledDowntimeDepth > 0) ClassicDetailRow("In downtime", "yes")
            if (!problem.notificationsEnabled) ClassicDetailRow("Notifications", "disabled")
            if (!problem.checksEnabled) ClassicDetailRow("Active checks", "disabled")
            if (problem.isFlapping) ClassicDetailRow("Flapping", "yes")
        }

        if (problem is NagiosProblem.ServiceProblem) {
            val hostLabel = when (problem.hostStatus) {
                NagiosStatus.HOST_DOWN        -> "DOWN"
                NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
                NagiosStatus.HOST_UP          -> "UP"
                else                          -> null
            }
            val hasHostInfo = hostLabel != null || problem.hostAcknowledged ||
                problem.hostScheduledDowntimeDepth > 0
            if (hasHostInfo) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(2.dp))
                if (hostLabel != null) ClassicDetailRow("Host state", hostLabel)
                if (problem.hostAcknowledged) ClassicDetailRow("Host ack", "yes")
                if (problem.hostScheduledDowntimeDepth > 0) ClassicDetailRow("Host downtime", "yes")
            }
        }
    }
}

@Composable
private fun ClassicDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
    }
}

// ── Accent color by severity ──────────────────────────────────────────────────

private fun rowAccentColor(problem: NagiosProblem, dark: Boolean): Color = when {
    problem is NagiosProblem.HostProblem && problem.status == NagiosStatus.HOST_DOWN ->
        Color(0xFFC62828)
    problem is NagiosProblem.HostProblem && problem.status == NagiosStatus.HOST_UNREACHABLE ->
        if (dark) Color(0xFFFFF176) else Color(0xFFF57F17)
    problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_CRITICAL ->
        if (dark) Color(0xFFEF5350) else Color(0xFFB71C1C)
    problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_WARNING ->
        if (dark) Color(0xFFFFD54F) else Color(0xFF856404)
    problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_UNKNOWN ->
        if (dark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)
    else -> Color(0xFF616161)
}

// ── Overflow menu ─────────────────────────────────────────────────────────────

@Composable
private fun ClassicOverflowMenu(
    problem: NagiosProblem,
    isAcknowledged: Boolean,
    onOpenDetail: (() -> Unit)?,
    onCopyOutput: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onOpenInNagios: (() -> Unit)?,
    onScheduleDowntime: (() -> Unit)?,
    onAckAllServicesOnHost: (() -> Unit)?,
    onRecheckAllServicesOnHost: (() -> Unit)?,
    onUnack: (() -> Unit)?,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More actions",
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            onOpenDetail?.let {
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            DropdownMenuItem(
                text = { Text("Acknowledge") },
                onClick = { menuExpanded = false; onAck() },
            )
            DropdownMenuItem(
                text = { Text("Recheck") },
                onClick = { menuExpanded = false; onRecheck() },
            )
            onAckAllServicesOnHost?.let {
                DropdownMenuItem(
                    text = { Text("ACK all services on host…") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            onRecheckAllServicesOnHost?.let {
                DropdownMenuItem(
                    text = { Text("Recheck all services on host…") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            onUnack?.let {
                DropdownMenuItem(
                    text = { Text("Remove ACK") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            onCopyOutput?.let {
                DropdownMenuItem(
                    text = { Text("Copy output") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            onOpenInNagios?.let {
                DropdownMenuItem(
                    text = { Text("Open in Nagios") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            onShare?.let {
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { menuExpanded = false; it() },
                )
            }
            HorizontalDivider()
            if (onScheduleDowntime != null) {
                DropdownMenuItem(
                    text = { Text("Schedule downtime…") },
                    onClick = { menuExpanded = false; onScheduleDowntime() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Schedule downtime…",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}
