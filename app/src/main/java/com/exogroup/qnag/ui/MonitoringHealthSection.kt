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
import kotlinx.coroutines.delay

/**
 * Monitoring health overview — shows all reliability layers and provides recovery actions.
 *
 * Layers shown:
 *  1. Reliability Mode / foreground service
 *  2. Exact Alarm Watchdog
 *  3. WorkManager fallback (with context-aware status — no false OVERDUE when primary is healthy)
 *  4. Notification health
 *
 * Refreshes every 5 seconds while the section is visible so countdown timers and poll
 * ages stay current without requiring the user to leave and re-enter the screen.
 */
@Composable
fun MonitoringHealthSection(
    commandSettings: CommandSettings,
    notificationSettings: NotificationSettings = NotificationSettings(),
) {
    val context = LocalContext.current

    // ── Live-refreshing state (Goal 4) ────────────────────────────────────────
    var snapshot by remember { mutableStateOf(MonitoringHealth.getSnapshot(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            snapshot = MonitoringHealth.getSnapshot(context)
            now = System.currentTimeMillis()
        }
    }

    // ── Notification / permission health ──────────────────────────────────────
    val notifEnabled = remember { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val alertChannelOk = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = mgr?.getNotificationChannel(NotificationHelper.CHANNEL_ALERT_SUMMARY)
            ch != null && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
        } else true
    }
    val dndPolicyGranted = remember {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true
    }
    val exactAlarmGranted = remember { ExactAlarmWatchdogScheduler.canScheduleExactAlarms(context) }

    // ── Derived health signals ────────────────────────────────────────────────
    val staleThresholdMs = commandSettings.monitoringStaleThresholdMinutes * 60_000L
    val isStale = snapshot.lastSuccessfulPollAt?.let { (now - it) > staleThresholdMs } ?: false

    // "Primary monitoring healthy" = foreground service running + poll is fresh
    val primaryHealthy = commandSettings.keepMonitoringActive && snapshot.isServiceRunning && !isStale

    // WorkManager status — only shown as OVERDUE when it is actually expected to be running
    val workerStatus = computeWorkerStatus(
        snapshot         = snapshot,
        commandSettings  = commandSettings,
        notifSettings    = notificationSettings,
        primaryHealthy   = primaryHealthy,
        now              = now,
    )

    // Watchdog health
    val watchdogEnabled = commandSettings.exactAlarmWatchdogEnabled && exactAlarmGranted
    val expectedWatchdogMs = commandSettings.exactAlarmWatchdogIntervalMinutes * 60_000L
    val watchdogOverdue = snapshot.lastExactAlarmFiredAt
        ?.let { (now - it) > expectedWatchdogMs * 3 }
        ?: false

    // Service degraded = Reliability Mode ON but service stopped or stale
    val serviceDegraded = commandSettings.keepMonitoringActive &&
            (!snapshot.isServiceRunning || isStale)

    // ── UI ────────────────────────────────────────────────────────────────────

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // ── Foreground service ─────────────────────────────────────────────────
        HealthRow(
            label = "Reliability Mode",
            value = if (commandSettings.keepMonitoringActive) "ON" else "OFF",
            ok    = commandSettings.keepMonitoringActive,
        )
        // Capture nullable fields from the delegated property into local vals so that
        // smart-casts work inside the `when` expression (snapshot is a `var` delegate).
        val stoppedAt  = snapshot.lastServiceStoppedAt
        val stopReason = snapshot.lastServiceStopReason
        HealthRow(
            label = "Foreground service",
            value = when {
                snapshot.isServiceRunning -> "running"
                stoppedAt != null ->
                    "stopped ${relTime(now, stoppedAt)}" +
                    (stopReason?.let { " ($it)" } ?: "")
                else -> "not started"
            },
            ok = snapshot.isServiceRunning,
        )

        if (serviceDegraded && !snapshot.isServiceRunning) {
            WarningCard("Reliability Mode is ON but the foreground service has stopped. Tap 'Restart Reliability Mode' below.")
        } else if (serviceDegraded && isStale) {
            WarningCard("Monitoring is stale — no successful poll in the last ${commandSettings.monitoringStaleThresholdMinutes}m. Service may be stuck.")
        }

        HealthRow(
            label = "Last successful poll",
            value = snapshot.lastSuccessfulPollAt?.let { relTime(now, it) } ?: "never",
            ok    = !isStale && snapshot.lastSuccessfulPollAt != null,
        )

        // ── Exact Alarm Watchdog ───────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow(
            label = "Exact Alarm Watchdog",
            value = when {
                !commandSettings.exactAlarmWatchdogEnabled -> "disabled"
                !exactAlarmGranted -> "enabled — permission MISSING"
                snapshot.exactAlarmScheduled && !watchdogOverdue -> {
                    val next = snapshot.nextExactAlarmAt
                    if (next != null && next > now) "healthy — next in ${(next - now) / 1000}s"
                    else "healthy"
                }
                watchdogOverdue -> "OVERDUE — may need recovery"
                else -> "enabled — not yet scheduled"
            },
            ok = commandSettings.exactAlarmWatchdogEnabled && exactAlarmGranted &&
                 snapshot.exactAlarmScheduled && !watchdogOverdue,
        )
        if (commandSettings.exactAlarmWatchdogEnabled && !exactAlarmGranted) {
            WarningCard("Exact alarm permission is missing. Grant it in Settings → Special app access → Alarms & reminders.")
        }
        if (commandSettings.exactAlarmWatchdogEnabled && exactAlarmGranted && watchdogOverdue) {
            WarningCard("Watchdog has not fired recently — it may have been cancelled by the OS.")
        }
        snapshot.lastExactAlarmAction?.let {
            HealthRow("Last watchdog action", it)
        }

        // ── WorkManager fallback ───────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow(
            label = "WorkManager fallback",
            value = workerStatus.displayText(snapshot, notificationSettings, now),
            ok    = workerStatus.isOk(),
        )
        if (workerStatus == WorkerFallbackStatus.FALLBACK_OVERDUE) {
            val expected = notificationSettings.refreshIntervalMinutes
            WarningCard("WorkManager fallback is overdue — expected every ${expected}m, last ran ${snapshot.lastWorkerRunAt?.let { relTime(now, it) } ?: "never"}.")
        }
        if (workerStatus == WorkerFallbackStatus.NOT_SCHEDULED) {
            WarningCard("WorkManager fallback is not scheduled. Tap 'Reschedule WorkManager fallback' below.")
        }

        // ── Notification health ────────────────────────────────────────────────
        Spacer(Modifier.height(2.dp))
        HealthRow("Android notifications", if (notifEnabled) "enabled" else "DISABLED", notifEnabled)
        if (!notifEnabled) WarningCard("Android notifications are disabled for qNag. Alerts cannot appear or sound.")

        HealthRow("Alert summary channel", if (alertChannelOk) "OK" else "muted or disabled", alertChannelOk)
        if (!alertChannelOk) WarningCard("Alert summary channel is muted or disabled.")

        if (notificationSettings.alertSoundMode == AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP ||
            notificationSettings.helpBypassDnd) {
            HealthRow("DND policy access", if (dndPolicyGranted) "granted" else "NOT GRANTED", dndPolicyGranted)
        }

        // ── Recovery actions (contextual — Goal 5) ─────────────────────────────
        Spacer(Modifier.height(8.dp))
        Text("Recovery actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        // "Restart Reliability Mode" — only when degraded
        if (serviceDegraded) {
            Button(onClick = {
                val store = SecureInstanceStore(context)
                val settings = store.getAppSettings()
                BackgroundPollingScheduler.cancel(context)
                NagiosMonitoringService.start(context)
                ExactAlarmWatchdogScheduler.schedule(context, settings)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Restart Reliability Mode")
            }
        }

        // "Run check now" — always available
        OutlinedButton(onClick = {
            val store = SecureInstanceStore(context)
            val settings = store.getAppSettings()
            if (settings.commandSettings.keepMonitoringActive) {
                NagiosMonitoringService.start(context)  // reload service
            } else {
                BackgroundPollingScheduler.scheduleOnce(context)
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (commandSettings.keepMonitoringActive) "Run check now (reload service)" else "Run check now (WorkManager)")
        }

        // "Reschedule watchdog" — when watchdog is unhealthy
        if (commandSettings.exactAlarmWatchdogEnabled && exactAlarmGranted &&
            (!snapshot.exactAlarmScheduled || watchdogOverdue)) {
            OutlinedButton(onClick = {
                val store = SecureInstanceStore(context)
                ExactAlarmWatchdogScheduler.schedule(context, store.getAppSettings())
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Reschedule Exact Alarm Watchdog")
            }
        }

        // "Reschedule WorkManager fallback" — when fallback is expected but missing/overdue
        if (workerStatus == WorkerFallbackStatus.NOT_SCHEDULED ||
            workerStatus == WorkerFallbackStatus.FALLBACK_OVERDUE) {
            OutlinedButton(onClick = {
                val store = SecureInstanceStore(context)
                val settings = store.getAppSettings()
                BackgroundPollingScheduler.scheduleFallback(context, settings, store.getInstances())
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Reschedule WorkManager fallback")
            }
        }

        // Exact alarm permission button — when needed and missing
        if (commandSettings.exactAlarmWatchdogEnabled && !exactAlarmGranted &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Open exact alarm settings")
            }
        }

        // System notification settings
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            }, modifier = Modifier.weight(1f)) {
                Text("Notification settings", style = MaterialTheme.typography.bodySmall)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_ALERT_SUMMARY)
                    })
                }, modifier = Modifier.weight(1f)) {
                    Text("Alert channel", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Test / stop sound
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

        // Test alert notification — posts through the wearable-compatible nagios_alerts channel
        // so Samsung Galaxy Wearable / Galaxy Fit discovers qNag and allows notification forwarding.
        OutlinedButton(
            onClick = { NotificationHelper.postTestAlertNotification(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send test alert notification", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Posts a test notification through the Nagios alerts channel. " +
            "Required for Samsung Galaxy Wearable / Galaxy Fit to detect qNag as a notification source.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── WorkManager fallback status ───────────────────────────────────────────────

/** Context-aware WorkManager status — avoids false OVERDUE while primary monitoring is healthy. */
private enum class WorkerFallbackStatus {
    /** Foreground service + poll are healthy — WorkManager is intentional standby, not a concern. */
    PRIMARY_ACTIVE,
    /** WorkManager is expected and ran recently. */
    FALLBACK_OK,
    /** WorkManager is expected but has not run recently. */
    FALLBACK_OVERDUE,
    /** WorkManager is expected but no evidence it is scheduled or has ever run. */
    NOT_SCHEDULED,
    ;

    fun isOk(): Boolean = this == PRIMARY_ACTIVE || this == FALLBACK_OK

    fun displayText(
        snapshot: MonitoringHealth.HealthSnapshot,
        notifSettings: NotificationSettings,
        now: Long,
    ): String = when (this) {
        PRIMARY_ACTIVE -> "fallback standby — Reliability Mode active"
        FALLBACK_OK -> "OK — last run ${snapshot.lastWorkerRunAt?.let { relTime(now, it) } ?: "never"}"
        FALLBACK_OVERDUE -> "OVERDUE — last run ${snapshot.lastWorkerRunAt?.let { relTime(now, it) } ?: "never"}"
        NOT_SCHEDULED -> "NOT SCHEDULED — fallback expected"
    }
}

private fun computeWorkerStatus(
    snapshot: MonitoringHealth.HealthSnapshot,
    commandSettings: CommandSettings,
    notifSettings: NotificationSettings,
    primaryHealthy: Boolean,
    now: Long,
): WorkerFallbackStatus {
    // Primary monitoring is healthy → WorkManager is intentional standby, not overdue
    if (primaryHealthy) return WorkerFallbackStatus.PRIMARY_ACTIVE

    // WorkManager is expected as fallback or primary (service stopped / RM off)
    val expectedIntervalMs = notifSettings.refreshIntervalMinutes.coerceAtLeast(15) * 60_000L
    val lastRun = snapshot.lastWorkerRunAt

    return when {
        // No evidence of scheduling or running at all
        !snapshot.workerScheduled && lastRun == null -> WorkerFallbackStatus.NOT_SCHEDULED
        // Has run recently enough
        lastRun != null && (now - lastRun) <= expectedIntervalMs * 3 -> WorkerFallbackStatus.FALLBACK_OK
        // Scheduled but never ran, and it's early — give it a grace period equal to one interval
        lastRun == null && snapshot.lastWorkerScheduledAt != null &&
                (now - (snapshot.lastWorkerScheduledAt)) <= expectedIntervalMs -> WorkerFallbackStatus.FALLBACK_OK
        // Overdue
        else -> WorkerFallbackStatus.FALLBACK_OVERDUE
    }
}

// ── Private UI helpers ────────────────────────────────────────────────────────

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = when (ok) {
                true  -> okGreenColor()
                false -> MaterialTheme.colorScheme.error
                null  -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun WarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        ),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun relTime(now: Long, epochMs: Long): String {
    val diffSec = (now - epochMs) / 1000
    return when {
        diffSec < 5    -> "just now"
        diffSec < 60   -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else           -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
    }
}
