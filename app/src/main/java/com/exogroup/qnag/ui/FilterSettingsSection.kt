package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.RegexFilterField
import com.exogroup.qnag.data.RegexFilterRule
import com.exogroup.qnag.data.validateRegex
import java.util.UUID

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

        // ── Regex filter rules ─────────────────────────────────────────────
        FilterSubheader("Regex filter rules")
        Text(
            "\"Show matching\" keeps only problems matching the pattern. " +
                    "\"Hide matching\" removes matching problems. " +
                    "Choose a field (Host, Service, Status info) or Any field to match the combined text. " +
                    "Rules of the same mode are OR'd; include is applied before exclude.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        filters.regexRules.forEachIndexed { index, rule ->
            RegexRuleCard(
                rule = rule,
                onUpdate = { updated ->
                    onUpdate(filters.copy(regexRules = filters.regexRules.toMutableList().also { it[index] = updated }))
                },
                onDelete = {
                    onUpdate(filters.copy(regexRules = filters.regexRules.filterIndexed { i, _ -> i != index }))
                },
            )
            Spacer(Modifier.height(4.dp))
        }

        OutlinedButton(
            onClick = {
                onUpdate(
                    filters.copy(
                        regexRules = filters.regexRules + RegexFilterRule(
                            id = UUID.randomUUID().toString(),
                            pattern = "",
                            reverse = false,
                            enabled = true,
                        )
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add regex rule")
        }

        Spacer(Modifier.height(4.dp))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegexRuleCard(
    rule: RegexFilterRule,
    onUpdate: (RegexFilterRule) -> Unit,
    onDelete: () -> Unit,
) {
    val regexError = if (rule.enabled && rule.pattern.isNotBlank()) validateRegex(rule.pattern) else null
    var fieldMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Controls row: enable toggle | mode chips | delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = !rule.reverse,
                    onClick = { onUpdate(rule.copy(reverse = false)) },
                    label = { Text("Show matching", maxLines = 1, overflow = TextOverflow.Clip) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = rule.reverse,
                    onClick = { onUpdate(rule.copy(reverse = true)) },
                    label = { Text("Hide matching", maxLines = 1, overflow = TextOverflow.Clip) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete rule", modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            // Field selector
            ExposedDropdownMenuBox(
                expanded = fieldMenuExpanded,
                onExpandedChange = { fieldMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = rule.field.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Field") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fieldMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = fieldMenuExpanded,
                    onDismissRequest = { fieldMenuExpanded = false },
                ) {
                    RegexFilterField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.displayName) },
                            onClick = {
                                onUpdate(rule.copy(field = field))
                                fieldMenuExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = rule.pattern,
                onValueChange = { onUpdate(rule.copy(pattern = it)) },
                label = { Text("Pattern") },
                placeholder = { Text("e.g. load|cpu|disk") },
                singleLine = true,
                isError = regexError != null,
                supportingText = regexError?.let { err -> { Text("Invalid regex: $err", color = MaterialTheme.colorScheme.error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
