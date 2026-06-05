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
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.sound.AlertSoundPlayer

/**
 * Monitoring health overview (Goals 6 + 8).
 *
 * Shows status of: reliability mode, foreground service, last poll, WorkManager,
 * notification permission, notification channels, DND, battery optimisation.
 *
 * Provides action buttons to fix detected problems.
 */
@Composable
fun MonitoringHealthSection(
    commandSettings: CommandSettings,
    notificationSettings: NotificationSettings = NotificationSettings(),
) {
    val context = LocalContext.current
    val snapshot = remember { MonitoringHealth.getSnapshot(context) }
    val now = System.currentTimeMillis()

    // Notification health
    val notifEnabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val alertChannelOk = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = mgr?.getNotificationChannel(NotificationHelper.CHANNEL_ALERT_SUMMARY)
            ch != null && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
        } else true
    }
    val dndPolicyGranted = remember {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.isNotificationPolicyAccessGranted == true
    }

    // Stale detection
    val staleThresholdMs = commandSettings.monitoringStaleThresholdMinutes * 60_000L
    val isStale = snapshot.lastSuccessfulPollAt?.let { (now - it) > staleThresholdMs } ?: false

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ── Reliability mode ───────────────────────────────────────────────────
        HealthRow(
            label = "Reliability mode",
            value = if (commandSettings.keepMonitoringActive) "ON" else "OFF",
            ok = commandSettings.keepMonitoringActive,
        )

        // Warning: reliability mode ON but service not running
        if (commandSettings.keepMonitoringActive && !snapshot.isServiceRunning) {
            WarningCard("Reliability mode is ON but foreground service is not running. " +
                "Open qNag to restart it, or Android killed it — WorkManager fallback is active.")
        }

        HealthRow(
            label = "Foreground service",
            value = when {
                snapshot.isServiceRunning -> "running"
                snapshot.lastServiceStoppedAt != null ->
                    "stopped ${relativeTime(snapshot.lastServiceStoppedAt)}" +
                    (snapshot.lastServiceStopReason?.let { " ($it)" } ?: "")
                else -> "not started"
            },
            ok = snapshot.isServiceRunning,
        )

        // ── Poll health ────────────────────────────────────────────────────────
        HealthRow("Last poll finished", snapshot.lastPollFinishedAt?.let { relativeTime(it) } ?: "never")

        val lastSuccessLabel = snapshot.lastSuccessfulPollAt?.let { relativeTime(it) } ?: "never"
        HealthRow("Last successful poll", lastSuccessLabel, ok = !isStale && snapshot.lastSuccessfulPollAt != null)
        if (isStale) {
            WarningCard("Monitoring is stale — no successful poll in the last " +
                "${commandSettings.monitoringStaleThresholdMinutes} minutes.")
        }

        HealthRow("WorkManager last run", snapshot.lastWorkerRunAt?.let { relativeTime(it) } ?: "never")

        // ── Notification health ────────────────────────────────────────────────
        HealthRow("Android notifications enabled", if (notifEnabled) "yes" else "DISABLED", notifEnabled)
        if (!notifEnabled) {
            WarningCard("Android notifications are disabled for qNag. Alerts cannot appear or sound.")
        }

        HealthRow("Alert summary channel", if (alertChannelOk) "OK" else "muted or disabled", alertChannelOk)
        if (!alertChannelOk) {
            WarningCard("Alert summary channel is muted or disabled. " +
                "Open channel settings and set importance to at least 'Default'.")
        }

        // DND health (only relevant when DND help is enabled)
        if (notificationSettings.alertSoundMode == AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP ||
            notificationSettings.helpBypassDnd) {
            HealthRow("DND policy access", if (dndPolicyGranted) "granted" else "NOT GRANTED", dndPolicyGranted)
            if (!dndPolicyGranted) {
                WarningCard("DND policy access is missing. Grant it in the DND settings " +
                    "so qNag-controlled sounds can bypass Do Not Disturb.")
            }
        }

        // ── Action buttons ─────────────────────────────────────────────────────
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

        if (notificationSettings.alertSoundMode == AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP && !dndPolicyGranted) {
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant DND override access") }
        }

        // Test + stop sound buttons
        if (notificationSettings.alertSoundMode != AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { AlertSoundPlayer.playIfNeeded(context, true, notificationSettings) },
                    modifier = Modifier.weight(1f),
                ) { Text("Test alert sound", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = { AlertSoundPlayer.stop() },
                    modifier = Modifier.weight(1f),
                ) { Text("Stop sound", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = when (ok) {
                true -> okGreenColor()
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(8.dp),
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
