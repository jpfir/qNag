package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.validateRegex

/**
 * All filter controls.  Every change is emitted immediately via [onUpdate].
 * Invalid regex is saved as-is (so the user doesn't lose their work);
 * [applyFilters] silently ignores invalid patterns at runtime.
 */
@Composable
fun FilterSettingsSection(
    filters: FilterSettings,
    onUpdate: (FilterSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

        // ── Status filters ─────────────────────────────────────────────────
        FilterSubheader("Status")

        FilterRow("Hide DOWN hosts", filters.hideDownHosts) {
            onUpdate(filters.copy(hideDownHosts = it))
        }
        FilterRow("Hide UNREACHABLE hosts", filters.hideUnreachableHosts) {
            onUpdate(filters.copy(hideUnreachableHosts = it))
        }
        FilterRow("Hide flapping hosts", filters.hideFlappingHosts) {
            onUpdate(filters.copy(hideFlappingHosts = it))
        }
        FilterRow("Hide CRITICAL services", filters.hideCriticalServices) {
            onUpdate(filters.copy(hideCriticalServices = it))
        }
        FilterRow("Hide WARNING services", filters.hideWarningServices) {
            onUpdate(filters.copy(hideWarningServices = it))
        }
        FilterRow("Hide UNKNOWN services", filters.hideUnknownServices) {
            onUpdate(filters.copy(hideUnknownServices = it))
        }
        FilterRow("Hide flapping services", filters.hideFlappingServices) {
            onUpdate(filters.copy(hideFlappingServices = it))
        }

        Spacer(Modifier.height(4.dp))

        // ── State / check filters ──────────────────────────────────────────
        FilterSubheader("State & checks")

        FilterRow("Hide acknowledged hosts & services", filters.hideAcknowledgedHostsAndServices) {
            onUpdate(filters.copy(hideAcknowledgedHostsAndServices = it))
        }
        FilterRow("Hide hosts & services with disabled notifications", filters.hideHostsAndServicesWithDisabledNotifications) {
            onUpdate(filters.copy(hideHostsAndServicesWithDisabledNotifications = it))
        }
        FilterRow("Hide hosts & services with disabled checks", filters.hideHostsAndServicesWithDisabledChecks) {
            onUpdate(filters.copy(hideHostsAndServicesWithDisabledChecks = it))
        }
        FilterRow("Hide hosts & services in scheduled downtime", filters.hideHostsAndServicesDownForDowntime) {
            onUpdate(filters.copy(hideHostsAndServicesDownForDowntime = it))
        }
        FilterRow("Hide hosts in soft state", filters.hideHostsInSoftState) {
            onUpdate(filters.copy(hideHostsInSoftState = it))
        }
        FilterRow("Hide services in soft state", filters.hideServicesInSoftState) {
            onUpdate(filters.copy(hideServicesInSoftState = it))
        }

        Spacer(Modifier.height(4.dp))

        // ── Host–service relationship filters ─────────────────────────────
        FilterSubheader("Services on problem hosts")

        FilterRow("Hide services on acknowledged hosts", filters.hideServicesOnAcknowledgedHosts) {
            onUpdate(filters.copy(hideServicesOnAcknowledgedHosts = it))
        }
        FilterRow("Hide services on DOWN hosts", filters.hideServicesOnDownHosts) {
            onUpdate(filters.copy(hideServicesOnDownHosts = it))
        }
        FilterRow("Hide services on hosts in downtime", filters.hideServicesOnHostsInDowntime) {
            onUpdate(filters.copy(hideServicesOnHostsInDowntime = it))
        }
        FilterRow("Hide services on UNREACHABLE hosts", filters.hideServicesOnUnreachableHosts) {
            onUpdate(filters.copy(hideServicesOnUnreachableHosts = it))
        }

        Spacer(Modifier.height(4.dp))

        // ── Regex filters ──────────────────────────────────────────────────
        FilterSubheader("Regex filters")
        Text(
            "When reverse is OFF: hide matching problems.  " +
                    "When reverse is ON: hide non-matching problems.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        RegexFilterGroup(
            label = "Host name",
            enabled = filters.hostRegexEnabled,
            pattern = filters.hostRegex,
            reverse = filters.hostRegexReverse,
            onEnabledChange = { onUpdate(filters.copy(hostRegexEnabled = it)) },
            onPatternChange = { onUpdate(filters.copy(hostRegex = it)) },
            onReverseChange = { onUpdate(filters.copy(hostRegexReverse = it)) },
        )

        RegexFilterGroup(
            label = "Service name",
            enabled = filters.serviceRegexEnabled,
            pattern = filters.serviceRegex,
            reverse = filters.serviceRegexReverse,
            onEnabledChange = { onUpdate(filters.copy(serviceRegexEnabled = it)) },
            onPatternChange = { onUpdate(filters.copy(serviceRegex = it)) },
            onReverseChange = { onUpdate(filters.copy(serviceRegexReverse = it)) },
        )

        RegexFilterGroup(
            label = "Status info / plugin output",
            enabled = filters.statusInfoRegexEnabled,
            pattern = filters.statusInfoRegex,
            reverse = filters.statusInfoRegexReverse,
            onEnabledChange = { onUpdate(filters.copy(statusInfoRegexEnabled = it)) },
            onPatternChange = { onUpdate(filters.copy(statusInfoRegex = it)) },
            onReverseChange = { onUpdate(filters.copy(statusInfoRegexReverse = it)) },
        )

        Spacer(Modifier.height(8.dp))

        // ── Reset ──────────────────────────────────────────────────────────
        OutlinedButton(
            onClick = { onUpdate(FilterSettings()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset all filters")
        }
    }
}

@Composable
private fun FilterSubheader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FilterRow(
    label: String,
    checked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun RegexFilterGroup(
    label: String,
    enabled: Boolean,
    pattern: String,
    reverse: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPatternChange: (String) -> Unit,
    onReverseChange: (Boolean) -> Unit,
) {
    val regexError = if (enabled && pattern.isNotBlank()) validateRegex(pattern) else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = onPatternChange,
                        label = { Text("Regex pattern") },
                        singleLine = true,
                        isError = regexError != null,
                        supportingText = regexError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Reverse (invert match)", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = reverse, onCheckedChange = onReverseChange)
                    }
                }
            }
        }
    }
}
