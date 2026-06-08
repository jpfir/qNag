package com.exogroup.qnag.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.ImportPreview
import com.exogroup.qnag.data.NagiosInstance

// ── Import preview dialog ─────────────────────────────────────────────────────

@Composable
internal fun ImportPreviewDialog(
    preview: ImportPreview,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = preview.toAdd.size + preview.toUpdate.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Instances") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Found $total valid instance(s):")
                if (preview.toAdd.isNotEmpty()) {
                    Text(
                        "• ${preview.toAdd.size} new — will be added",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (preview.toUpdate.isNotEmpty()) {
                    Text(
                        "• ${preview.toUpdate.size} existing — will be updated",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (preview.passwordsIncluded) "Passwords: included in file"
                    else "Passwords: not included — existing passwords preserved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.toUpdate.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Existing instances matched by URL + username. Other settings preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Export dialog ─────────────────────────────────────────────────────────────

@Composable
internal fun ExportInstancesDialog(
    instances: List<NagiosInstance>,
    onExport: (selectedInstances: List<NagiosInstance>, includePasswords: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember { mutableStateOf(instances.map { it.id }.toSet()) }
    var includePasswords by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Instances") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Select instances to export:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))

                instances.forEach { inst ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (selectedIds.contains(inst.id))
                                    selectedIds - inst.id
                                else
                                    selectedIds + inst.id
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedIds.contains(inst.id),
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + inst.id else selectedIds - inst.id
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                inst.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                inst.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Include passwords",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includePasswords,
                        onCheckedChange = { includePasswords = it },
                    )
                }

                if (includePasswords) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠ Passwords will be written in plaintext JSON. Only store or share this file securely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(instances.filter { selectedIds.contains(it.id) }, includePasswords) },
                enabled = selectedIds.isNotEmpty(),
            ) { Text("Export…") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
