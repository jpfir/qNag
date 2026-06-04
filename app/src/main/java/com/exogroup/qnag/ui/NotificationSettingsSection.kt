package com.exogroup.qnag.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.NotificationSettings

private const val MIN_INTERVAL = 15

/**
 * Notification settings controls.  Every change is emitted immediately via [onUpdate].
 *
 * @param notificationPermissionGranted Pass false on Android 13+ when POST_NOTIFICATIONS
 *   has not been granted, so a warning is shown below the master switch.
 */
@Composable
fun NotificationSettingsSection(
    settings: NotificationSettings,
    notificationPermissionGranted: Boolean = true,
    onUpdate: (NotificationSettings) -> Unit,
) {
    var intervalText by remember(settings.refreshIntervalMinutes) {
        mutableStateOf(settings.refreshIntervalMinutes.toString())
    }
    val intervalError: String? = intervalText.toIntOrNull().let { v ->
        when {
            v == null -> "Enter a whole number."
            v < MIN_INTERVAL -> "Minimum interval is $MIN_INTERVAL minutes."
            else -> null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

        // ── Master switch ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Enable notifications", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.notificationsEnabled,
                onCheckedChange = { onUpdate(settings.copy(notificationsEnabled = it)) },
            )
        }

        // Permission warning (Android 13+)
        if (settings.notificationsEnabled && !notificationPermissionGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Notification permission is not granted. " +
                            "Background notifications will not appear until permission is allowed in Android Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        AnimatedVisibility(visible = settings.notificationsEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                Spacer(Modifier.height(4.dp))
                NotifSubheader("Notify on")

                NotifRow("CRITICAL services", settings.notifyOnCriticalServices) {
                    onUpdate(settings.copy(notifyOnCriticalServices = it))
                }
                NotifRow("WARNING services", settings.notifyOnWarningServices) {
                    onUpdate(settings.copy(notifyOnWarningServices = it))
                }
                NotifRow("UNKNOWN services", settings.notifyOnUnknownServices) {
                    onUpdate(settings.copy(notifyOnUnknownServices = it))
                }
                NotifRow("DOWN hosts", settings.notifyOnDownHosts) {
                    onUpdate(settings.copy(notifyOnDownHosts = it))
                }
                NotifRow("UNREACHABLE hosts", settings.notifyOnUnreachableHosts) {
                    onUpdate(settings.copy(notifyOnUnreachableHosts = it))
                }

                Spacer(Modifier.height(4.dp))
                NotifSubheader("Filters")

                NotifRow("Only unacknowledged problems", settings.notifyOnlyUnacknowledged) {
                    onUpdate(settings.copy(notifyOnlyUnacknowledged = it))
                }
                NotifRow("Only hard state (ignore soft)", settings.notifyOnlyHardState) {
                    onUpdate(settings.copy(notifyOnlyHardState = it))
                }
                NotifRow("Respect scheduled downtime", settings.respectDowntime) {
                    onUpdate(settings.copy(respectDowntime = it))
                }

                Spacer(Modifier.height(8.dp))
                NotifSubheader("Refresh interval")

                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { raw ->
                        intervalText = raw
                        val v = raw.toIntOrNull()
                        if (v != null && v >= MIN_INTERVAL) {
                            onUpdate(settings.copy(refreshIntervalMinutes = v))
                        }
                    },
                    label = { Text("Interval (minutes)") },
                    singleLine = true,
                    isError = intervalError != null,
                    supportingText = intervalError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Android background polling with WorkManager has a minimum periodic interval of $MIN_INTERVAL minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotifSubheader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun NotifRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
