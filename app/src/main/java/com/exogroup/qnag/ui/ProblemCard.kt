package com.exogroup.qnag.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProblemCard(
    problem: NagiosProblem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    // isAcknowledged: server ack OR local overlay; isPendingAck: local overlay only (not yet confirmed)
    isAcknowledged: Boolean = false,
    isPendingAck: Boolean = false,
    // Non-empty in ALL-instances view — shows a small badge indicating the source instance
    instanceName: String = "",
    // True while a recheck was submitted but Nagios has not yet executed the forced check
    isRecheckPending: Boolean = false,
    // Overflow-menu actions — null = hidden from menu
    // True when Tier 2+ delay is active and this alert has not yet reached the threshold
    isTier2Waiting: Boolean = false,
    // False while the list is scrolling or within 250 ms of scroll stop; also false in selection mode
    swipeAllowed: Boolean = true,
    onOpenDetail: (() -> Unit)? = null,
    onCopyOutput: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onOpenInNagios: (() -> Unit)? = null,
    onScheduleDowntime: (() -> Unit)? = null,
    // Null = not shown; non-null = shown only when same-host services exist on this instance
    onAckAllServicesOnHost: (() -> Unit)? = null,
    onRecheckAllServicesOnHost: (() -> Unit)? = null,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onUnack: (() -> Unit)? = null,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var commandLocked by remember { mutableStateOf(false) }
    var gestureAllowed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val offsetAnim = remember { Animatable(0f) }

    // During drag show the raw unthrottled position; during return animation show the animated value.
    val visualOffset = if (isDragging) dragOffsetPx else offsetAnim.value

    // Reset stale swipe state when the card identity or selection mode changes.
    // Guards against the card remaining offset or locked after a list refresh or mode toggle.
    val problemKey = "${problem.uniqueId}|${problem.instanceId}"
    LaunchedEffect(problemKey, isSelectionMode) {
        if (!isDragging) {
            dragOffsetPx = 0f
            gestureAllowed = false
            commandLocked = false
            offsetAnim.snapTo(0f)
        }
    }

    val (rawContainerColor, contentColor) = problemColors(problem)
    val containerColor = when {
        isSelected -> rawContainerColor.copy(alpha = 0.5f)
        isAcknowledged || isPendingAck -> rawContainerColor.copy(alpha = 0.65f)
        else -> rawContainerColor
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val thresholdPx = widthPx * 0.45f
        val maxSwipePx = widthPx * 0.55f

        val draggableState = rememberDraggableState { delta ->
            if (!gestureAllowed) return@rememberDraggableState
            dragOffsetPx = (dragOffsetPx + delta).coerceIn(-maxSwipePx, maxSwipePx)
        }

        val progress = (abs(visualOffset) / thresholdPx).coerceIn(0f, 1f)
        val backgroundColor = when {
            visualOffset > 0f -> Color(0xFF1976D2).copy(alpha = 0.25f + progress * 0.75f)
            visualOffset < 0f -> Color(0xFF388E3C).copy(alpha = 0.25f + progress * 0.75f)
            else -> Color.Transparent
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // Swipe command background — reveals action colour and icon behind the sliding card
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 4.dp)
                    .background(backgroundColor, shape = CardDefaults.shape),
                contentAlignment = if (visualOffset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when {
                    visualOffset > 0f -> Icon(
                        Icons.Default.Refresh, contentDescription = "Recheck",
                        tint = Color.White,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                    visualOffset < 0f -> Icon(
                        Icons.Default.Check, contentDescription = "Acknowledge",
                        tint = Color.White,
                        modifier = Modifier.padding(end = 24.dp),
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .offset { IntOffset(visualOffset.roundToInt(), 0) }
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Horizontal,
                        enabled = !isSelectionMode,
                        onDragStarted = {
                            gestureAllowed = swipeAllowed && !isSelectionMode && !commandLocked
                            isDragging = gestureAllowed
                        },
                        onDragStopped = {
                            val finalOffset = dragOffsetPx
                            val action = when {
                                gestureAllowed && !commandLocked && abs(finalOffset) >= thresholdPx && finalOffset > 0f -> "recheck"
                                gestureAllowed && !commandLocked && abs(finalOffset) >= thresholdPx && finalOffset < 0f -> "ack"
                                else -> null
                            }
                            val shouldLock = action != null
                            if (shouldLock) commandLocked = true

                            try {
                                // Sync animation to drag position before clearing isDragging — no visual jump.
                                offsetAnim.snapTo(finalOffset)
                                isDragging = false
                                dragOffsetPx = 0f
                                gestureAllowed = false

                                // Fire command immediately and safely; failures surface via Command Activity.
                                if (action == "recheck") runCatching { onRecheck() }
                                else if (action == "ack") runCatching { onAck() }

                                offsetAnim.animateTo(0f, animationSpec = tween(durationMillis = 180))

                                if (shouldLock) {
                                    delay(120L)
                                    commandLocked = false
                                }
                            } finally {
                                // Runs on cancellation (e.g. card leaves composition mid-animation).
                                isDragging = false
                                dragOffsetPx = 0f
                                gestureAllowed = false
                                if (shouldLock) commandLocked = false
                                withContext(NonCancellable) { offsetAnim.snapTo(0f) }
                            }
                        },
                    )
                    .combinedClickable(
                        onClick = {
                            if (isSelectionMode) onToggleSelect()
                            else isExpanded = !isExpanded
                        },
                        onLongClick = onLongPress,
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                ProblemCardContent(
                    problem = problem,
                    isExpanded = isExpanded,
                    isAcknowledged = isAcknowledged,
                    isPendingAck = isPendingAck,
                    instanceName = instanceName,
                    isRecheckPending = isRecheckPending,
                    isTier2Waiting = isTier2Waiting,
                    onUnack = onUnack,
                    onAck = onAck,
                    onRecheck = onRecheck,
                    onOpenDetail = onOpenDetail,
                    onCopyOutput = onCopyOutput,
                    onShare = onShare,
                    onOpenInNagios = onOpenInNagios,
                    onScheduleDowntime = onScheduleDowntime,
                    onAckAllServicesOnHost = onAckAllServicesOnHost,
                    onRecheckAllServicesOnHost = onRecheckAllServicesOnHost,
                )
            }
        }
    }
}

@Composable
private fun ProblemCardContent(
    problem: NagiosProblem,
    isExpanded: Boolean,
    isAcknowledged: Boolean,
    isPendingAck: Boolean,
    instanceName: String = "",
    isRecheckPending: Boolean = false,
    isTier2Waiting: Boolean = false,
    onUnack: (() -> Unit)? = null,
    onAck: () -> Unit = {},
    onRecheck: () -> Unit = {},
    onOpenDetail: (() -> Unit)? = null,
    onCopyOutput: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onOpenInNagios: (() -> Unit)? = null,
    onScheduleDowntime: (() -> Unit)? = null,
    onAckAllServicesOnHost: (() -> Unit)? = null,
    onRecheckAllServicesOnHost: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(12.dp)) {
        // ── Name block — instance chip floats to top-right so names use full width ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (problem) {
                    is NagiosProblem.ServiceProblem -> {
                        Text(
                            problem.hostName,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            problem.serviceName,
                            fontWeight = FontWeight.SemiBold,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                    }
                    is NagiosProblem.HostProblem -> {
                        Text(
                            "[HOST] ${problem.hostName}",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Instance chip anchored to top-right; only shown in ALL mode (instanceName non-empty)
            if (instanceName.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                InstanceBadge(instanceName)
            }
            // Three-dot overflow menu
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        modifier = Modifier.size(18.dp),
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
                            text = { Text("Schedule downtime…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                }
            }
        }

        // ── Status + state/action chips ──────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        // NEW chip: state changed within the last 15 minutes
        val isNew = problem.lastStateChange?.let {
            (System.currentTimeMillis() - it) < NEW_STATE_THRESHOLD_MS
        } ?: false
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (problem) {
                is NagiosProblem.ServiceProblem -> StatusBadge(serviceStatusLabel(problem.status))
                is NagiosProblem.HostProblem    -> StatusBadge(hostStatusLabel(problem.status))
            }
            if (isTier2Waiting) Tier2WaitingBadge()
            if (isNew) NewBadge()
            if (isAcknowledged || isPendingAck) {
                AckBadge(pending = isPendingAck && !problem.acknowledged)
            }
            if (problem.scheduledDowntimeDepth > 0) StateBadge("DT", Color(0xFF1565C0), Color(0xFFE3F2FD))
            if (problem is NagiosProblem.ServiceProblem && problem.hostScheduledDowntimeDepth > 0) {
                StateBadge("HOST DT", Color(0xFF1565C0), Color(0xFFE3F2FD))
            }
            if (!problem.notificationsEnabled) StateBadge("NOTIF OFF", Color(0xFF616161), Color(0xFFF5F5F5))
            if (!problem.checksEnabled) StateBadge("CHECKS OFF", Color(0xFF616161), Color(0xFFF5F5F5))
            if (problem.isFlapping) StateBadge("FLAP", Color(0xFF6A1B9A), Color(0xFFEDE7F6))
            if (problem.isSoftState) StateBadge("SOFT", Color(0xFF455A64), Color(0xFFECEFF1))
        }

        // ── Check timing line ─────────────────────────────────────────────────
        val lastCheckMs = problem.lastCheck
        val now = System.currentTimeMillis()
        val stateForSuffix = problem.lastStateChange?.let { " · Since ${checkDuration(now - it)}" } ?: ""
        Spacer(Modifier.height(3.dp))
        if (isRecheckPending) {
            Text(
                "⏳ Recheck pending — waiting for fresh result",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (lastCheckMs != null) {
                Text(
                    "Last check: ${checkTime(lastCheckMs)} · ${checkAge(now - lastCheckMs)}$stateForSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (lastCheckMs != null) {
            val ageMs = now - lastCheckMs
            // CHECK OVERDUE badge is omitted on collapsed cards — overdue info is shown in the
            // expanded section (Next check row) and on the Details screen.
            Text(
                "Checked ${checkAge(ageMs)} · ${checkTime(lastCheckMs)}$stateForSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (stateForSuffix.isNotEmpty()) {
            Text(
                stateForSuffix.removePrefix(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Plugin output ─────────────────────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = problem.pluginOutput,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }

        // ── Expanded check metadata ───────────────────────────────────────────
        if (isExpanded) {
            val hasMetadata = problem.lastCheck != null || problem.nextCheck != null ||
                problem.lastStateChange != null || problem.lastHardStateChange != null ||
                problem.currentAttempt != null || problem.checkType != null ||
                problem.passiveChecksEnabled != null || problem.freshnessChecksEnabled != null
            if (hasMetadata) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                CheckMetadataSection(problem)
            }
        }
    }
}

@Composable
private fun CheckMetadataSection(problem: NagiosProblem) {
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ── Check timing ──────────────────────────────────────────────────────
        problem.lastCheck?.let { ts ->
            CheckDetailRow("Last check", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }
        problem.nextCheck?.let { ts ->
            // Use distinct wording for future vs overdue
            val nextText = if (ts > now) checkIn(ts - now) else checkOverdue(now - ts)
            CheckDetailRow("Next check", nextText)
        }
        problem.lastStateChange?.let { ts ->
            CheckDetailRow("State for", checkDuration(now - ts))
        }
        problem.lastHardStateChange?.let { ts ->
            CheckDetailRow("Hard state for", "${checkTime(ts)}  (${checkDuration(now - ts)})")
        }
        val attempt = problem.currentAttempt
        val maxAtt  = problem.maxAttempts
        if (attempt != null) {
            val stateLabel = if (problem.isSoftState) "SOFT" else "HARD"
            CheckDetailRow("Attempt", if (maxAtt != null) "$attempt/$maxAtt  $stateLabel" else "$attempt  $stateLabel")
        }
        // check_type = last result type, not the configured mode — label clarifies this
        problem.checkType?.let { CheckDetailRow("Last result", it) }
        problem.passiveChecksEnabled?.let {
            CheckDetailRow("Passive checks", if (it) "enabled" else "disabled")
        }
        problem.freshnessChecksEnabled?.let { f ->
            CheckDetailRow("Freshness checks", if (f) "enabled" else "disabled")
            if (f) problem.freshnessThresholdSeconds?.let {
                CheckDetailRow("Freshness threshold", "${it}s")
            }
        }

        // ── State flags ───────────────────────────────────────────────────────
        val hasStateFlags = problem.acknowledged || problem.scheduledDowntimeDepth > 0 ||
                !problem.notificationsEnabled || !problem.checksEnabled || problem.isFlapping
        if (hasStateFlags) {
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(2.dp))
            if (problem.acknowledged) CheckDetailRow("Acknowledged", "yes")
            if (problem.scheduledDowntimeDepth > 0) CheckDetailRow("In downtime", "yes (depth ${problem.scheduledDowntimeDepth})")
            if (!problem.notificationsEnabled) CheckDetailRow("Notifications", "disabled")
            if (!problem.checksEnabled) CheckDetailRow("Active checks", "disabled")
            if (problem.isFlapping) CheckDetailRow("Flapping", "yes")
        }

        // ── Host state (service problems only) ────────────────────────────────
        if (problem is NagiosProblem.ServiceProblem) {
            val hostStatusLabel = when (problem.hostStatus) {
                NagiosStatus.HOST_DOWN        -> "DOWN"
                NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
                NagiosStatus.HOST_UP          -> "UP"
                else                          -> null
            }
            val hasHostInfo = hostStatusLabel != null || problem.hostAcknowledged ||
                    problem.hostScheduledDowntimeDepth > 0
            if (hasHostInfo) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(2.dp))
                if (hostStatusLabel != null) CheckDetailRow("Host state", hostStatusLabel)
                if (problem.hostAcknowledged) CheckDetailRow("Host ack", "yes")
                if (problem.hostScheduledDowntimeDepth > 0) CheckDetailRow("Host downtime", "yes")
            }
        }
    }
}

@Composable
private fun CheckDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
    }
}

/** Generic compact state/action badge. */
@Composable
private fun StateBadge(label: String, textColor: Color, bgColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor, contentColor = textColor) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Tier 2+ waiting chip — shown when Tier 2+ delay is active and the alert has not reached threshold. */
@Composable
private fun Tier2WaitingBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFFE3F2FD),   // light blue — informational, not alarming
        contentColor = Color(0xFF1565C0),
    ) {
        Text(
            "T2+",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** "NEW" chip — solid blue bg + white text; visually distinct from the green ACK badge. */
@Composable
private fun NewBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1565C0),   // blue 800 — distinct from ACK green, readable on all card colors
        contentColor = Color.White,
    ) {
        Text(
            "NEW",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Small teal badge showing the source instance name — visible in ALL-instances view. */
@Composable
private fun InstanceBadge(name: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** Small green badge indicating an acknowledgement — "ACK" or "ACK…" while pending. */
@Composable
private fun AckBadge(pending: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (pending) Color(0xAA388E3C) else Color(0xFF388E3C),
        contentColor = Color.White,
    ) {
        Text(
            text = if (pending) "ACK…" else "ACK",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusBadge(label: String) {
    Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun problemColors(problem: NagiosProblem): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (problem) {
        is NagiosProblem.ServiceProblem -> serviceColors(problem.status, dark)
        is NagiosProblem.HostProblem -> hostColors(problem.status, dark)
    }
}

private fun serviceColors(status: Int, dark: Boolean): Pair<Color, Color> = when (status) {
    NagiosStatus.SERVICE_CRITICAL ->
        if (dark) Color(0xFF660000) to Color(0xFFEF9A9A)
        else Color(0xFFF8D7DA) to Color(0xFF721C24)
    NagiosStatus.SERVICE_WARNING ->
        if (dark) Color(0xFF664D00) to Color(0xFFFFD54F)
        else Color(0xFFFFF3CD) to Color(0xFF856404)
    NagiosStatus.SERVICE_UNKNOWN ->
        if (dark) Color(0xFF3D2070) to Color(0xFFCE93D8)
        else Color(0xFFEDE7F6) to Color(0xFF4A148C)
    else ->
        if (dark) Color(0xFF333333) to Color(0xFFBDBDBD)
        else Color(0xFFE2E3E5) to Color(0xFF383D41)
}

private fun hostColors(status: Int, dark: Boolean): Pair<Color, Color> = when (status) {
    NagiosStatus.HOST_DOWN ->
        if (dark) Color(0xFF4D0000) to Color(0xFFFF8A80)
        else Color(0xFFFFEBEE) to Color(0xFFB71C1C)
    NagiosStatus.HOST_UNREACHABLE ->
        if (dark) Color(0xFF3D3300) to Color(0xFFFFF176)
        else Color(0xFFFFFDE7) to Color(0xFFF57F17)
    else ->
        if (dark) Color(0xFF333333) to Color(0xFFBDBDBD)
        else Color(0xFFE2E3E5) to Color(0xFF383D41)
}

private const val NEW_STATE_THRESHOLD_MS = 15 * 60 * 1_000L

/** Format a duration in the future — "in 3m", "in 2h 5m". */
internal fun checkIn(durationMs: Long): String {
    val sec = durationMs / 1000
    return when {
        sec < 60   -> "in ${sec}s"
        sec < 3600 -> "in ${sec / 60}m"
        else       -> "in ${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}

/** Format an overdue duration — "overdue by 3m". */
internal fun checkOverdue(ageMs: Long): String {
    val sec = ageMs / 1000
    return when {
        sec < 60   -> "overdue by ${sec}s"
        sec < 3600 -> "overdue by ${sec / 60}m"
        else       -> "overdue by ${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}

internal fun checkTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

internal fun checkAge(ageMs: Long): String {
    val sec = ageMs / 1000
    return when {
        sec < 60   -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m ago"
    }
}

internal fun checkDuration(durationMs: Long): String {
    val sec = durationMs / 1000
    return when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}

fun serviceStatusLabel(status: Int): String = when (status) {
    NagiosStatus.SERVICE_CRITICAL -> "CRITICAL"
    NagiosStatus.SERVICE_WARNING -> "WARNING"
    NagiosStatus.SERVICE_UNKNOWN -> "UNKNOWN"
    else -> "STATUS $status"
}

fun hostStatusLabel(status: Int): String = when (status) {
    NagiosStatus.HOST_DOWN -> "DOWN"
    NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
    else -> "STATUS $status"
}
