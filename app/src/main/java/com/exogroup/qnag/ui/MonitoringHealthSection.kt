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
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.reliability.ExactAlarmWatchdogScheduler
import com.exogroup.qnag.service.NagiosMonitoringService
import com.exogroup.qnag.sound.AlertSoundPlayer
import com.exogroup.qnag.worker.BackgroundPollingScheduler

/**
 * Monitoring health overview — shows all reliability layers and provides recovery actions.
 *
 * Layers shown:
 *  1. Reliability Mode / foreground service
 *  2. Exact Alarm Watchdog
 *  3. WorkManager fallback (with honest stale/overdue detection)
 *  4. Notification health
 *
 * Recovery actions let the user fix degraded states without leaving the app.
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
    val exactAlarmGranted = remember { ExactAlarmWatchdogScheduler.canScheduleExactAlarms(context) }

    // Stale detection
    val staleThresholdMs = commandSettings.monitoringStaleThresholdMinutes * 60_000L
    val isStale = snapshot.lastSuccessfulPollAt?.let { (now - it) > staleThresholdMs } ?: false

    // WorkManager health (Goal 3)
    val expectedWorkerIntervalMs = notificationSettings.refreshIntervalMinutes.coerceAtLeast(15) * 60_000L
    val workerStatus = when {
        snapshot.lastWorkerRunAt == null -> "NOT SCHEDULED"
        (now - snapshot.lastWorkerRunAt) > expectedWorkerIntervalMs * 3 -> "OVERDUE"
        else -> "OK"
    }
    val workerStatusOk = workerStatus == "OK"

    // Watchdog health
    val expectedWatchdogMs = commandSettings.exactAlarmWatchdogIntervalMinutes * 60_000L
    val watchdogOverdue = snapshot.lastExactAlarmFiredAt?.let {
        (now - it) > expectedWatchdogMs * 3
    } ?: false

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ── Reliability mode summary ───────────────────────────────────────────
        HealthRow(
            label = "Reliability mode",
            value = if (commandSettings.keepMonitoringActive) "ON" else "OFF",
            ok = commandSettings.keepMonitoringActive,
        )
        HealthRow(
            label = "Foreground service",
            value = when {
                snapshot.isServiceRunning -> "running"
                snapshot.lastServiceStoppedAt != null ->
                    "stopped ${relTime(snapshot.lastServiceStoppedAt)}" +
                    (snapshot.lastServiceStopReason?.let { " ($it)" } ?: "")
                else -> "not started"
            },
            ok = snapshot.isServiceRunning,
        )

        if (commandSettings.keepMonitoringActive && !snapshot.isServiceRunning) {
            WarningCard("Reliability mode is ON but foreground service is not running. Tap Restart below.")
        }

        HealthRow("Last successful poll",
            snapshot.lastSuccessfulPollAt?.let { relTime(it) } ?: "never",
            ok = !isStale && snapshot.lastSuccessfulPollAt != null)
        if (isStale) WarningCard("Monitoring is stale — no successful poll in the last ${commandSettings.monitoringStaleThresholdMinutes} minutes.")

        // ── Exact Alarm Watchdog ───────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow("Exact Alarm Watchdog",
            if (commandSettings.exactAlarmWatchdogEnabled) "enabled" else "disabled",
            ok = commandSettings.exactAlarmWatchdogEnabled)
        HealthRow("Exact alarm permission",
            if (exactAlarmGranted) "granted" else "MISSING",
            ok = exactAlarmGranted)
        if (commandSettings.exactAlarmWatchdogEnabled && !exactAlarmGranted) {
            WarningCard("Exact alarm permission is missing. Watchdog recovery may be delayed. Grant it in Settings → Special app access → Alarms & reminders.")
        }
        if (commandSettings.exactAlarmWatchdogEnabled) {
            HealthRow("Watchdog scheduled",
                if (snapshot.exactAlarmScheduled) "yes" else "NO",
                ok = snapshot.exactAlarmScheduled)
            snapshot.nextExactAlarmAt?.let {
                HealthRow("Next watchdog alarm", if (it > now) "in ${(it - now) / 1000}s" else "imminent")
            }
            snapshot.lastExactAlarmFiredAt?.let {
                HealthRow("Last watchdog fired",
                    relTime(it),
                    ok = !watchdogOverdue)
            }
            snapshot.lastExactAlarmAction?.let { HealthRow("Last watchdog action", it) }
            if (watchdogOverdue) WarningCard("Watchdog has not fired recently — it may have been cancelled by the OS.")
        }

        // ── WorkManager fallback ───────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow("WorkManager fallback",
            when {
                workerStatus == "NOT SCHEDULED" -> "NOT SCHEDULED"
                workerStatus == "OVERDUE" ->
                    "OVERDUE — last run ${snapshot.lastWorkerRunAt?.let { relTime(it) } ?: "never"}"
                else ->
                    "OK — last run ${snapshot.lastWorkerRunAt?.let { relTime(it) } ?: "never"}"
            },
            ok = workerStatusOk)
        if (workerStatus == "OVERDUE") WarningCard("WorkManager fallback is overdue. Expected every ${notificationSettings.refreshIntervalMinutes}m but last ran ${snapshot.lastWorkerRunAt?.let { relTime(it) }}.")

        // ── Notification health ────────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow("Android notifications enabled", if (notifEnabled) "yes" else "DISABLED", notifEnabled)
        if (!notifEnabled) WarningCard("Android notifications are disabled for qNag. Alerts cannot appear or sound.")

        HealthRow("Alert summary channel", if (alertChannelOk) "OK" else "muted or disabled", alertChannelOk)
        if (!alertChannelOk) WarningCard("Alert summary channel is muted or disabled. Open channel settings and set importance to at least Default.")

        if (notificationSettings.alertSoundMode == AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP ||
            notificationSettings.helpBypassDnd) {
            HealthRow("DND policy access", if (dndPolicyGranted) "granted" else "NOT GRANTED", dndPolicyGranted)
        }

        // ── Recovery actions (Goal 2, 9) ───────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Text("Recovery actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        // Restart Reliability Mode
        if (commandSettings.keepMonitoringActive) {
            OutlinedButton(onClick = {
                val store = SecureInstanceStore(context)
                val settings = store.getAppSettings()
                val instances = store.getInstances()
                BackgroundPollingScheduler.cancel(context)
                NagiosMonitoringService.start(context)
                ExactAlarmWatchdogScheduler.schedule(context, settings)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Restart Reliability Mode")
            }
        }

        // Run check now
        OutlinedButton(onClick = {
            val store = SecureInstanceStore(context)
            val settings = store.getAppSettings()
            if (settings.commandSettings.keepMonitoringActive) {
                NagiosMonitoringService.start(context)
            } else {
                BackgroundPollingScheduler.scheduleOnce(context)
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (commandSettings.keepMonitoringActive) "Run check now (reload service)" else "Run check now (WorkManager)")
        }

        // Reschedule watchdog
        if (commandSettings.exactAlarmWatchdogEnabled && exactAlarmGranted) {
            OutlinedButton(onClick = {
                val store = SecureInstanceStore(context)
                ExactAlarmWatchdogScheduler.schedule(context, store.getAppSettings())
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Reschedule watchdog")
            }
        }

        // Reschedule WorkManager fallback
        OutlinedButton(onClick = {
            val store = SecureInstanceStore(context)
            val settings = store.getAppSettings()
            BackgroundPollingScheduler.scheduleFallback(context, settings, store.getInstances())
        }, modifier = Modifier.fillMaxWidth()) { Text("Reschedule WorkManager fallback") }

        // Open exact alarm settings
        if (commandSettings.exactAlarmWatchdogEnabled && !exactAlarmGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open exact alarm settings") }
        }

        // System settings buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            }, modifier = Modifier.weight(1f)) { Text("Notification settings", style = MaterialTheme.typography.bodySmall) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_ALERT_SUMMARY)
                    })
                }, modifier = Modifier.weight(1f)) { Text("Alert channel", style = MaterialTheme.typography.bodySmall) }
            }
        }

        // Test + stop sound
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

private fun relTime(epochMs: Long): String {
    val diffSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        diffSec < 5 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
    }
}
