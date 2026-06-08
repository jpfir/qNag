package com.exogroup.qnag.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.reliability.ExactAlarmWatchdogScheduler
import kotlinx.coroutines.delay

@Composable
fun ReliabilityChecklistScreen(
    notificationSettings: NotificationSettings,
    commandSettings: CommandSettings,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current

    var batteryExempt by remember {
        mutableStateOf(
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        )
    }
    var exactAlarmGranted by remember {
        mutableStateOf(ExactAlarmWatchdogScheduler.canScheduleExactAlarms(context))
    }
    var systemNotifEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var alertChannelOk by remember { mutableStateOf(checkAlertChannel(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            batteryExempt = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            exactAlarmGranted = ExactAlarmWatchdogScheduler.canScheduleExactAlarms(context)
            systemNotifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            alertChannelOk = checkAlertChannel(context)
        }
    }

    val notifPermNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val exactAlarmNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val items = buildChecklistItems(
        notifPermNeeded = notifPermNeeded,
        notificationPermissionGranted = notificationPermissionGranted,
        batteryExempt = batteryExempt,
        exactAlarmNeeded = exactAlarmNeeded,
        exactAlarmGranted = exactAlarmGranted,
        keepMonitoringActive = commandSettings.keepMonitoringActive,
        notificationsEnabled = notificationSettings.notificationsEnabled,
        systemNotifEnabled = systemNotifEnabled,
        alertChannelOk = alertChannelOk,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenBatterySettings = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        },
        onOpenAlarmSettings = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        },
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        },
    )

    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Start monitoring")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text("Reliability Checklist", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Verify these settings before relying on qNag for on-call monitoring. " +
                "Android's power management may affect background alerting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            items.forEachIndexed { index, item ->
                ChecklistRow(item)
                if (index < items.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "About Android background monitoring",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android's Doze mode, battery optimization, and manufacturer-specific " +
                        "power management may pause background processes even with Reliability " +
                        "Mode enabled. On battery-aggressive devices (Samsung, Xiaomi, Huawei), " +
                        "additional per-app \"Auto-start\" or \"Battery\" settings may be required. " +
                        "Check Settings → Monitoring Health after the first alerts to verify " +
                        "monitoring is working correctly. Advanced controls are available in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Checklist data ────────────────────────────────────────────────────────────

private enum class CheckStatus { OK, WARN, ERROR }

private data class ChecklistItem(
    val label: String,
    val detail: String,
    val status: CheckStatus,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

private fun buildChecklistItems(
    notifPermNeeded: Boolean,
    notificationPermissionGranted: Boolean,
    batteryExempt: Boolean,
    exactAlarmNeeded: Boolean,
    exactAlarmGranted: Boolean,
    keepMonitoringActive: Boolean,
    notificationsEnabled: Boolean,
    systemNotifEnabled: Boolean,
    alertChannelOk: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAlarmSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
): List<ChecklistItem> = listOf(
    ChecklistItem(
        label = "Notification permission",
        detail = when {
            !notifPermNeeded -> "Not required on this Android version"
            notificationPermissionGranted -> "Granted"
            else -> "Not granted — alerts cannot appear on this device"
        },
        status = when {
            !notifPermNeeded || notificationPermissionGranted -> CheckStatus.OK
            else -> CheckStatus.ERROR
        },
        actionLabel = if (notifPermNeeded && !notificationPermissionGranted) "Allow notifications" else null,
        onAction = if (notifPermNeeded && !notificationPermissionGranted) onRequestNotificationPermission else null,
    ),
    ChecklistItem(
        label = "Battery optimization",
        detail = if (batteryExempt) "Exempt — monitoring can run freely"
                 else "Not exempt — Android may pause monitoring when the screen is off",
        status = if (batteryExempt) CheckStatus.OK else CheckStatus.WARN,
        actionLabel = if (!batteryExempt) "Remove battery restriction" else null,
        onAction = if (!batteryExempt) onOpenBatterySettings else null,
    ),
    ChecklistItem(
        label = "Exact Alarm Watchdog",
        detail = when {
            !exactAlarmNeeded -> "Not required on this Android version"
            exactAlarmGranted -> "Permission granted — watchdog can recover the service"
            else -> "Permission missing — watchdog cannot schedule exact alarm recovery"
        },
        status = when {
            !exactAlarmNeeded || exactAlarmGranted -> CheckStatus.OK
            else -> CheckStatus.WARN
        },
        actionLabel = if (exactAlarmNeeded && !exactAlarmGranted) "Grant alarm permission" else null,
        onAction = if (exactAlarmNeeded && !exactAlarmGranted) onOpenAlarmSettings else null,
    ),
    ChecklistItem(
        label = "Reliability Mode",
        detail = if (keepMonitoringActive)
            "Enabled — foreground service polls continuously"
        else
            "Disabled — only 15-minute background polling (may miss alerts)",
        status = if (keepMonitoringActive) CheckStatus.OK else CheckStatus.WARN,
    ),
    ChecklistItem(
        label = "Android alerts",
        detail = if (notificationsEnabled) "Enabled in qNag"
                 else "Disabled in qNag — no alerts will fire (enable in Settings → Notifications)",
        status = if (notificationsEnabled) CheckStatus.OK else CheckStatus.WARN,
    ),
    ChecklistItem(
        label = "Alert notification channel",
        detail = when {
            !systemNotifEnabled -> "Android notifications blocked — no alerts can appear"
            !alertChannelOk -> "Alert summary channel is muted or disabled"
            else -> "Configured"
        },
        status = when {
            !systemNotifEnabled || !alertChannelOk -> CheckStatus.WARN
            else -> CheckStatus.OK
        },
        actionLabel = if (!systemNotifEnabled || !alertChannelOk) "Open notification settings" else null,
        onAction = if (!systemNotifEnabled || !alertChannelOk) onOpenNotificationSettings else null,
    ),
)

// ── Row composable ────────────────────────────────────────────────────────────

@Composable
private fun ChecklistRow(item: ChecklistItem) {
    val errorColor = MaterialTheme.colorScheme.error
    val (icon, tint) = when (item.status) {
        CheckStatus.OK -> Icons.Default.CheckCircle to okGreenColor()
        CheckStatus.WARN -> Icons.Default.Warning to Color(0xFFF9A825)
        CheckStatus.ERROR -> Icons.Default.Error to errorColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.actionLabel != null && item.onAction != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = item.onAction,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(item.actionLabel, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun checkAlertChannel(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
    val ch = mgr.getNotificationChannel(NotificationHelper.CHANNEL_ALERT_SUMMARY) ?: return false
    return ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
}
