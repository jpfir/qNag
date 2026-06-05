package com.exogroup.qnag.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.notifications.NotificationHelper

/**
 * Settings section that shows monitoring health at a glance (Goal 6 + Goal 8).
 *
 * Reads from plain SharedPreferences on composition — no side effects, no live updates.
 * The user can navigate away and back to refresh the view.
 */
@Composable
fun MonitoringHealthSection(commandSettings: CommandSettings) {
    val context = LocalContext.current
    val snapshot = remember { MonitoringHealth.getSnapshot(context) }

    // Notification health checks (Goal 8)
    val notifEnabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val alertChannelOk = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = mgr?.getNotificationChannel(NotificationHelper.CHANNEL_ALERT_SUMMARY)
            ch != null && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
        } else true
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ── Reliability mode summary ───────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Reliability mode:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (commandSettings.keepMonitoringActive) "ON (foreground service)" else "OFF (WorkManager)",
                style = MaterialTheme.typography.bodySmall,
                color = if (commandSettings.keepMonitoringActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Foreground service status ──────────────────────────────────────────
        HealthRow("Foreground service",
            if (snapshot.isServiceRunning) "running"
            else snapshot.lastServiceStoppedAt?.let { "stopped (${relativeTime(it)}" + (snapshot.lastServiceStopReason?.let { r -> " — $r" } ?: "") + ")" }
                ?: "not started",
            snapshot.isServiceRunning,
        )

        // ── Poll health ────────────────────────────────────────────────────────
        HealthRow("Last poll started", snapshot.lastPollStartedAt?.let { relativeTime(it) } ?: "never")
        HealthRow("Last successful poll", snapshot.lastSuccessfulPollAt?.let { relativeTime(it) } ?: "never",
            ok = snapshot.lastSuccessfulPollAt != null)
        HealthRow("Last WorkManager run", snapshot.lastWorkerRunAt?.let { relativeTime(it) } ?: "never")

        // ── Notification health ────────────────────────────────────────────────
        HealthRow("Android notifications", if (notifEnabled) "enabled" else "DISABLED", notifEnabled)
        HealthRow("Alert summary channel", if (alertChannelOk) "OK" else "muted or disabled", alertChannelOk)

        // ── Open system settings buttons ──────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        if (!notifEnabled || !alertChannelOk) {
            Text(
                "If Android notification settings block qNag, alerts cannot sound.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("App notifications", style = MaterialTheme.typography.bodySmall) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_ALERT_SUMMARY)
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Alert channel", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = when (ok) {
                true -> Color(0xFF2E7D32)
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun relativeTime(epochMs: Long): String {
    val diffSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
    }
}
