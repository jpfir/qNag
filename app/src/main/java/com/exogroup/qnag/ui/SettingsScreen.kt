package com.exogroup.qnag.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.R
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NotificationSettings

// ── Settings navigation destinations ─────────────────────────────────────────

private const val NAV_HOME             = "home"
private const val NAV_INSTANCES        = "instances"
private const val NAV_MONITORING       = "monitoring"
private const val NAV_NOTIFICATIONS    = "notifications"
private const val NAV_FILTERS          = "filters"
private const val NAV_COMMANDS         = "commands"
private const val NAV_RELIABILITY      = "reliability"
private const val NAV_IMPORT_EXPORT    = "import_export"
private const val NAV_ABOUT            = "about"
private const val NAV_EVENT_LOG        = "event_log"
private const val NAV_COMMAND_ACTIVITY = "command_activity"

// ── Search index ──────────────────────────────────────────────────────────────

private data class SettingsItem(
    val title: String,
    val subtitle: String,
    val destination: String,
    val keywords: List<String>,
)

private val SETTINGS_INDEX = listOf(
    // ── Instances ─────────────────────────────────────────────────────────────
    SettingsItem("Instances", "Instances", NAV_INSTANCES,
        listOf("instance", "nagios", "url", "server", "add", "edit", "remove", "enable", "disable")),

    // ── Monitoring & Refresh ──────────────────────────────────────────────────
    SettingsItem("Keep monitoring active", "Monitoring & Refresh", NAV_MONITORING,
        listOf("monitoring", "active", "reliability", "foreground", "enable", "keep")),
    SettingsItem("WorkManager refresh interval", "Monitoring & Refresh", NAV_MONITORING,
        listOf("workmanager", "background", "15 minutes", "interval", "refresh", "poll", "frequency")),
    SettingsItem("Stale monitoring alert", "Monitoring & Refresh", NAV_MONITORING,
        listOf("stale", "threshold", "no poll", "alert", "overdue", "monitoring", "missed")),
    SettingsItem("Notify on polling failure", "Monitoring & Refresh", NAV_MONITORING,
        listOf("poll", "failure", "notify", "connection", "error", "fetch")),
    SettingsItem("Background polling behavior", "Monitoring & Refresh", NAV_MONITORING,
        listOf("background", "polling", "new problems", "repeat", "suppress", "notify only new")),

    // ── Notifications & Sound ─────────────────────────────────────────────────
    SettingsItem("Enable notifications", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("enable", "notifications", "disable", "permission")),
    SettingsItem("Notification mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("mode", "summary", "per problem", "grouped", "notification")),
    SettingsItem("Alert sound mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("sound", "in-app", "alarm", "channel", "alert", "ring", "ringtone")),
    SettingsItem("Sound in vibrate mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("vibrate", "vibration", "silent", "sound", "ringer")),
    SettingsItem("DND / Do Not Disturb", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("dnd", "do not disturb", "bypass", "override", "policy")),
    SettingsItem("Sound cooldown / anti-flood", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("cooldown", "repeat", "sound", "flood", "throttle", "anti-flood", "storm")),
    SettingsItem("Re-sound active alerts every refresh", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("resound", "re-sound", "sound", "repeat", "every poll", "every refresh",
               "foreground", "app open", "wearable", "watch", "pulse")),
    SettingsItem("Notification channel sound settings", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("channel", "muted", "disabled", "importance", "health", "android",
               "wearable", "watch", "wear", "smartwatch", "galaxy watch", "galaxy fit", "samsung")),
    SettingsItem("Respect Nagios notification disabled state", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("nagios", "notif off", "notiff", "notification disabled", "respect", "notif disabled",
               "nagios notif", "notification off")),
    SettingsItem("Tier 2 alert delay", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("tier 2", "tier2", "delay", "transient", "spurious", "duration")),
    SettingsItem("ACKed alert re-notification", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("acked", "re-notify", "stale ack", "old ack", "remind")),

    // ── Commands ──────────────────────────────────────────────────────────────
    SettingsItem("Default ACK message", "Commands", NAV_COMMANDS,
        listOf("ack", "acknowledge", "message", "default", "comment")),
    SettingsItem("ACK author", "Commands", NAV_COMMANDS,
        listOf("ack", "author", "name", "acknowledge")),
    SettingsItem("ACK services on host ACK", "Commands", NAV_COMMANDS,
        listOf("ack", "host", "services", "cascade", "acknowledge")),
    SettingsItem("ACK sticky / notify / persistent", "Commands", NAV_COMMANDS,
        listOf("ack", "sticky", "notify", "persistent", "acknowledge")),
    SettingsItem("Recheck date format", "Commands", NAV_COMMANDS,
        listOf("recheck", "date", "format", "iso", "us", "euro", "start_time", "nagios date")),
    SettingsItem("Command debug logging", "Commands", NAV_COMMANDS,
        listOf("debug", "log", "command", "ack", "recheck", "diagnose", "logcat")),

    // ── Reliability ───────────────────────────────────────────────────────────
    SettingsItem("Foreground service", "Reliability", NAV_RELIABILITY,
        listOf("foreground", "service", "polling", "interval", "running")),
    SettingsItem("Exact Alarm Watchdog", "Reliability", NAV_RELIABILITY,
        listOf("exact", "alarm", "watchdog", "recovery", "schedule", "permission")),
    SettingsItem("Battery optimization", "Reliability", NAV_RELIABILITY,
        listOf("battery", "optimization", "doze", "unrestricted", "background", "battery saver")),
    SettingsItem("Monitoring health & recovery", "Reliability", NAV_RELIABILITY,
        listOf("health", "poll", "last", "service", "running", "status", "recovery", "restart",
               "workmanager", "watchdog")),
    SettingsItem("Wearable / Samsung Galaxy Watch notification setup", "Reliability", NAV_RELIABILITY,
        listOf("wearable", "watch", "smartwatch", "galaxy watch", "wear", "galaxy fit", "samsung",
               "samsung wearable", "test notification", "wear os")),

    // ── Filters ───────────────────────────────────────────────────────────────
    SettingsItem("Hide acknowledged", "Filters & Display", NAV_FILTERS,
        listOf("acknowledged", "ack", "hide", "filter")),
    SettingsItem("Soft state / hard state filter", "Filters & Display", NAV_FILTERS,
        listOf("soft", "hard", "state", "filter")),
    SettingsItem("Downtime filter", "Filters & Display", NAV_FILTERS,
        listOf("downtime", "scheduled", "maintenance", "filter")),
    SettingsItem("Regex filter rules", "Filters & Display", NAV_FILTERS,
        listOf("regex", "regexp", "regular expression", "filter", "include", "exclude", "reverse",
               "hide matching", "show matching", "qnagstamon", "pattern", "host", "service", "plugin output",
               "host filter", "service filter", "status info", "regex host", "regex service", "field", "any field")),

    // ── Import / Export ───────────────────────────────────────────────────────
    SettingsItem("Import instances", "Import / Export", NAV_IMPORT_EXPORT,
        listOf("import", "restore", "backup", "json", "instances", "file")),
    SettingsItem("Export instances", "Import / Export", NAV_IMPORT_EXPORT,
        listOf("export", "backup", "save", "json", "instances", "file")),

    // ── Command Activity ──────────────────────────────────────────────────────
    SettingsItem("Command Activity", "Command Activity", NAV_COMMAND_ACTIVITY,
        listOf("command", "activity", "ack", "recheck", "downtime", "history", "status", "running", "failed")),

    // ── Event Log ─────────────────────────────────────────────────────────────
    SettingsItem("Event Log", "Event Log", NAV_EVENT_LOG,
        listOf("log", "event", "debug", "troubleshoot", "history", "polling", "watchdog", "events")),

    // ── About ─────────────────────────────────────────────────────────────────
    SettingsItem("About qNag", "About", NAV_ABOUT,
        listOf("about", "version", "help", "readme", "reliability", "explanation", "info")),
)

// ── Main SettingsScreen entry point ──────────────────────────────────────────

/**
 * Full-screen settings with submenu navigation and global search.
 * External API is unchanged; navigation is internal Compose state.
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
    onImportInstances: (() -> Unit)? = null,
    onExportInstances: (() -> Unit)? = null,
    initialNav: String = NAV_HOME,
) {
    var nav by rememberSaveable { mutableStateOf(initialNav) }

    BackHandler {
        if (nav != NAV_HOME) nav = NAV_HOME else onBack()
    }

    when (nav) {
        NAV_HOME -> SettingsHome(
            onNavigate = { nav = it },
            onBack = onBack,
        )
        NAV_INSTANCES -> SettingsSubScreen("Instances", onBack = { nav = NAV_HOME }) {
            InstanceSettingsSection(
                instances = instances,
                onUpdate = onUpdateInstances,
                onImportInstances = onImportInstances,
                onExportInstances = onExportInstances,
            )
        }
        NAV_MONITORING -> SettingsSubScreen("Monitoring & Refresh", onBack = { nav = NAV_HOME }) {
            MonitoringRefreshPage(
                commandSettings = commandSettings,
                notificationSettings = notificationSettings,
                onUpdateCommandSettings = onUpdateCommandSettings,
                onUpdateNotificationSettings = onUpdateNotificationSettings,
            )
        }
        NAV_NOTIFICATIONS -> SettingsSubScreen("Notifications & Sound", onBack = { nav = NAV_HOME }) {
            NotificationSettingsSection(
                settings = notificationSettings,
                notificationPermissionGranted = notificationPermissionGranted,
                onUpdate = onUpdateNotificationSettings,
            )
        }
        NAV_FILTERS -> SettingsSubScreen("Filters & Display", onBack = { nav = NAV_HOME }) {
            FilterSettingsSection(filters = filterSettings, onUpdate = onUpdateFilterSettings)
        }
        NAV_COMMANDS -> SettingsSubScreen("Commands", onBack = { nav = NAV_HOME }) {
            CommandSettingsSection(
                settings = commandSettings,
                onUpdate = onUpdateCommandSettings,
                showCommandsOnly = true,
            )
        }
        NAV_RELIABILITY -> SettingsSubScreen("Reliability", onBack = { nav = NAV_HOME }) {
            ReliabilityPage(
                commandSettings = commandSettings,
                notificationSettings = notificationSettings,
                onUpdateCommandSettings = onUpdateCommandSettings,
            )
        }
        NAV_IMPORT_EXPORT -> SettingsSubScreen("Import / Export", onBack = { nav = NAV_HOME }) {
            ImportExportPage(
                onImportInstances = onImportInstances,
                onExportInstances = onExportInstances,
            )
        }
        NAV_ABOUT -> SettingsSubScreen("About qNag", onBack = { nav = NAV_HOME }) {
            AboutPage()
        }
        NAV_EVENT_LOG -> EventLogScreen(onBack = { nav = NAV_HOME })
        NAV_COMMAND_ACTIVITY -> CommandActivityScreen(onBack = { nav = NAV_HOME })
    }
}

// ── Settings home ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredItems = remember(searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val q = searchQuery.lowercase().trim()
            SETTINGS_INDEX.filter { item ->
                item.title.lowercase().contains(q) ||
                item.subtitle.lowercase().contains(q) ||
                item.keywords.any { it.lowercase().contains(q) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Search field
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search settings") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (searchQuery.isNotBlank()) {
                // Search results
                if (filteredItems.isEmpty()) {
                    item {
                        Text(
                            "No settings found for \"$searchQuery\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    filteredItems.forEach { item ->
                        item(key = item.title + item.destination) {
                            SearchResultRow(item = item, onClick = { onNavigate(item.destination) })
                        }
                    }
                }
            } else {
                // Category cards — ordered per the v1.0.6 proposed structure
                item { SettingsNavItem("Instances",
                    "Add, edit, and configure Nagios instances",
                    NAV_INSTANCES, onNavigate) }
                item { SettingsNavItem("Monitoring & Refresh",
                    "Active monitoring, refresh interval, polling behavior",
                    NAV_MONITORING, onNavigate) }
                item { SettingsNavItem("Notifications & Sound",
                    "Alerts, in-app sound, wearable, DND, channel health",
                    NAV_NOTIFICATIONS, onNavigate) }
                item { SettingsNavItem("Commands",
                    "ACK defaults, recheck date format, debug logging",
                    NAV_COMMANDS, onNavigate) }
                item { SettingsNavItem("Reliability",
                    "Foreground service, watchdog, battery, monitoring health",
                    NAV_RELIABILITY, onNavigate) }
                item { SettingsNavItem("Filters & Display",
                    "Dashboard visibility filters and regex rules",
                    NAV_FILTERS, onNavigate) }
                item { SettingsNavItem("Import / Export",
                    "Import and export instance configuration",
                    NAV_IMPORT_EXPORT, onNavigate) }
                item { SettingsNavItem("Command Activity",
                    "Recent ACK, recheck and downtime commands",
                    NAV_COMMAND_ACTIVITY, onNavigate) }
                item { SettingsNavItem("About",
                    "App version and info",
                    NAV_ABOUT, onNavigate) }
                item { SettingsNavItem("Event Log",
                    "In-app log of polling, commands, and reliability events",
                    NAV_EVENT_LOG, onNavigate) }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun SettingsNavItem(
    title: String,
    subtitle: String,
    destination: String,
    onNavigate: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onNavigate(destination) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchResultRow(item: SettingsItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = { Text(item.subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

// ── Sub-screen wrapper ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { content() }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Monitoring & Refresh sub-screen ──────────────────────────────────────────

@Composable
private fun MonitoringRefreshPage(
    commandSettings: CommandSettings,
    notificationSettings: NotificationSettings,
    onUpdateCommandSettings: (CommandSettings) -> Unit,
    onUpdateNotificationSettings: (NotificationSettings) -> Unit,
) {
    var intervalText by remember(notificationSettings.refreshIntervalMinutes) {
        mutableStateOf(notificationSettings.refreshIntervalMinutes.toString())
    }
    val intervalError: String? = intervalText.toIntOrNull().let { v ->
        when {
            v == null -> "Enter a whole number."
            v < 15   -> "Minimum interval is 15 minutes."
            else     -> null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        SectionHeader("Active monitoring")
        CmdRow(
            label = "Enable reliability mode (foreground service)",
            checked = commandSettings.keepMonitoringActive,
            onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(keepMonitoringActive = it)) },
        )
        Text(
            "Keeps qNag actively polling with a foreground service and persistent notification. " +
            "Improves reliability for on-call use. Configure the polling interval and watchdog " +
            "in Reliability settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("WorkManager refresh interval")
        OutlinedTextField(
            value = intervalText,
            onValueChange = { raw ->
                intervalText = raw
                val v = raw.toIntOrNull()
                if (v != null && v >= 15) {
                    onUpdateNotificationSettings(notificationSettings.copy(refreshIntervalMinutes = v))
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
            "Android WorkManager background polling has a minimum periodic interval of 15 minutes. " +
            "When Reliability mode is active, the foreground service polls more frequently.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Stale monitoring alert")
        CmdRow(
            label = "Alert if monitoring goes stale",
            checked = commandSettings.staleMonitoringAlertEnabled,
            onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(staleMonitoringAlertEnabled = it)) },
        )
        Text(
            "Shows a notification if no poll succeeded within the stale threshold.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = commandSettings.staleMonitoringAlertEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Stale threshold: ${commandSettings.monitoringStaleThresholdMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 5, 10, 15, 30).forEach { minutes ->
                        FilterChip(
                            selected = commandSettings.monitoringStaleThresholdMinutes == minutes,
                            onClick = { onUpdateCommandSettings(commandSettings.copy(monitoringStaleThresholdMinutes = minutes)) },
                            label = { Text("${minutes}m") },
                        )
                    }
                }
            }
        }

        SectionHeader("Background polling behavior")
        CmdRow(
            label = "Notify on fetch/polling failure",
            checked = commandSettings.notifyOnFetchFailure,
            onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(notifyOnFetchFailure = it)) },
        )
        CmdRow(
            label = "Notify only new problems (suppress repeats)",
            checked = commandSettings.notifyOnlyNewProblems,
            onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(notifyOnlyNewProblems = it)) },
        )
        CmdRow(
            label = "Show battery optimization hint",
            checked = commandSettings.showBatteryOptimizationHint,
            onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(showBatteryOptimizationHint = it)) },
        )
    }
}

// ── Reliability sub-screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReliabilityPage(
    commandSettings: CommandSettings,
    notificationSettings: NotificationSettings,
    onUpdateCommandSettings: (CommandSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        SectionHeader("Foreground service")
        if (!commandSettings.keepMonitoringActive) {
            Text(
                "Reliability mode is disabled. Enable it in Monitoring & Refresh to configure " +
                "the foreground polling interval and watchdog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CmdSubheader("Foreground polling interval")
            ForegroundIntervalPicker(
                currentSeconds = commandSettings.foregroundPollingIntervalSeconds,
                onSelect = { onUpdateCommandSettings(commandSettings.copy(foregroundPollingIntervalSeconds = it)) },
            )
            Spacer(Modifier.height(4.dp))
            CmdSubheader("Exact Alarm Watchdog")
            CmdRow(
                label = "Enable watchdog recovery alarm",
                checked = commandSettings.exactAlarmWatchdogEnabled,
                onCheckedChange = { onUpdateCommandSettings(commandSettings.copy(exactAlarmWatchdogEnabled = it)) },
            )
            AnimatedVisibility(visible = commandSettings.exactAlarmWatchdogEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Exact Alarm Watchdog fires periodically to check whether Reliability Mode " +
                        "is still healthy. If the service has stopped or gone stale, it attempts " +
                        "recovery. Android 12+ requires exact alarm permission " +
                        "(Settings → Special app access → Alarms & reminders).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WatchdogIntervalPicker(
                        currentMinutes = commandSettings.exactAlarmWatchdogIntervalMinutes,
                        onSelect = { onUpdateCommandSettings(commandSettings.copy(exactAlarmWatchdogIntervalMinutes = it)) },
                    )
                }
            }
        }

        SectionHeader("Battery & Background")
        BatteryOptimizationSection()

        SectionHeader("Monitoring Health")
        MonitoringHealthSection(
            commandSettings = commandSettings,
            notificationSettings = notificationSettings,
        )
    }
}

// ── Import / Export sub-screen ────────────────────────────────────────────────

@Composable
private fun ImportExportPage(
    onImportInstances: (() -> Unit)?,
    onExportInstances: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Instance configuration")
        Text(
            "Import and export your Nagios instance configuration. " +
            "Passwords are not included in exports.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onImportInstances?.invoke() },
                enabled = onImportInstances != null,
                modifier = Modifier.weight(1f),
            ) { Text("Import instances") }
            OutlinedButton(
                onClick = { onExportInstances?.invoke() },
                enabled = onExportInstances != null,
                modifier = Modifier.weight(1f),
            ) { Text("Export instances") }
        }
    }
}

// ── About sub-screen ──────────────────────────────────────────────────────────

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("unknown")
    }

    var logoTapCount by remember { mutableIntStateOf(0) }
    var showEasterEgg by remember { mutableStateOf(false) }
    var coffeeAcked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "qNag logo") {
                    logoTapCount += 1
                    if (logoTapCount >= 7) showEasterEgg = true
                },
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "qNag logo",
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "qNag",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Version $versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Reliability Mode uses a foreground service with a persistent status notification " +
                "for phone-side Nagios monitoring.  qNag produces alert sounds via its own " +
                "in-app audio engine, independent of notification-channel settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "⚠ Android or vendor battery policies may still limit background execution. " +
                "The Monitoring Health section shows warnings when issues are detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "qNag is designed to be robust for on-call use but should be one alerting path " +
                "among others in critical environments.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showEasterEgg) {
                Spacer(Modifier.height(4.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Duck mode unlocked 🦆",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$ ./qnag --status",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        EasterEggStatusLine("Monitoring brain", "OK",   MaterialTheme.colorScheme.primary)
                        EasterEggStatusLine("Coffee level",     "WARNING", MaterialTheme.colorScheme.error)
                        EasterEggStatusLine("Alert fatigue",    "CRITICAL", MaterialTheme.colorScheme.error)
                        EasterEggStatusLine("Rubber duck",      "listening", MaterialTheme.colorScheme.onSurface)
                        if (coffeeAcked) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "ACK submitted by qNag: \"Needs more coffee.\"",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { coffeeAcked = true },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    "Acknowledge coffee warning",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EasterEggStatusLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = valueColor,
        )
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun BatteryOptimizationSection() {
    val context = LocalContext.current
    val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (context.getSystemService(PowerManager::class.java))
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Android may still delay background checks even with this setting. " +
            "Allowing unrestricted battery usage improves reliability.",
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
            ) { Text("Battery optimization settings") }
        }
    }
}
