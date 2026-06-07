package com.exogroup.qnag

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.exogroup.qnag.data.AppSettings
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.reliability.ExactAlarmWatchdogScheduler
import com.exogroup.qnag.service.NagiosMonitoringService
import com.exogroup.qnag.ui.AddInstanceScreen
import com.exogroup.qnag.ui.DashboardScreen
import com.exogroup.qnag.ui.SettingsScreen
import com.exogroup.qnag.worker.BackgroundPollingScheduler

private sealed class AppScreen {
    object AddInstance : AppScreen()
    data class Dashboard(val instance: NagiosInstance) : AppScreen()
    data class Settings(val fromInstance: NagiosInstance) : AppScreen()
}

class MainActivity : ComponentActivity() {

    // Compose-state holder for notification permission — updated in the result callback
    // so SettingsScreen immediately reflects the new grant/deny state.
    private var notifPermissionGranted = mutableStateOf(false)

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            notifPermissionGranted.value = isGranted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannels(this)
        notifPermissionGranted.value = NotificationHelper.hasPermission(this)
        // Auto-restart on cold start (also covers package replace / force-stop recovery)
        autoRestartIfEligible()

        val store = SecureInstanceStore(this)

        setContent {
            val isDark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    var instances by remember { mutableStateOf(store.getInstances()) }
                    var appSettings by remember { mutableStateOf(store.getAppSettings()) }
                    val notifPermGranted by notifPermissionGranted  // observe the Activity-level state

                    // ── Startup scheduling ─────────────────────────────────
                    LaunchedEffect(Unit) {
                        applyPollingMode(appSettings, instances)
                        maybeRequestNotificationPermission(appSettings.notificationSettings.notificationsEnabled)
                    }

                    var screen by remember {
                        val firstEnabled = instances.firstOrNull { it.enabled }
                        mutableStateOf<AppScreen>(
                            if (firstEnabled != null) AppScreen.Dashboard(firstEnabled)
                            else AppScreen.AddInstance
                        )
                    }

                    // Helper: update instance list and apply the appropriate polling mode
                    fun onInstancesUpdated(newInstances: List<NagiosInstance>) {
                        store.saveInstances(newInstances)
                        instances = newInstances
                        applyPollingMode(appSettings, newInstances)
                        val enabled = newInstances.filter { it.enabled }
                        val current = screen
                        when {
                            enabled.isEmpty() -> screen = AppScreen.AddInstance
                            current is AppScreen.Dashboard && enabled.none { it.id == current.instance.id } ->
                                screen = AppScreen.Dashboard(enabled.first())
                            current is AppScreen.Settings && enabled.none { it.id == current.fromInstance.id } && enabled.isNotEmpty() ->
                                screen = AppScreen.Settings(enabled.first())
                            else -> Unit
                        }
                    }

                    when (val s = screen) {

                        is AppScreen.AddInstance -> {
                            AddInstanceScreen(
                                onSave = { newInstance ->
                                    store.addInstance(newInstance)
                                    instances = store.getInstances()
                                    applyPollingMode(appSettings, instances)
                                    screen = AppScreen.Dashboard(newInstance)
                                },
                                onCancel = if (instances.any { it.enabled }) {
                                    { screen = AppScreen.Dashboard(instances.first { it.enabled }) }
                                } else null,
                            )
                        }

                        is AppScreen.Dashboard -> {
                            DashboardScreen(
                                instance = s.instance,
                                allInstances = instances,
                                filterSettings = appSettings.filterSettings,
                                notificationSettings = appSettings.notificationSettings,
                                commandSettings = appSettings.commandSettings,
                                onSwitchInstance = { screen = AppScreen.Dashboard(it) },
                                onAddNewInstance = { screen = AppScreen.AddInstance },
                                onOpenSettings = { screen = AppScreen.Settings(s.instance) },
                                initialDashboardScope = appSettings.selectedDashboardScope,
                                onScopeChanged = { newScope ->
                                    val updated = appSettings.copy(selectedDashboardScope = newScope)
                                    store.saveAppSettings(updated)
                                    appSettings = updated
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
        // Auto-restart Reliability Mode when the user opens/resumes the app (Goal 1).
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
