package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.CommandActivityTracker
import com.exogroup.qnag.data.CommandJob
import com.exogroup.qnag.data.CommandJobStatus
import com.exogroup.qnag.data.CommandTargetResult
import com.exogroup.qnag.data.CommandTargetStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandActivityScreen(onBack: () -> Unit) {
    val jobs by CommandActivityTracker.jobs.collectAsState()
    val hasCompleted = jobs.any { it.status != CommandJobStatus.RUNNING }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Command Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (hasCompleted) {
                        TextButton(onClick = { CommandActivityTracker.clearCompleted() }) {
                            Text("Clear done")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No command activity yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(jobs, key = { it.id }) { job ->
                    CommandJobCard(job)
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

// ── Job card ──────────────────────────────────────────────────────────────────

@Composable
private fun CommandJobCard(job: CommandJob) {
    var expanded by rememberSaveable(job.id) {
        mutableStateOf(job.status == CommandJobStatus.RUNNING)
    }

    val succeeded = job.targets.count { it.status == CommandTargetStatus.SUCCEEDED }
    val failed    = job.targets.count { it.status == CommandTargetStatus.FAILED }
    val running   = job.targets.count { it.status == CommandTargetStatus.RUNNING }
    val total     = job.targets.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    job.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                JobStatusChip(job.status)
            }

            Spacer(Modifier.height(4.dp))

            // ── Times ─────────────────────────────────────────────────────────
            Text(
                buildTimeLabel(job),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Summary ───────────────────────────────────────────────────────
            if (total > 0) {
                Spacer(Modifier.height(2.dp))
                val summaryParts = buildList {
                    add("$total target${if (total != 1) "s" else ""}")
                    if (job.status != CommandJobStatus.RUNNING) {
                        if (succeeded > 0) add("$succeeded succeeded")
                        if (failed > 0)    add("$failed failed")
                    } else {
                        val done = succeeded + failed
                        if (done > 0) add("$done/$total completed")
                        if (running > 0) add("$running running")
                    }
                }
                Text(
                    summaryParts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Expand toggle ─────────────────────────────────────────────────
            if (total > 0) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (expanded) "Hide targets" else "Show $total target${if (total != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
                        job.targets.forEach { target ->
                            TargetRow(target)
                        }
                    }
                }
            }
        }
    }
}

// ── Status chip ───────────────────────────────────────────────────────────────

@Composable
private fun JobStatusChip(status: CommandJobStatus) {
    val (label, colors) = when (status) {
        CommandJobStatus.RUNNING        -> "Running" to AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        )
        CommandJobStatus.SUCCEEDED      -> "Succeeded" to AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
        CommandJobStatus.PARTIAL_FAILED -> "Partial" to AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        )
        CommandJobStatus.FAILED         -> "Failed" to AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        )
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = colors,
        modifier = Modifier.height(24.dp),
    )
}

// ── Target row ────────────────────────────────────────────────────────────────

@Composable
private fun TargetRow(target: CommandTargetResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TargetStatusIcon(target.status)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val label = buildString {
                if (target.serviceName != null) {
                    append(target.serviceName)
                    append(" on ")
                    append(target.hostName)
                } else {
                    append(target.hostName)
                }
            }
            Text(label, style = MaterialTheme.typography.bodySmall)
            if (target.instanceName.isNotEmpty()) {
                Text(
                    target.instanceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (target.message != null && target.status == CommandTargetStatus.FAILED) {
                Text(
                    target.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TargetStatusIcon(status: CommandTargetStatus) {
    val (icon, tint) = when (status) {
        CommandTargetStatus.SUCCEEDED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        CommandTargetStatus.FAILED    -> Icons.Default.Close to MaterialTheme.colorScheme.error
        CommandTargetStatus.RUNNING   -> Icons.Default.Refresh to MaterialTheme.colorScheme.secondary
        CommandTargetStatus.SKIPPED   -> Icons.Default.Warning to MaterialTheme.colorScheme.onSurfaceVariant
        CommandTargetStatus.PENDING   -> Icons.Default.Refresh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = status.name, tint = tint, modifier = Modifier.size(16.dp))
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun buildTimeLabel(job: CommandJob): String {
    val startStr = timeFormat.format(Date(job.startedAt))
    return when {
        job.finishedAt != null -> {
            val finishStr = timeFormat.format(Date(job.finishedAt))
            val durationSec = (job.finishedAt - job.startedAt) / 1000L
            "Started $startStr · Finished $finishStr (${durationSec}s)"
        }
        else -> {
            val elapsedSec = (System.currentTimeMillis() - job.startedAt) / 1000L
            "Started $startStr · Running ${elapsedSec}s"
        }
    }
}
