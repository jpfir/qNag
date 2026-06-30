package com.exogroup.qnag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.exogroup.qnag.data.AppSettings
import com.exogroup.qnag.data.EventLog
import com.exogroup.qnag.data.ImportParseResult
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.UserMonitoringPause
import com.exogroup.qnag.data.applyImport
import com.exogroup.qnag.data.exportInstancesToJson
import com.exogroup.qnag.data.parseImportJson
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.reliability.ExactAlarmWatchdogScheduler
import com.exogroup.qnag.service.NagiosMonitoringService
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.ui.AddInstanceScreen
import com.exogroup.qnag.ui.CommandActivityScreen
import com.exogroup.qnag.ui.DashboardScreen
import com.exogroup.qnag.ui.ExportInstancesDialog
import com.exogroup.qnag.ui.ImportPreviewDialog
import com.exogroup.qnag.ui.ProblemDetailScreen
import com.exogroup.qnag.ui.ReliabilityChecklistScreen
import com.exogroup.qnag.ui.SettingsDestination
import com.exogroup.qnag.ui.SettingsScreen
import com.exogroup.qnag.ui.WelcomeScreen
import com.exogroup.qnag.widget.WidgetRefresher
import com.exogroup.qnag.worker.BackgroundPollingScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private sealed class AppScreen {
    object Welcome : AppScreen()
    object AddInstance : AppScreen()
    data class ReliabilityChecklist(val dashboardInstance: NagiosInstance) : AppScreen()
    data class Dashboard(val instance: NagiosInstance) : AppScreen()
    data class Settings(
        val fromInstance: NagiosInstance,
        val initialDestination: SettingsDestination = SettingsDestination.HOME,
    ) : AppScreen()
    data class ProblemDetail(
        val problem: NagiosProblem,
        val instance: NagiosInstance?,
        /** The Dashboard instance that was active — used to restore the correct scope on back. */
        val fromDashboardInstance: NagiosInstance,
    ) : AppScreen()
    /** Command Activity opened from the Dashboard running-commands banner. */
    data class CommandActivity(val fromDashboardInstance: NagiosInstance) : AppScreen()
}

private data class PendingExportConfig(
    val instances: List<NagiosInstance>,
    val appSettings: AppSettings,
    val includePasswords: Boolean,
)

class MainActivity : ComponentActivity() {

    // Compose-state holder for notification permission — updated in the result callback
    // so SettingsScreen immediately reflects the new grant/deny state.
    private var notifPermissionGranted = mutableStateOf(false)

    // Compose-state holder for user-initiated monitoring pause — updated in onResume and
    // by the stop/resume actions so the Dashboard banner reacts without recomposing the whole tree.
    private var isMonitoringPaused = mutableStateOf(false)

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            notifPermissionGranted.value = isGranted
        }

    // ── Import/export state ───────────────────────────────────────────────────
    // Observable from Compose — set by launcher callbacks, cleared by dialog handlers.

    private val pendingImportJson = mutableStateOf<String?>(null)
    private val importFileError   = mutableStateOf<String?>(null)
    private var pendingExportConfig: PendingExportConfig? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (json.isNullOrBlank()) importFileError.value = "Selected file is empty."
            else pendingImportJson.value = json
        } catch (_: Exception) {
            importFileError.value = "Could not read file."
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) { pendingExportConfig = null; return@registerForActivityResult }
        val config = pendingExportConfig
        pendingExportConfig = null
        if (config == null) return@registerForActivityResult
        try {
            val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val json = exportInstancesToJson(
                instances = config.instances,
                filterSettings = config.appSettings.filterSettings,
                notificationSettings = config.appSettings.notificationSettings,
                commandSettings = config.appSettings.commandSettings,
                includePasswords = config.includePasswords,
                nowUtcIso = nowUtc,
            )
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
            Toast.makeText(
                this,
                "Exported ${config.instances.size} instance(s).",
                Toast.LENGTH_SHORT,
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Export failed.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannels(this)
        notifPermissionGranted.value = NotificationHelper.hasPermission(this)
        isMonitoringPaused.value = UserMonitoringPause.isPaused(this)
        // Auto-restart on cold start (also covers package replace / force-stop recovery)
        autoRestartIfEligible()

        val store = SecureInstanceStore(this)

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    var instances by remember { mutableStateOf(store.getInstances()) }
                    var appSettings by remember { mutableStateOf(store.getAppSettings()) }
                    val notifPermGranted by notifPermissionGranted    // observe the Activity-level state
                    val monitoringPaused by isMonitoringPaused        // observe the Activity-level state

                    // ── Import/export ──────────────────────────────────────────
                    val pendingJson  by pendingImportJson   // observe Activity-level state
                    val fileError    by importFileError
                    var exportDialogOpen by remember { mutableStateOf(false) }

                    // Parse import JSON reactively (memoised on the JSON + existing instances)
                    val parseResult = remember(pendingJson, instances) {
                        if (pendingJson != null) parseImportJson(pendingJson!!, instances) else null
                    }

                    if (parseResult is ImportParseResult.Success) {
                        ImportPreviewDialog(
                            preview = parseResult.preview,
                            onImport = {
                                val merged = applyImport(instances, parseResult.preview)
                                store.saveInstances(merged)
                                instances = merged
                                applyPollingMode(appSettings, merged)
                                WidgetRefresher.onInstancesChanged(applicationContext)
                                pendingImportJson.value = null
                                Toast.makeText(
                                    this@MainActivity,
                                    "Imported: ${parseResult.preview.toAdd.size} added, " +
                                    "${parseResult.preview.toUpdate.size} updated.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onDismiss = { pendingImportJson.value = null },
                        )
                    }

                    if (parseResult is ImportParseResult.Failure) {
                        AlertDialog(
                            onDismissRequest = { pendingImportJson.value = null },
                            title = { Text("Import Error") },
                            text = { Text(parseResult.error) },
                            confirmButton = {
                                TextButton(onClick = { pendingImportJson.value = null }) { Text("OK") }
                            },
                        )
                    }

                    if (fileError != null) {
                        AlertDialog(
                            onDismissRequest = { importFileError.value = null },
                            title = { Text("Import Error") },
                            text = { Text(fileError!!) },
                            confirmButton = {
                                TextButton(onClick = { importFileError.value = null }) { Text("OK") }
                            },
                        )
                    }

                    if (exportDialogOpen && instances.isNotEmpty()) {
                        ExportInstancesDialog(
                            instances = instances,
                            onExport = { selectedInstances, includePasswords ->
                                pendingExportConfig = PendingExportConfig(
                                    instances = selectedInstances,
                                    appSettings = appSettings,
                                    includePasswords = includePasswords,
                                )
                                exportDialogOpen = false
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
                                exportLauncher.launch("qnag_instances_$ts.json")
                            },
                            onDismiss = { exportDialogOpen = false },
                        )
                    }

                    // ── Startup scheduling ─────────────────────────────────
                    LaunchedEffect(Unit) {
                        applyPollingMode(appSettings, instances)
                        // Guard: don't prompt for notification permission before any instance
                        // is configured — the Reliability Checklist handles first-run prompting.
                        if (instances.isNotEmpty()) {
                            maybeRequestNotificationPermission(appSettings.notificationSettings.notificationsEnabled)
                        }
                    }

                    var screen by remember {
                        mutableStateOf<AppScreen>(
                            when {
                                instances.isEmpty() -> AppScreen.Welcome
                                instances.any { it.enabled } -> AppScreen.Dashboard(instances.first { it.enabled })
                                else -> AppScreen.AddInstance
                            }
                        )
                    }

                    // After an import on the Welcome screen, instances becomes non-empty — show checklist.
                    LaunchedEffect(instances, screen) {
                        if (screen is AppScreen.Welcome && instances.isNotEmpty()) {
                            val firstEnabled = instances.firstOrNull { it.enabled }
                            screen = if (firstEnabled != null) AppScreen.ReliabilityChecklist(firstEnabled)
                                     else AppScreen.AddInstance
                        }
                    }

                    // Helper: update instance list and apply the appropriate polling mode
                    fun onInstancesUpdated(newInstances: List<NagiosInstance>) {
                        store.saveInstances(newInstances)
                        instances = newInstances
                        applyPollingMode(appSettings, newInstances)
                        WidgetRefresher.onInstancesChanged(applicationContext)
                        val enabled = newInstances.filter { it.enabled }
                        val current = screen
                        when {
                            newInstances.isEmpty() -> screen = AppScreen.Welcome
                            enabled.isEmpty() -> screen = AppScreen.AddInstance
                            current is AppScreen.Dashboard && enabled.none { it.id == current.instance.id } ->
                                screen = AppScreen.Dashboard(enabled.first())
                            current is AppScreen.Settings && enabled.none { it.id == current.fromInstance.id } && enabled.isNotEmpty() ->
                                screen = AppScreen.Settings(enabled.first())
                            else -> Unit
                        }
                    }

                    when (val s = screen) {

                        is AppScreen.Welcome -> {
                            WelcomeScreen(
                                versionName = BuildConfig.VERSION_NAME,
                                onAddInstance = { screen = AppScreen.AddInstance },
                                onImportConfiguration = {
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                            )
                        }

                        is AppScreen.AddInstance -> {
                            AddInstanceScreen(
                                onSave = { newInstance ->
                                    val wasFirstInstance = instances.isEmpty()
                                    store.addInstance(newInstance)
                                    instances = store.getInstances()
                                    applyPollingMode(appSettings, instances)
                                    WidgetRefresher.onInstancesChanged(applicationContext)
                                    screen = if (wasFirstInstance) AppScreen.ReliabilityChecklist(newInstance)
                                             else AppScreen.Dashboard(newInstance)
                                },
                                onCancel = if (instances.any { it.enabled }) {
                                    { screen = AppScreen.Dashboard(instances.first { it.enabled }) }
                                } else null,
                                configuredInstances = instances,
                                // Only offer re-enable when every configured instance is disabled.
                                // When at least one is enabled, the user is on the Dashboard and
                                // this screen is only reachable via "Add new instance", so the
                                // button is not relevant.
                                onEnableConfiguredInstance = if (instances.isNotEmpty() && instances.none { it.enabled }) {
                                    { selected ->
                                        val updated = instances.map {
                                            if (it.id == selected.id) it.copy(enabled = true) else it
                                        }
                                        store.saveInstances(updated)
                                        instances = updated
                                        applyPollingMode(appSettings, updated)
                                        WidgetRefresher.onInstancesChanged(applicationContext)
                                        screen = AppScreen.Dashboard(updated.first { it.id == selected.id })
                                    }
                                } else null,
                            )
                        }

                        is AppScreen.ReliabilityChecklist -> {
                            ReliabilityChecklistScreen(
                                notificationSettings = appSettings.notificationSettings,
                                commandSettings = appSettings.commandSettings,
                                notificationPermissionGranted = notifPermGranted,
                                onRequestNotificationPermission = {
                                    maybeRequestNotificationPermission(true)
                                },
                                onContinue = {
                                    screen = AppScreen.Dashboard(s.dashboardInstance)
                                },
                            )
                        }

                        is AppScreen.Dashboard -> {
                            DashboardScreen(
                                instance = s.instance,
                                allInstances = instances,
                                filterSettings = appSettings.filterSettings,
                                notificationSettings = appSettings.notificationSettings,
                                commandSettings = appSettings.commandSettings,
                                alertListStyle = appSettings.alertListStyle,
                                alertGroupingMode = appSettings.alertGroupingMode,
                                onSwitchInstance = { screen = AppScreen.Dashboard(it) },
                                onAddNewInstance = { screen = AppScreen.AddInstance },
                                onManageInstances = { screen = AppScreen.Settings(s.instance, SettingsDestination.INSTANCES) },
                                onOpenSettings = { screen = AppScreen.Settings(s.instance) },
                                onOpenCommandActivity = { screen = AppScreen.CommandActivity(s.instance) },
                                isMonitoringPaused = monitoringPaused,
                                onResumeMonitoring = {
                                    UserMonitoringPause.clearPaused(this@MainActivity)
                                    isMonitoringPaused.value = false
                                    applyPollingMode(appSettings, instances)
                                },
                                onStopMonitoringAndExit = {
                                    UserMonitoringPause.markPaused(this@MainActivity)
                                    NagiosMonitoringService.stop(this@MainActivity)
                                    BackgroundPollingScheduler.cancel(this@MainActivity)
                                    ExactAlarmWatchdogScheduler.cancel(this@MainActivity)
                                    NotificationHelper.cancelAlertSummary(this@MainActivity)
                                    NotificationHelper.cancelStale(this@MainActivity)
                                    NotificationHelper.cancelRefreshFailure(this@MainActivity)
                                    EventLog.info(this@MainActivity, EventLog.CAT_APP, "qNag monitoring paused by user")
                                    isMonitoringPaused.value = true
                                    finishAndRemoveTask()
                                },
                                initialDashboardScope = appSettings.selectedDashboardScope,
                                onScopeChanged = { newScope ->
                                    val updated = appSettings.copy(selectedDashboardScope = newScope)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                },
                                initialSummaryExpanded = appSettings.summaryExpanded,
                                onSummaryExpandedChanged = { expanded ->
                                    val updated = appSettings.copy(summaryExpanded = expanded)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                },
                                onOpenProblemDetail = { problem, instance ->
                                    screen = AppScreen.ProblemDetail(
                                        problem = problem,
                                        instance = instance,
                                        fromDashboardInstance = s.instance,
                                    )
                                },
                            )
                        }

                        is AppScreen.Settings -> {
                            SettingsScreen(
                                instances = instances,
                                filterSettings = appSettings.filterSettings,
                                notificationSettings = appSettings.notificationSettings,
                                commandSettings = appSettings.commandSettings,
                                notificationPermissionGranted = notifPermGranted,
                                alertListStyle = appSettings.alertListStyle,
                                onUpdateAlertListStyle = { style ->
                                    val updated = appSettings.copy(alertListStyle = style)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                },
                                alertGroupingMode = appSettings.alertGroupingMode,
                                onUpdateAlertGroupingMode = { mode ->
                                    val updated = appSettings.copy(alertGroupingMode = mode)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                },
                                onUpdateInstances = { onInstancesUpdated(it) },
                                onUpdateFilterSettings = { newFilters ->
                                    val updated = appSettings.copy(filterSettings = newFilters)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                },
                                onUpdateNotificationSettings = { newNotif ->
                                    val updated = appSettings.copy(notificationSettings = newNotif)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                    applyPollingMode(updated, instances)
                                    maybeRequestNotificationPermission(newNotif.notificationsEnabled)
                                },
                                onUpdateCommandSettings = { newCmd ->
                                    val updated = appSettings.copy(commandSettings = newCmd)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
                                    applyPollingMode(updated, instances)
                                },
                                onBack = {
                                    val enabled = instances.filter { it.enabled }
                                    screen = when {
                                        enabled.isEmpty() -> AppScreen.AddInstance
                                        enabled.any { it.id == s.fromInstance.id } -> AppScreen.Dashboard(s.fromInstance)
                                        else -> AppScreen.Dashboard(enabled.first())
                                    }
                                },
                                initialDestination = s.initialDestination,
                                onImportInstances = {
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                                onExportInstances = {
                                    if (instances.isNotEmpty()) exportDialogOpen = true
                                },
                            )
                        }

                        is AppScreen.ProblemDetail -> {
                            ProblemDetailScreen(
                                problem = s.problem,
                                instance = s.instance,
                                commandSettings = appSettings.commandSettings,
                                onBack = {
                                    // Return to the same Dashboard instance/scope that was
                                    // active when Details was opened (preserves ALL-mode etc.)
                                    screen = AppScreen.Dashboard(s.fromDashboardInstance)
                                },
                            )
                        }

                        is AppScreen.CommandActivity -> {
                            CommandActivityScreen(
                                onBack = { screen = AppScreen.Dashboard(s.fromDashboardInstance) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notifPermissionGranted.value = NotificationHelper.hasPermission(this)
        isMonitoringPaused.value = UserMonitoringPause.isPaused(this)
        // Auto-restart Reliability Mode when the user opens/resumes the app.
        // Only reads from the store when Reliability Mode is ON and service has died,
        // to avoid expensive reads on every resume.
        autoRestartIfEligible()
    }

    /**
     * Restart Reliability Mode if all conditions are met and the service has stopped.
     * Lightweight guard: only reads EncryptedSharedPreferences when keepMonitoringActive=true
     * and health prefs show the service is not running.
     */
    private fun autoRestartIfEligible() {
        if (UserMonitoringPause.isPaused(this)) return  // paused by user — do not auto-restart
        val snapshot = MonitoringHealth.getSnapshot(this)
        if (snapshot.isServiceRunning) return  // fast-path: service alive, nothing to do
        val store = SecureInstanceStore(this)
        val settings = store.getAppSettings()
        if (!settings.commandSettings.keepMonitoringActive) return  // Reliability Mode OFF
        val instances = store.getInstances()
        applyPollingMode(settings, instances)
    }

    /**
     * Single entry-point for all foreground-service / WorkManager scheduling decisions.
     *
     * Rules:
     *  - eligible = notificationsEnabled AND at least one enabled+notificationsEnabled instance
     *  - keepMonitoringActive && eligible  → cancel WorkManager, start/reload foreground service
     *  - keepMonitoringActive && !eligible → stop foreground service, cancel WorkManager
     *  - !keepMonitoringActive             → stop foreground service, scheduleOrCancel WorkManager
     */
    private fun applyPollingMode(settings: AppSettings, instances: List<NagiosInstance>) {
        if (UserMonitoringPause.isPaused(this)) return  // do not restart while paused by user
        val eligible = settings.notificationSettings.notificationsEnabled &&
                instances.any { it.enabled && it.notificationsEnabled }
        val debug = settings.commandSettings.debugCommandSubmission

        if (settings.commandSettings.keepMonitoringActive && eligible) {
            if (debug) android.util.Log.d("qNag", "[main] applyPollingMode: foreground active+eligible")
            BackgroundPollingScheduler.cancel(this)
            NagiosMonitoringService.start(this)  // idempotent — service reloads settings
            ExactAlarmWatchdogScheduler.schedule(this, settings)
        } else {
            NagiosMonitoringService.stop(this)
            ExactAlarmWatchdogScheduler.cancel(this)
            if (!settings.commandSettings.keepMonitoringActive) {
                if (debug) android.util.Log.d("qNag", "[main] applyPollingMode: foreground off, scheduling WorkManager")
                BackgroundPollingScheduler.scheduleOrCancel(this, settings, instances)
            } else {
                // keepMonitoringActive=true but not eligible — cancel both
                if (debug) android.util.Log.d("qNag", "[main] applyPollingMode: foreground active but not eligible, cancelling WorkManager")
                BackgroundPollingScheduler.cancel(this)
            }
        }
    }

    private fun maybeRequestNotificationPermission(notificationsEnabled: Boolean) {
        if (!notificationsEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
