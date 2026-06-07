// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exogroup.qnag.data.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var entries by remember { mutableStateOf(EventLog.getEntries(context)) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Event Log") },
            text = { Text("Delete all ${entries.size} log entries? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    EventLog.clear(context)
                    entries = emptyList()
                    showClearConfirm = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Event Log")
                        Text(
                            "${entries.size} entries · safe to share",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Copy
                    IconButton(onClick = {
                        val text = EventLog.formatForSharing(entries)
                        clipboardManager.setText(AnnotatedString(text))
                    }) { Icon(Icons.Default.ContentCopy, "Copy log") }
                    // Share
                    IconButton(onClick = {
                        val text = EventLog.formatForSharing(entries)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, "qNag Event Log")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Event Log"))
                    }) { Icon(Icons.Default.Share, "Share log") }
                    // Clear
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.Delete, "Clear log", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No events recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(entries) { entry ->
                    EventLogEntryRow(entry)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun EventLogEntryRow(entry: EventLog.Entry) {
    val (levelBg, levelFg) = when (entry.level) {
        EventLog.ERROR -> Color(0xFFFCE4EC) to Color(0xFFC62828)
        EventLog.WARN  -> Color(0xFFFFF8E1) to Color(0xFFF9A825)
        else           -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
    }
    val catColor = when (entry.category) {
        EventLog.CAT_COMMAND  -> Color(0xFF1565C0)
        EventLog.CAT_WATCHDOG -> Color(0xFF6A1B9A)
        EventLog.CAT_SOUND    -> Color(0xFF00838F)
        EventLog.CAT_NOTIF    -> Color(0xFFE65100)
        else                  -> Color(0xFF546E7A)
    }
    val tsText = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Level badge
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = levelBg,
                contentColor = levelFg,
                modifier = Modifier.padding(top = 1.dp),
            ) {
                Text(
                    entry.level,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // Category badge
            Box(
                modifier = Modifier
                    .background(catColor.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .padding(top = 1.dp),
            ) {
                Text(
                    entry.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = catColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
