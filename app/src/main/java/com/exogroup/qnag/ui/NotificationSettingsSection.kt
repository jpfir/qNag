package com.exogroup.qnag.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NotificationMode
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.data.WearableNotifDetail
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.sound.AlertSoundPlayer

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
    val context = LocalContext.current
    var intervalText by remember(settings.refreshIntervalMinutes) {
        mutableStateOf(settings.refreshIntervalMinutes.toString())
    }
    var globalCooldownText by remember(settings.globalSoundCooldownSeconds) {
        mutableStateOf(settings.globalSoundCooldownSeconds.toString())
    }
    var perStateCooldownText by remember(settings.perStateSoundCooldownSeconds) {
        mutableStateOf(settings.perStateSoundCooldownSeconds.toString())
    }
    val intervalError: String? = intervalText.toIntOrNull().let { v ->
        when {
            v == null -> "Enter a whole number."
            v < MIN_INTERVAL -> "Minimum interval is $MIN_INTERVAL minutes."
            else -> null
        }
    }

    // Ringtone picker — must be registered at composable scope, not inside lambdas
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            onUpdate(settings.copy(inAppSoundUri = uri?.toString()))
        }
    }

    // Derive human-readable name for the current in-app sound
    val currentSoundLabel = remember(settings.inAppSoundUri) {
        if (settings.inAppSoundUri == null) {
            "qNag default alert"
        } else {
            try {
                RingtoneManager.getRingtone(context, Uri.parse(settings.inAppSoundUri))
                    ?.getTitle(context) ?: "Custom sound"
            } catch (_: Exception) { "Custom sound" }
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

                // ── qNag alert sound (in-app engine) — PRIMARY sound setting ──────────
                Spacer(Modifier.height(4.dp))
                NotifSubheader("qNag alert sound")
                Text(
                    "qNag uses a bundled short alert sound by default and stops it automatically. " +
                    "You can choose another sound, but qNag will stop it after the configured duration. " +
                    "This works independently of Android channel settings, which can be muted or disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                listOf(
                    AlertSoundMode.IN_APP_SOUND to "In-app sound (recommended)",
                    AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP to "In-app sound + DND guidance",
                    AlertSoundMode.NOTIFICATION_CHANNEL_ONLY to "Notification channel only",
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate(settings.copy(alertSoundMode = mode)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.alertSoundMode == mode,
                            onClick = { onUpdate(settings.copy(alertSoundMode = mode)) },
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Sound picker — shown when in-app mode is selected
                AnimatedVisibility(settings.alertSoundMode != AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Spacer(Modifier.height(4.dp))

                        // Current sound label
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Selected sound:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    currentSoundLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            OutlinedButton(onClick = {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "qNag alert sound")
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    settings.inAppSoundUri?.let {
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                                    }
                                }
                                ringtoneLauncher.launch(intent)
                            }) { Text("Choose sound") }
                        }

                        var maxDurationText by remember(settings.maxAlertSoundSeconds) {
                            mutableStateOf(settings.maxAlertSoundSeconds.toString())
                        }
                        OutlinedTextField(
                            value = maxDurationText,
                            onValueChange = { raw ->
                                maxDurationText = raw
                                raw.toIntOrNull()?.coerceIn(1, 60)?.let {
                                    onUpdate(settings.copy(maxAlertSoundSeconds = it))
                                }
                            },
                            label = { Text("Alert sound duration (seconds)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("qNag stops alert sounds automatically after this duration. Range: 1–60.") },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        NotifRow("Sound in vibrate mode (alarm stream)", settings.playSoundInVibrateMode) {
                            onUpdate(settings.copy(playSoundInVibrateMode = it))
                        }
                        NotifRow("Use alarm audio stream", settings.useAlarmAudioStream) {
                            onUpdate(settings.copy(useAlarmAudioStream = it))
                        }
                        Text(
                            "Alarm stream plays even in vibrate mode on most devices. " +
                            "Volume is controlled by system alarm volume.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // DND guidance
                        AnimatedVisibility(settings.alertSoundMode == AlertSoundMode.IN_APP_SOUND_WITH_DND_HELP) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val dndGranted = remember {
                                    val nm = context.getSystemService(android.app.NotificationManager::class.java)
                                    nm?.isNotificationPolicyAccessGranted == true
                                }
                                NotifSubheader("Do Not Disturb access")
                                if (dndGranted) {
                                    Text("DND policy access granted.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = okGreenColor())
                                } else {
                                    Text("Grant DND access so qNag sounds can bypass Do Not Disturb.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(
                                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Grant DND access") }
                                }
                                NotifRow("Help bypass Do Not Disturb", settings.helpBypassDnd) {
                                    onUpdate(settings.copy(helpBypassDnd = it))
                                }
                            }
                        }
                    }
                }

                // ── Notification mode ─────────────────────────────────────────────────
                Spacer(Modifier.height(4.dp))
                NotifSubheader("Notification display mode")
                Text(
                    "Compact summary shows one notification instead of one per alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                // GROUPED_DETAILS is not yet implemented; map it to SUMMARY_ONLY for display.
                val displayMode = if (settings.notificationMode == NotificationMode.GROUPED_DETAILS)
                    NotificationMode.SUMMARY_ONLY else settings.notificationMode
                listOf(
                    NotificationMode.SUMMARY_ONLY to "Compact summary (recommended)",
                    NotificationMode.PER_PROBLEM  to "Per problem (noisy)",
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate(settings.copy(notificationMode = mode)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = displayMode == mode,
                            onClick = { onUpdate(settings.copy(notificationMode = mode)) },
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }

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
                NotifRow("Respect Nagios notification disabled state", settings.respectNagiosNotificationsDisabled) {
                    onUpdate(settings.copy(respectNagiosNotificationsDisabled = it))
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

                Spacer(Modifier.height(8.dp))
                NotifSubheader("Sound & anti-flood")

                OutlinedTextField(
                    value = globalCooldownText,
                    onValueChange = { raw ->
                        globalCooldownText = raw
                        raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                            onUpdate(settings.copy(globalSoundCooldownSeconds = it))
                        }
                    },
                    label = { Text("Global sound cooldown (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("0 = no limit. Prevents sound storms across all states.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = perStateCooldownText,
                    onValueChange = { raw ->
                        perStateCooldownText = raw
                        raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                            onUpdate(settings.copy(perStateSoundCooldownSeconds = it))
                        }
                    },
                    label = { Text("Per-state sound cooldown (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("0 = no limit. Separate cooldown per state (CRITICAL, DOWN, etc.).") },
                    modifier = Modifier.fillMaxWidth(),
                )
                NotifRow("Re-sound for same problem on every poll", settings.repeatSameProblemSound) {
                    onUpdate(settings.copy(repeatSameProblemSound = it))
                }

                Spacer(Modifier.height(8.dp))
                NotifSubheader("Notification channel sounds")
                Text(
                    "After channels are created, sound and importance can only be changed in Android Settings. " +
                            "Tap a button below to open the channel settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Spacer(Modifier.height(4.dp))
                    val channelButtons = listOf(
                        "Host DOWN" to NotificationHelper.CHANNEL_HOST_DOWN,
                        "Host UNREACHABLE" to NotificationHelper.CHANNEL_HOST_UNREACHABLE,
                        "Service CRITICAL" to NotificationHelper.CHANNEL_SERVICE_CRITICAL,
                        "Service WARNING" to NotificationHelper.CHANNEL_SERVICE_WARNING,
                        "Service UNKNOWN" to NotificationHelper.CHANNEL_SERVICE_UNKNOWN,
                        "Connection failure" to NotificationHelper.CHANNEL_FETCH_FAIL,
                    )
                    channelButtons.forEach { (label, channelId) ->
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) { Text("$label — manage sound") }
                    }
                }

                // ── Tier 2+ notification delay ────────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                NotifSubheader("Tier 2+ notification delay")
                Text(
                    "Tier 2+ keeps alerts visible in qNag immediately, but delays Android alerting " +
                    "and sound until the problem has lasted long enough. Useful to avoid spurious " +
                    "alerts from short transient problems.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                NotifRow("Enable Tier 2+ delay mode", settings.tier2PlusEnabled) {
                    onUpdate(settings.copy(tier2PlusEnabled = it))
                }
                AnimatedVisibility(visible = settings.tier2PlusEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var tier2DelayText by remember(settings.tier2PlusDelayMinutes) {
                            mutableStateOf(settings.tier2PlusDelayMinutes.toString())
                        }
                        OutlinedTextField(
                            value = tier2DelayText,
                            onValueChange = { raw ->
                                tier2DelayText = raw
                                raw.toIntOrNull()?.takeIf { it >= 0 }?.let {
                                    onUpdate(settings.copy(tier2PlusDelayMinutes = it))
                                }
                            },
                            label = { Text("Delay all alerts (minutes)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("0 = no delay (same as disabled)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NotifRow("Use different delay per state", settings.tier2PlusUsePerStateDelays) {
                            onUpdate(settings.copy(tier2PlusUsePerStateDelays = it))
                        }
                        AnimatedVisibility(visible = settings.tier2PlusUsePerStateDelays) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    "Host DOWN (min)" to settings.tier2HostDownDelayMinutes to
                                        { v: Int -> settings.copy(tier2HostDownDelayMinutes = v) },
                                    "Host UNREACHABLE (min)" to settings.tier2HostUnreachableDelayMinutes to
                                        { v: Int -> settings.copy(tier2HostUnreachableDelayMinutes = v) },
                                    "Service CRITICAL (min)" to settings.tier2ServiceCriticalDelayMinutes to
                                        { v: Int -> settings.copy(tier2ServiceCriticalDelayMinutes = v) },
                                    "Service WARNING (min)" to settings.tier2ServiceWarningDelayMinutes to
                                        { v: Int -> settings.copy(tier2ServiceWarningDelayMinutes = v) },
                                    "Service UNKNOWN (min)" to settings.tier2ServiceUnknownDelayMinutes to
                                        { v: Int -> settings.copy(tier2ServiceUnknownDelayMinutes = v) },
                                ).forEach { (labelVal, copyFn) ->
                                    val (label, current) = labelVal
                                    var text by remember(current) { mutableStateOf(current.toString()) }
                                    OutlinedTextField(
                                        value = text,
                                        onValueChange = { raw ->
                                            text = raw
                                            raw.toIntOrNull()?.takeIf { it >= 0 }
                                                ?.let { onUpdate(copyFn(it)) }
                                        },
                                        label = { Text(label) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── ACKed alert re-notification ───────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                NotifSubheader("ACKed alert re-notification")
                Text(
                    "ACKed alerts are normally quiet. Enable to notify again if an ACKed alert " +
                    "remains active for too long — indicating the ACK may be stale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                NotifRow("Notify if ACKed for too long", settings.notifyAckedAfterEnabled) {
                    onUpdate(settings.copy(notifyAckedAfterEnabled = it))
                }
                AnimatedVisibility(visible = settings.notifyAckedAfterEnabled) {
                    var ackedText by remember(settings.notifyAckedAfterMinutes) {
                        mutableStateOf(settings.notifyAckedAfterMinutes.toString())
                    }
                    OutlinedTextField(
                        value = ackedText,
                        onValueChange = { raw ->
                            ackedText = raw
                            raw.toIntOrNull()?.takeIf { it > 0 }?.let {
                                onUpdate(settings.copy(notifyAckedAfterMinutes = it))
                            }
                        },
                        label = { Text("Notify again after ACKed for (minutes)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("Re-notification fires once when threshold is crossed.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── Wearable & lock screen ───────────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                NotifSubheader("Wearable & lock screen")
                Text(
                    "Controls what appears in Samsung Fit / wearable and lock screen notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("Wearable notification detail", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                listOf(
                    WearableNotifDetail.COMPACT_SUMMARY          to "Compact summary (counts only)",
                    WearableNotifDetail.TOP_PROBLEM_PLUS_SUMMARY to "Top problem + summary (recommended)",
                    WearableNotifDetail.TOP_PROBLEMS_LIST        to "Top problems list",
                ).forEach { (detail, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate(settings.copy(wearableNotifDetail = detail)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.wearableNotifDetail == detail,
                            onClick  = { onUpdate(settings.copy(wearableNotifDetail = detail)) },
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                NotifRow("Hide host/service details on lock screen & wearables", settings.hideDetailsOnLockScreen) {
                    onUpdate(settings.copy(hideDetailsOnLockScreen = it))
                }
                Text(
                    "Shows only problem counts — no host/service names in notification text. " +
                    "Failure alerts remain visible but sanitized.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── Android notification channel settings (secondary / channel-only mode) ───
                Spacer(Modifier.height(8.dp))
                NotifSubheader("Android notification channel settings")
                Text(
                    if (settings.alertSoundMode == AlertSoundMode.NOTIFICATION_CHANNEL_ONLY)
                        "Channel-only mode: Android channel settings control qNag's alert sound."
                    else
                        "These settings do NOT affect qNag's in-app alert sound. " +
                        "They only apply when using Notification channel only mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.alertSoundMode == AlertSoundMode.NOTIFICATION_CHANNEL_ONLY)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open app notification settings") }
                }
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

// Shared helper — used by NotificationSettingsSection and MonitoringHealthSection
@androidx.compose.runtime.Composable
fun okGreenColor() = androidx.compose.ui.graphics.Color(0xFF2E7D32)
