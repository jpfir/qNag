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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProblemCard(
    problem: NagiosProblem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    // isAcknowledged: server ack OR local overlay; isPendingAck: local overlay only (not yet confirmed)
    isAcknowledged: Boolean = false,
    isPendingAck: Boolean = false,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onAck: () -> Unit,
    onRecheck: () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }

    val (rawContainerColor, contentColor) = problemColors(problem)
    // Dim acknowledged cards to de-emphasize them without hiding them
    val containerColor = when {
        isSelected -> rawContainerColor.copy(alpha = 0.5f)
        isAcknowledged || isPendingAck -> rawContainerColor.copy(alpha = 0.65f)
        else -> rawContainerColor
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onAck(); false }
                SwipeToDismissBoxValue.StartToEnd -> { onRecheck(); false }
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
) {
    Column(modifier = Modifier.padding(12.dp)) {
        when (problem) {
            is NagiosProblem.ServiceProblem -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(problem.hostName, fontWeight = FontWeight.Bold)
                        Text(problem.serviceName, fontWeight = FontWeight.SemiBold)
                    }
                    if (isAcknowledged || isPendingAck) {
                        Spacer(Modifier.width(6.dp))
                        AckBadge(pending = isPendingAck && !problem.acknowledged)
                        Spacer(Modifier.width(6.dp))
                    }
                    StatusBadge(serviceStatusLabel(problem.status))
                }
            }
            is NagiosProblem.HostProblem -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[HOST] ${problem.hostName}",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (isAcknowledged || isPendingAck) {
                        Spacer(Modifier.width(6.dp))
                        AckBadge(pending = isPendingAck && !problem.acknowledged)
                        Spacer(Modifier.width(6.dp))
                    }
                    StatusBadge(hostStatusLabel(problem.status))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = problem.pluginOutput,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }
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
