package com.exogroup.qnag.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    // One-shot swipe lock: prevents the same gesture from firing onAck/onRecheck multiple times.
    // confirmValueChange can be called repeatedly during a single gesture, so we gate on this flag
    // and reset it after a short delay (long enough for the gesture to fully settle).
    var swipeLocked by remember { mutableStateOf(false) }
    LaunchedEffect(swipeLocked) {
        if (swipeLocked) {
            delay(1_000L)
            swipeLocked = false
        }
    }

    val (rawContainerColor, contentColor) = problemColors(problem)
    val containerColor = when {
        isSelected -> rawContainerColor.copy(alpha = 0.5f)
        isAcknowledged || isPendingAck -> rawContainerColor.copy(alpha = 0.65f)
        else -> rawContainerColor
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (swipeLocked) return@rememberSwipeToDismissBoxState false
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { swipeLocked = true; onAck(); false }
                SwipeToDismissBoxValue.StartToEnd -> { swipeLocked = true; onRecheck(); false }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val bgColor = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF1976D2)   // blue = recheck
                SwipeToDismissBoxValue.EndToStart -> Color(0xFF388E3C)   // green = ack
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .background(bgColor, shape = CardDefaults.shape),
                contentAlignment = when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                }
            ) {
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Icon(Icons.Default.Refresh, "Recheck", tint = Color.White, modifier = Modifier.padding(start = 24.dp))
                    SwipeToDismissBoxValue.EndToStart ->
                        Icon(Icons.Default.Check, "Acknowledge", tint = Color.White, modifier = Modifier.padding(end = 24.dp))
                    else -> {}
                }
            }
        },
        content = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
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
                )
            }
        }
    )
}

@Composable
private fun ProblemCardContent(
    problem: NagiosProblem,
    isExpanded: Boolean,
    isAcknowledged: Boolean,
    isPendingAck: Boolean,
    instanceName: String = "",
    isRecheckPending: Boolean = false,
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
        }

        // ── Severity / ACK chips — status and ACK only, no instance clutter ──
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (problem) {
                is NagiosProblem.ServiceProblem -> StatusBadge(serviceStatusLabel(problem.status))
                is NagiosProblem.HostProblem    -> StatusBadge(hostStatusLabel(problem.status))
            }
            if (isAcknowledged || isPendingAck) {
                AckBadge(pending = isPendingAck && !problem.acknowledged)
            }
        }

        // ── Check timing line ─────────────────────────────────────────────────
        val lastCheckMs = problem.lastCheck
        Spacer(Modifier.height(3.dp))
        if (isRecheckPending) {
            // Pending: show the "waiting" notice plus the previous check time so the user
            // knows the displayed plugin output is still from the old check (Goal 1).
            Text(
                "⏳ Recheck pending — waiting for fresh result",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (lastCheckMs != null) {
                Text(
                    "Last check: ${checkTime(lastCheckMs)} · ${checkAge(System.currentTimeMillis() - lastCheckMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (lastCheckMs != null) {
            val ageMs = System.currentTimeMillis() - lastCheckMs
            val isStale = isCheckStale(ageMs, problem.nextCheck)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Checked ${checkAge(ageMs)} · ${checkTime(lastCheckMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isStale) StaleBadge()
            }
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
                problem.currentAttempt != null || problem.checkType != null
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
        problem.lastCheck?.let { ts ->
            CheckDetailRow("Last check", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }
        problem.nextCheck?.let { ts ->
            val inFuture = ts > now
            CheckDetailRow("Next check", if (inFuture) "in ${checkAge(ts - now)}" else checkTime(ts))
        }
        problem.lastStateChange?.let { ts ->
            CheckDetailRow("State since", checkDuration(now - ts))
        }
        // Last hard state change — Goal 2
        problem.lastHardStateChange?.let { ts ->
            CheckDetailRow("Hard state since", "${checkTime(ts)}  (${checkDuration(now - ts)})")
        }
        val attempt = problem.currentAttempt
        val maxAtt  = problem.maxAttempts
        if (attempt != null) {
            val stateLabel = if (problem.isSoftState) "SOFT" else "HARD"
            CheckDetailRow("Attempt", if (maxAtt != null) "$attempt/$maxAtt  $stateLabel" else "$attempt  $stateLabel")
        }
        problem.checkType?.let { CheckDetailRow("Check type", it) }
    }
}

@Composable
private fun CheckDetailRow(label: String, value: String) {
    Row {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StaleBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFFF9A825).copy(alpha = 0.18f),
        contentColor = Color(0xFFB36B00),
    ) {
        Text(
            "STALE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
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

// Default stale threshold: 15 minutes without a Nagios re-check.
private const val STALE_CHECK_THRESHOLD_MS = 15 * 60 * 1_000L

/**
 * Returns true when the last check result is considered stale.
 *
 * Currently uses a fixed 15-minute threshold.
 *
 * TODO: use [nextCheckMs] to derive a smarter threshold — if nextCheck is known,
 *   mark stale when ageMs > (expectedInterval * 2), where expectedInterval =
 *   nextCheckMs - lastCheckMs.  This handles cases where Nagios has a very short
 *   check interval (e.g. 30 s) or a very long one (e.g. 24 h).
 */
private fun isCheckStale(ageMs: Long, @Suppress("UNUSED_PARAMETER") nextCheckMs: Long?): Boolean {
    // TODO: factor in nextCheckMs when implementing interval-aware staleness.
    return ageMs > STALE_CHECK_THRESHOLD_MS
}

private fun checkTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

private fun checkAge(ageMs: Long): String {
    val sec = ageMs / 1000
    return when {
        sec < 60   -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        else       -> "${sec / 3600}h ${(sec % 3600) / 60}m ago"
    }
}

private fun checkDuration(durationMs: Long): String {
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
