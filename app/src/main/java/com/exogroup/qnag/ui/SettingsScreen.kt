package com.exogroup.qnag.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.exogroup.qnag.R
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NotificationSettings

// ── Settings navigation destinations ─────────────────────────────────────────

private const val NAV_HOME         = "home"
private const val NAV_INSTANCES    = "instances"
private const val NAV_MONITORING   = "monitoring"
private const val NAV_NOTIFICATIONS= "notifications"
private const val NAV_FILTERS      = "filters"
private const val NAV_COMMANDS     = "commands"
private const val NAV_ABOUT        = "about"
private const val NAV_EVENT_LOG    = "event_log"

// ── Search index ──────────────────────────────────────────────────────────────

private data class SettingsItem(
    val title: String,
    val subtitle: String,
    val destination: String,
    val keywords: List<String>,
)

private val SETTINGS_INDEX = listOf(
    // Instances
    SettingsItem("Instances", "Monitoring & Reliability", NAV_INSTANCES,
        listOf("instance", "nagios", "url", "server", "add", "edit", "remove", "enable", "disable")),
    // Monitoring
    SettingsItem("Reliability Mode", "Monitoring & Reliability", NAV_MONITORING,
        listOf("reliability", "foreground", "service", "polling", "keepMonitoringActive", "interval")),
    SettingsItem("WorkManager interval", "Monitoring & Reliability", NAV_MONITORING,
        listOf("workmanager", "background", "15 minutes", "interval", "refresh")),
    SettingsItem("Battery optimization", "Monitoring & Reliability", NAV_MONITORING,
        listOf("battery", "optimization", "doze", "unrestricted")),
    SettingsItem("Monitoring health", "Monitoring & Reliability", NAV_MONITORING,
        listOf("health", "stale", "poll", "last", "foreground", "service", "running")),
    SettingsItem("Stale monitoring alert", "Monitoring & Reliability", NAV_MONITORING,
        listOf("stale", "threshold", "no poll", "alert", "monitoring")),
    // Notifications
    SettingsItem("Enable notifications", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("enable", "notifications", "disable", "permission")),
    SettingsItem("Notification mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("mode", "summary", "per problem", "grouped", "notification")),
    SettingsItem("Alert sound mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("sound", "in-app", "alarm", "channel", "alert")),
    SettingsItem("Sound in vibrate mode", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("vibrate", "vibration", "silent", "sound", "ringer")),
    SettingsItem("DND / Do Not Disturb", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("dnd", "do not disturb", "bypass", "override", "policy")),
    SettingsItem("Sound cooldown", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("cooldown", "repeat", "sound", "flood", "throttle")),
    SettingsItem("Notification channel health", "Notifications & Sound", NAV_NOTIFICATIONS,
        listOf("channel", "muted", "disabled", "importance", "health")),
    // Filters
    SettingsItem("Hide acknowledged", "Filters", NAV_FILTERS,
        listOf("acknowledged", "ack", "hide", "filter")),
    SettingsItem("Soft state / hard state", "Filters", NAV_FILTERS,
        listOf("soft", "hard", "state", "filter")),
    SettingsItem("Downtime filter", "Filters", NAV_FILTERS,
        listOf("downtime", "scheduled", "maintenance", "filter")),
    SettingsItem("Host regex filter", "Filters", NAV_FILTERS,
        listOf("host", "regex", "filter", "pattern")),
    SettingsItem("Service regex filter", "Filters", NAV_FILTERS,
        listOf("service", "regex", "filter", "pattern")),
    SettingsItem("Status info regex", "Filters", NAV_FILTERS,
        listOf("status", "info", "regex", "plugin", "output", "filter")),
    // Commands
    SettingsItem("Default ACK message", "Commands", NAV_COMMANDS,
        listOf("ack", "acknowledge", "message", "default", "comment")),
    SettingsItem("ACK author", "Commands", NAV_COMMANDS,
        listOf("ack", "author", "name", "acknowledge")),
    SettingsItem("ACK services on host ACK", "Commands", NAV_COMMANDS,
        listOf("ack", "host", "services", "cascade", "acknowledge")),
    SettingsItem("ACK sticky / notify / persistent", "Commands", NAV_COMMANDS,
        listOf("ack", "sticky", "notify", "persistent", "acknowledge")),
    SettingsItem("Recheck date format", "Commands", NAV_COMMANDS,
        listOf("recheck", "date", "format", "iso", "us", "euro", "start_time")),
    SettingsItem("Command debug logging", "Commands", NAV_COMMANDS,
        listOf("debug", "log", "command", "ack", "recheck", "diagnose")),
    // About
    SettingsItem("About qNag", "About", NAV_ABOUT,
        listOf("about", "version", "help", "readme", "reliability", "explanation")),
    // Event Log
    SettingsItem("Event Log", "About", NAV_EVENT_LOG,
        listOf("log", "event", "debug", "troubleshoot", "history", "polling", "watchdog")),
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
) {
    var nav by rememberSaveable { mutableStateOf(NAV_HOME) }

    BackHandler {
        if (nav != NAV_HOME) nav = NAV_HOME else onBack()
    }

    when (nav) {
        NAV_HOME -> SettingsHome(
            commandSettings = commandSettings,
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
        NAV_MONITORING -> SettingsSubScreen("Monitoring & Reliability", onBack = { nav = NAV_HOME }) {
            MonitoringReliabilityPage(
                commandSettings = commandSettings,
                onUpdateCommandSettings = onUpdateCommandSettings,
            )
        }
        NAV_NOTIFICATIONS -> SettingsSubScreen("Notifications & Sound", onBack = { nav = NAV_HOME }) {
            NotificationSettingsSection(
                settings = notificationSettings,
                notificationPermissionGranted = notificationPermissionGranted,
                onUpdate = onUpdateNotificationSettings,
            )
        }
        NAV_FILTERS -> SettingsSubScreen("Filters", onBack = { nav = NAV_HOME }) {
            FilterSettingsSection(filters = filterSettings, onUpdate = onUpdateFilterSettings)
        }
        NAV_COMMANDS -> SettingsSubScreen("Commands", onBack = { nav = NAV_HOME }) {
            CommandSettingsSection(settings = commandSettings, onUpdate = onUpdateCommandSettings)
        }
        NAV_ABOUT -> SettingsSubScreen("About qNag", onBack = { nav = NAV_HOME }) {
            AboutPage()
        }
        NAV_EVENT_LOG -> EventLogScreen(onBack = { nav = NAV_HOME })
    }
}

// ── Settings home ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    commandSettings: CommandSettings,
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
                item.keywords.any { it.contains(q) }
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
                // Category cards
                item { SettingsNavItem("Instances", "Add, edit, and configure Nagios instances", NAV_INSTANCES, onNavigate) }
                item { SettingsNavItem("Monitoring & Reliability", "Reliability mode, foreground polling, health, stale alerts", NAV_MONITORING, onNavigate) }
                item { SettingsNavItem("Notifications & Sound", "Alerts, in-app sound, DND, channel health", NAV_NOTIFICATIONS, onNavigate) }
                item { SettingsNavItem("Filters", "Dashboard visibility filters and regex rules", NAV_FILTERS, onNavigate) }
                item { SettingsNavItem("Commands", "ACK and recheck command behaviour", NAV_COMMANDS, onNavigate) }
                item { SettingsNavItem("About", "App version and reliability explanation", NAV_ABOUT, onNavigate) }
                item { SettingsNavItem("Event Log", "In-app log of polling, commands, and reliability events", NAV_EVENT_LOG, onNavigate) }
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

// ── Monitoring & Reliability sub-screen ──────────────────────────────────────

@Composable
private fun MonitoringReliabilityPage(
    commandSettings: CommandSettings,
    onUpdateCommandSettings: (CommandSettings) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Reliability mode + foreground settings from CommandSettingsSection
        // (Reuse the subsections inline)
        SectionHeader("Reliability Mode")
        CommandSettingsSection(
            settings = commandSettings,
            onUpdate = onUpdateCommandSettings,
            showOnlyReliability = true,
        )

        SectionHeader("Battery & Background")
        BatteryOptimizationSection()

        SectionHeader("Monitoring Health")
        MonitoringHealthSection(commandSettings = commandSettings)
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
