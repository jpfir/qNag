package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NagiosDateFormat

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
            "When enabled, a foreground service runs while the app is active, " +
                    "providing more reliable polling. Disabling stops the service immediately. " +
                    "Android may still kill the service under extreme memory pressure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Spacer(Modifier.height(4.dp))
        CmdSubheader("Nagios date format (for recheck start_time)")

        NagiosDateFormatPicker(
            current = settings.resolvedDateFormat,
            onSelect = { onUpdate(settings.copy(nagiosDateFormat = it)) },
        )
        Text(
            "Match the date_format setting in nagios.cfg. Default is US. " +
                    "If recheck commands are silently ignored, try changing this.",
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
