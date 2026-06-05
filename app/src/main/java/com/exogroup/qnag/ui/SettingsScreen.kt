package com.exogroup.qnag.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NotificationSettings

/**
 * Full-screen settings.  All changes are applied and persisted immediately.
 *
 * @param notificationPermissionGranted Pass the current Android notification permission state
 *   so the UI reflects the result immediately after the permission dialog is dismissed.
 *   Maintained as Compose state in MainActivity and updated in the activity result callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    instances: List<NagiosInstance>,
    filterSettings: FilterSettings,
    notificationSettings: NotificationSettings,
    commandSettings: CommandSettings,
    notificationPermissionGranted: Boolean,
    onUpdateInstances: (List<NagiosInstance>) -> Unit,
    onUpdateFilterSettings: (FilterSettings) -> Unit,
    onUpdateNotificationSettings: (NotificationSettings) -> Unit,
    onUpdateCommandSettings: (CommandSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {

            item { SettingsSectionHeader("Instances") }
            item {
                InstanceSettingsSection(
                    instances = instances,
                    onUpdate = onUpdateInstances,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item { SettingsSectionHeader("Filters") }
            item {
                FilterSettingsSection(
                    filters = filterSettings,
                    onUpdate = onUpdateFilterSettings,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item { SettingsSectionHeader("Notifications") }
            item {
                NotificationSettingsSection(
                    settings = notificationSettings,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onUpdate = onUpdateNotificationSettings,
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item { SettingsSectionHeader("Commands") }
            item {
                CommandSettingsSection(
                    settings = commandSettings,
                    onUpdate = onUpdateCommandSettings,
                )
            }

            if (commandSettings.showBatteryOptimizationHint) {
                item { Spacer(Modifier.height(8.dp)) }
                item { SettingsSectionHeader("Background & Battery") }
                item { BatteryOptimizationSection() }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SettingsSectionHeader("Monitoring Health") }
            item {
                MonitoringHealthSection(commandSettings = commandSettings)
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun BatteryOptimizationSection() {
    val context = LocalContext.current

    val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (context.getSystemService(PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Android may still delay background checks even with this setting. " +
                    "Allowing unrestricted battery usage improves reliability but does not guarantee " +
                    "exact polling intervals.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isIgnoring) {
            Text(
                "Battery optimization is already unrestricted for qNag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            OutlinedButton(
                onClick = {
                    // Prefer ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (no permission required,
                    // acceptable for general Play Store distribution).  Fall back to the direct
                    // request intent which requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and is
                    // more targeted but more scrutinised by app store review.
                    val launched = runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }.isSuccess
                    if (!launched) runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Battery optimization settings")
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}
