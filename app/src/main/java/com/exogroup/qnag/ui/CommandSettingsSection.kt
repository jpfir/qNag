package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NagiosDateFormat

private val FOREGROUND_INTERVALS: List<Pair<Int, String>> = listOf(
    30 to "30 seconds",
    60 to "1 minute",
    120 to "2 minutes",
    300 to "5 minutes",
    600 to "10 minutes",
    900 to "15 minutes",
)

/**
 * ACK defaults, polling behaviour flags, and miscellaneous command settings.
 * All changes are emitted immediately via [onUpdate].
 */
@Composable
fun CommandSettingsSection(
    settings: CommandSettings,
    onUpdate: (CommandSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

        // ── ACK defaults ───────────────────────────────────────────────────
        CmdSubheader("Default ACK settings")

        OutlinedTextField(
            value = settings.defaultAckMessage,
            onValueChange = { onUpdate(settings.copy(defaultAckMessage = it)) },
            label = { Text("Default ACK message") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = settings.ackAuthor,
            onValueChange = { onUpdate(settings.copy(ackAuthor = it)) },
            label = { Text("ACK author (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        CmdSubheader("ACK flags")

        CmdRow("Sticky ACK", settings.ackSticky) { onUpdate(settings.copy(ackSticky = it)) }
        CmdRow("Notify on ACK", settings.ackNotify) { onUpdate(settings.copy(ackNotify = it)) }
        CmdRow("Persistent ACK comment", settings.ackPersistent) { onUpdate(settings.copy(ackPersistent = it)) }
        CmdRow("When ACKing a host, also ACK service problems on that host", settings.ackServicesOnHostAck) {
            onUpdate(settings.copy(ackServicesOnHostAck = it))
        }
        Text(
            "Useful when a host is down and many services are failing because of it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Spacer(Modifier.height(4.dp))
        CmdSubheader("Background polling")

        CmdRow("Notify on fetch/polling failure", settings.notifyOnFetchFailure) {
            onUpdate(settings.copy(notifyOnFetchFailure = it))
        }
        CmdRow("Notify only new problems (suppress repeats)", settings.notifyOnlyNewProblems) {
            onUpdate(settings.copy(notifyOnlyNewProblems = it))
        }
        CmdRow("Show battery optimization hint", settings.showBatteryOptimizationHint) {
            onUpdate(settings.copy(showBatteryOptimizationHint = it))
        }

        Spacer(Modifier.height(4.dp))
        CmdSubheader("Foreground monitoring")

        CmdRow(
            label = "Keep monitoring active (foreground service)",
            checked = settings.keepMonitoringActive,
            onCheckedChange = { onUpdate(settings.copy(keepMonitoringActive = it)) },
        )
        Text(
            "Foreground monitoring is more reliable and can poll more often, but Android may still " +
                    "limit or stop it. On Android 15+, dataSync foreground services have background " +
                    "runtime limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        AnimatedVisibility(visible = settings.keepMonitoringActive) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CmdSubheader("Foreground polling interval")
                ForegroundIntervalPicker(
                    currentSeconds = settings.foregroundPollingIntervalSeconds,
                    onSelect = { onUpdate(settings.copy(foregroundPollingIntervalSeconds = it)) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        CmdSubheader("Nagios date format (for recheck start_time)")

        NagiosDateFormatPicker(
            current = settings.resolvedDateFormat,
            onSelect = { onUpdate(settings.copy(nagiosDateFormat = it)) },
        )
        Text(
            "Match the date_format setting in nagios.cfg. Default is ISO8601 (yyyy-MM-dd HH:mm:ss), " +
                    "which matches qNagstamon and is the most common setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        CmdSubheader("Diagnostics")

        CmdRow(
            label = "Debug command submission (logs safe info only)",
            checked = settings.debugCommandSubmission,
            onCheckedChange = { onUpdate(settings.copy(debugCommandSubmission = it)) },
        )
        Text(
            "When enabled, logs command kind, field names, HTTP status, and a sanitized response " +
                    "snippet to logcat (tag: qNag). Never logs passwords, cookies, or Authorization headers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NagiosDateFormatPicker(
    current: NagiosDateFormat,
    onSelect: (NagiosDateFormat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = "${current.name}  (${current.pattern})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Date format") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NagiosDateFormat.entries.forEach { fmt ->
                DropdownMenuItem(
                    text = { Text("${fmt.name}  —  ${fmt.pattern}") },
                    onClick = { onSelect(fmt); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForegroundIntervalPicker(
    currentSeconds: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = FOREGROUND_INTERVALS.find { (s, _) -> s == currentSeconds }?.second
        ?: "$currentSeconds seconds"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Poll every") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FOREGROUND_INTERVALS.forEach { (seconds, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(seconds); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CmdSubheader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun CmdRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 8.dp))
    }
}
