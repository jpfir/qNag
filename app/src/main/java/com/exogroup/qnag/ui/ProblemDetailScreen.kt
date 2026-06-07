package com.exogroup.qnag.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.viewmodel.CommandState
import com.exogroup.qnag.viewmodel.NagiosViewModel
import java.net.URLEncoder

/** Full-screen detail view for a single host or service problem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemDetailScreen(
    problem: NagiosProblem,
    instance: NagiosInstance?,
    commandSettings: CommandSettings,
    onBack: () -> Unit,
    nagiosViewModel: NagiosViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(nagiosViewModel.commandState) {
        when (val cs = nagiosViewModel.commandState) {
            is CommandState.Success -> {
                snackbarHostState.showSnackbar(cs.message, duration = SnackbarDuration.Short)
                nagiosViewModel.clearCommandState()
            }
            is CommandState.Error -> {
                snackbarHostState.showSnackbar("Error: ${cs.message}", duration = SnackbarDuration.Long)
                nagiosViewModel.clearCommandState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val title = when (problem) {
                            is NagiosProblem.ServiceProblem -> "${problem.hostName} / ${problem.serviceName}"
                            is NagiosProblem.HostProblem    -> "[HOST] ${problem.hostName}"
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (instance != null) {
                            Text(
                                instance.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Open in Nagios web UI (Goal 6)
                    if (instance != null) {
                        IconButton(onClick = {
                            val url = nagiosExtInfoUrl(instance.url, problem)
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in Nagios")
                        }
                    }
                    // Share (Goal 7)
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, buildAlertSummary(problem))
                        }
                        context.startActivity(Intent.createChooser(intent, "Share alert"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // ACK and Recheck actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (instance != null) {
                            nagiosViewModel.acknowledgeProblems(instance, listOf(problem), commandSettings)
                        }
                    },
                    enabled = instance != null && !problem.acknowledged,
                    modifier = Modifier.weight(1f),
                ) { Text("Acknowledge") }
                Button(
                    onClick = {
                        if (instance != null) {
                            nagiosViewModel.recheckProblems(instance, listOf(problem), commandSettings)
                        }
                    },
                    enabled = instance != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Recheck") }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Status + badge row
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusLabel = when (problem) {
                        is NagiosProblem.ServiceProblem -> serviceStatusLabel(problem.status)
                        is NagiosProblem.HostProblem    -> hostStatusLabel(problem.status)
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (problem.acknowledged) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF388E3C),
                            contentColor = Color.White,
                        ) {
                            Text(
                                "ACK",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (problem.scheduledDowntimeDepth > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE3F2FD),
                            contentColor = Color(0xFF1565C0),
                        ) {
                            Text(
                                "IN DOWNTIME",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Plugin output (full, selectable)
            item {
                DetailSectionHeader("Output")
                SelectionContainer {
                    Text(
                        problem.pluginOutput,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Copy output button (Goal 7)
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(problem.pluginOutput))
                    },
                    modifier = Modifier.padding(start = 0.dp),
                ) { Text("Copy output", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }

            // Full metadata
            item {
                DetailSectionHeader("Details")
                DetailMetadataFull(problem)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── Metadata section ──────────────────────────────────────────────────────────

@Composable
private fun DetailMetadataFull(problem: NagiosProblem) {
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Check timing
        problem.lastCheck?.let { ts ->
            DetailRow("Last check", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }
        problem.nextCheck?.let { ts ->
            val inFuture = ts > now
            DetailRow("Next check", if (inFuture) "in ${checkAge(ts - now)}" else checkTime(ts))
        }
        problem.lastStateChange?.let { ts ->
            DetailRow("State for", checkDuration(now - ts))
        }
        problem.lastHardStateChange?.let { ts ->
            DetailRow("Hard state for", "${checkTime(ts)}  (${checkDuration(now - ts)})")
        }
        val attempt = problem.currentAttempt
        val maxAtt  = problem.maxAttempts
        if (attempt != null) {
            val stateLabel = if (problem.isSoftState) "SOFT" else "HARD"
            DetailRow("Attempt", if (maxAtt != null) "$attempt/$maxAtt  $stateLabel" else "$attempt  $stateLabel")
        }
        problem.checkType?.let { DetailRow("Check type", it) }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))

        // State flags — always shown for clarity
        DetailRow("Acknowledged", if (problem.acknowledged) "yes" else "no")
        DetailRow("In downtime", if (problem.scheduledDowntimeDepth > 0) "yes (depth ${problem.scheduledDowntimeDepth})" else "no")
        DetailRow("Notifications", if (problem.notificationsEnabled) "enabled" else "disabled")
        DetailRow("Checks", if (problem.checksEnabled) "enabled" else "disabled")
        DetailRow("Flapping", if (problem.isFlapping) "yes" else "no")

        // Host state (service problems only)
        if (problem is NagiosProblem.ServiceProblem) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            val hostStateLabel = when (problem.hostStatus) {
                NagiosStatus.HOST_DOWN        -> "DOWN"
                NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
                NagiosStatus.HOST_UP          -> "UP"
                null                          -> "unknown"
                else                          -> "status ${problem.hostStatus}"
            }
            DetailRow("Host state", hostStateLabel)
            DetailRow("Host acknowledged", if (problem.hostAcknowledged) "yes" else "no")
            DetailRow("Host in downtime", if (problem.hostScheduledDowntimeDepth > 0) "yes" else "no")
        }
    }
}

@Composable
private fun DetailSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Utilities (Goals 6, 7) ────────────────────────────────────────────────────

/** Generate a Nagios extinfo.cgi URL for host or service details. */
fun nagiosExtInfoUrl(baseUrl: String, problem: NagiosProblem): String {
    val base = baseUrl.trimEnd('/')
    return when (problem) {
        is NagiosProblem.ServiceProblem -> {
            val host = URLEncoder.encode(problem.hostName, "UTF-8")
            val svc  = URLEncoder.encode(problem.serviceName, "UTF-8")
            "$base/nagios/cgi-bin/extinfo.cgi?type=2&host=$host&service=$svc"
        }
        is NagiosProblem.HostProblem -> {
            val host = URLEncoder.encode(problem.hostName, "UTF-8")
            "$base/nagios/cgi-bin/extinfo.cgi?type=1&host=$host"
        }
    }
}

/** Build a plain-text alert summary for sharing / copying (no credentials). */
fun buildAlertSummary(problem: NagiosProblem): String = buildString {
    val statusLabel = when (problem) {
        is NagiosProblem.ServiceProblem -> serviceStatusLabel(problem.status)
        is NagiosProblem.HostProblem    -> hostStatusLabel(problem.status)
    }
    appendLine("$statusLabel: ${problem.hostName}")
    if (problem is NagiosProblem.ServiceProblem) appendLine("Service: ${problem.serviceName}")
    if (problem.instanceName.isNotEmpty()) appendLine("Instance: ${problem.instanceName}")
    problem.lastStateChange?.let {
        appendLine("State for: ${checkDuration(System.currentTimeMillis() - it)}")
    }
    problem.lastCheck?.let {
        appendLine("Last check: ${checkTime(it)} (${checkAge(System.currentTimeMillis() - it)})")
    }
    append("Output: ${problem.pluginOutput}")
}
