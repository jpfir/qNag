package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosInstance

/**
 * Displays one card per instance with monitoring enable / Android-alerts toggles, an edit button,
 * and a delete button.
 *
 * The two toggles are intentionally distinct:
 *  - "Monitoring" (Enabled) — the instance is queried and shown in the dashboard.
 *  - "Android alerts" (notificationsEnabled) — the instance contributes to the Android shade
 *    status notification and per-alert Android notifications.  Does NOT control in-app monitoring.
 *
 * All changes are applied immediately via [onUpdate].
 * Deletion and editing both require confirmation / a dialog.
 */
@Composable
fun InstanceSettingsSection(
    instances: List<NagiosInstance>,
    onUpdate: (List<NagiosInstance>) -> Unit,
) {
    var instanceToDelete by remember { mutableStateOf<NagiosInstance?>(null) }
    var instanceToEdit by remember { mutableStateOf<NagiosInstance?>(null) }

    if (instances.isEmpty()) {
        Text(
            "No instances configured.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            instances.forEach { instance ->
                InstanceCard(
                    instance = instance,
                    onToggleEnabled = { enabled ->
                        onUpdate(instances.map { if (it.id == instance.id) it.copy(enabled = enabled) else it })
                    },
                    onToggleNotifications = { enabled ->
                        onUpdate(instances.map { if (it.id == instance.id) it.copy(notificationsEnabled = enabled) else it })
                    },
                    onEditRequest = { instanceToEdit = instance },
                    onDeleteRequest = { instanceToDelete = instance },
                )
            }
        }
    }

    // Delete confirmation dialog
    instanceToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = { Text("Remove Instance") },
            text = { Text("Remove \"${target.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdate(instances.filter { it.id != target.id })
                        instanceToDelete = null
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { instanceToDelete = null }) { Text("Cancel") }
            },
        )
    }

    // Edit dialog
    instanceToEdit?.let { target ->
        EditInstanceDialog(
            instance = target,
            onSave = { updated ->
                onUpdate(instances.map { if (it.id == updated.id) updated else it })
                instanceToEdit = null
            },
            onDismiss = { instanceToEdit = null },
        )
    }
}

// ── Instance card ─────────────────────────────────────────────────────────────

@Composable
private fun InstanceCard(
    instance: NagiosInstance,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (instance.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instance.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = instance.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEditRequest) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit instance")
                }
                IconButton(onClick = onDeleteRequest) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove instance",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabeledSwitch(
                    label = "Monitoring",
                    checked = instance.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.weight(1f),
                )
                LabeledSwitch(
                    label = "Android alerts",
                    checked = instance.notificationsEnabled,
                    onCheckedChange = onToggleNotifications,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Monitoring: shown in dashboard  ·  Android alerts: shade status & per-alert notifications",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Edit dialog ───────────────────────────────────────────────────────────────

@Composable
private fun EditInstanceDialog(
    instance: NagiosInstance,
    onSave: (NagiosInstance) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(instance.name) }
    var url by remember { mutableStateOf(instance.url) }
    var username by remember { mutableStateOf(instance.username) }
    // Empty = keep existing password.  Non-empty = replace.
    // The existing password is never displayed.
    var newPassword by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(instance.enabled) }
    var notificationsEnabled by remember { mutableStateOf(instance.notificationsEnabled) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Instance") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Nagios URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null },
                    label = { Text("New password") },
                    placeholder = { Text("Leave blank to keep existing") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave password blank to keep the existing password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LabeledSwitch(
                        label = "Monitoring",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        modifier = Modifier.weight(1f),
                    )
                    LabeledSwitch(
                        label = "Android alerts",
                        checked = notificationsEnabled,
                        onCheckedChange = { notificationsEnabled = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Monitoring: instance is queried and shown in the dashboard.\n" +
                    "Android alerts: contributes to the Android shade status notification and per-alert notifications. Does not affect in-app monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = validateEditFields(name, url, username, newPassword)
                if (err != null) {
                    error = err
                    return@TextButton
                }
                onSave(
                    instance.copy(
                        name = name.trim(),
                        url = url.trim(),
                        username = username.trim(),
                        // Empty → keep existing password.  Non-empty → replace.
                        password = if (newPassword.isEmpty()) instance.password else newPassword,
                        enabled = enabled,
                        notificationsEnabled = notificationsEnabled,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun validateEditFields(name: String, url: String, username: String, newPassword: String): String? {
    if (name.isBlank()) return "Display name is required."
    if (url.isBlank() || url == "https://") return "A valid Nagios URL is required."
    if (!url.startsWith("http://") && !url.startsWith("https://")) return "URL must start with http:// or https://"
    if (username.isBlank()) return "Username is required."
    if (newPassword.isNotEmpty() && newPassword.isBlank()) return "New password cannot be only whitespace."
    return null
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
