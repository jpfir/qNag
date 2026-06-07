package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.DowntimeScope
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem

private data class DurationPreset(val label: String, val ms: Long)

private val DURATION_PRESETS = listOf(
    DurationPreset("30m",  30 * 60 * 1_000L),
    DurationPreset("1h",   60 * 60 * 1_000L),
    DurationPreset("2h",  120 * 60 * 1_000L),
    DurationPreset("4h",  240 * 60 * 1_000L),
    DurationPreset("8h",  480 * 60 * 1_000L),
)
private const val CUSTOM_IDX = 5  // index after the 5 presets

/** Dialog for scheduling fixed downtime on a single host or service problem. */
@Composable
fun DowntimeDialog(
    problem: NagiosProblem,
    instance: NagiosInstance?,
    commandSettings: CommandSettings,
    onDismiss: () -> Unit,
    onSchedule: (scope: DowntimeScope, durationMs: Long, comment: String) -> Unit,
) {
    val isService = problem is NagiosProblem.ServiceProblem

    var selectedPresetIdx by remember { mutableIntStateOf(1) }   // default 1h
    var customHours   by remember { mutableStateOf("1") }
    var customMinutes by remember { mutableStateOf("0") }
    val isCustom = selectedPresetIdx == CUSTOM_IDX

    val availableScopes: List<DowntimeScope> = if (isService) {
        listOf(DowntimeScope.SERVICE_ONLY, DowntimeScope.HOST_ONLY, DowntimeScope.HOST_AND_SERVICES)
    } else {
        listOf(DowntimeScope.HOST_ONLY, DowntimeScope.HOST_AND_SERVICES)
    }
    var selectedScope by remember {
        mutableStateOf(if (isService) DowntimeScope.SERVICE_ONLY else DowntimeScope.HOST_AND_SERVICES)
    }

    var comment by remember { mutableStateOf("Scheduled from qNag") }

    val durationMs: Long = if (isCustom) {
        val h = customHours.toLongOrNull() ?: 0L
        val m = customMinutes.toLongOrNull() ?: 0L
        (h * 60 + m) * 60_000L
    } else {
        DURATION_PRESETS[selectedPresetIdx].ms
    }
    val isValid = durationMs > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule downtime") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Target info
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (instance != null) {
                        Text(
                            "Instance: ${instance.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Host: ${problem.hostName}", style = MaterialTheme.typography.bodySmall)
                    if (problem is NagiosProblem.ServiceProblem) {
                        Text("Service: ${problem.serviceName}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider()

                // Duration presets
                Text("Duration", style = MaterialTheme.typography.labelMedium)
                Column(modifier = Modifier.selectableGroup()) {
                    DURATION_PRESETS.forEachIndexed { idx, preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = selectedPresetIdx == idx,
                                onClick = { selectedPresetIdx = idx },
                                role = Role.RadioButton,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedPresetIdx == idx,
                                onClick = { selectedPresetIdx = idx },
                            )
                            Text(preset.label, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = isCustom,
                            onClick = { selectedPresetIdx = CUSTOM_IDX },
                            role = Role.RadioButton,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isCustom,
                            onClick = { selectedPresetIdx = CUSTOM_IDX },
                        )
                        Text("Custom", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                if (isCustom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customHours,
                            onValueChange = { customHours = it.filter { c -> c.isDigit() } },
                            label = { Text("Hours") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!isValid) {
                        Text(
                            "Duration must be greater than 0.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                HorizontalDivider()

                // Scope selector
                Text("Scope", style = MaterialTheme.typography.labelMedium)
                Column(modifier = Modifier.selectableGroup()) {
                    availableScopes.forEach { scope ->
                        val label = when (scope) {
                            DowntimeScope.SERVICE_ONLY -> "Service only"
                            DowntimeScope.HOST_ONLY -> "Host only"
                            DowntimeScope.HOST_AND_SERVICES -> "Host and all services"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = selectedScope == scope,
                                onClick = { selectedScope = scope },
                                role = Role.RadioButton,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedScope == scope,
                                onClick = { selectedScope = scope },
                            )
                            Text(label, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                if (selectedScope == DowntimeScope.HOST_AND_SERVICES) {
                    Text(
                        "⚠ This schedules downtime for the host AND all its services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // Comment field
                Text("Comment", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSchedule(selectedScope, durationMs, comment) },
                enabled = isValid,
            ) {
                val label = when (selectedScope) {
                    DowntimeScope.HOST_AND_SERVICES -> "Schedule (host + services)"
                    else -> "Schedule downtime"
                }
                Text(label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
