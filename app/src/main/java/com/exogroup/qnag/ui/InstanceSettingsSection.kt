package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NagiosInstance

/**
 * Displays one card per instance with enable/notifications toggles and a delete button.
 *
 * All changes are applied immediately via [onUpdate].
 * Deletion requires confirmation via an AlertDialog.
 */
@Composable
fun InstanceSettingsSection(
    instances: List<NagiosInstance>,
    onUpdate: (List<NagiosInstance>) -> Unit,
) {
    var instanceToDelete by remember { mutableStateOf<NagiosInstance?>(null) }

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
                    onDeleteRequest = { instanceToDelete = instance },
                )
            }
        }
    }

    // Confirmation dialog — only rendered while instanceToDelete is set
    instanceToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = { Text("Remove Instance") },
            text = {
                Text("Remove \"${target.name}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdate(instances.filter { it.id != target.id })
                        instanceToDelete = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: NagiosInstance,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
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
            // Header row: name + delete button
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

            // Toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LabeledSwitch(
                    label = "Enabled",
                    checked = instance.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.weight(1f),
                )
                LabeledSwitch(
                    label = "Notifications",
                    checked = instance.notificationsEnabled,
                    onCheckedChange = onToggleNotifications,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
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
