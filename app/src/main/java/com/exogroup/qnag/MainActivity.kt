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
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.notifications.NotificationHelper
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
                        if (appSettings.commandSettings.keepMonitoringActive) {
                            // Foreground service is preferred — cancel WorkManager to avoid duplicates
                            BackgroundPollingScheduler.cancel(this@MainActivity)
                            NagiosMonitoringService.start(this@MainActivity)
                        } else {
                            BackgroundPollingScheduler.scheduleOrCancel(this@MainActivity, appSettings, instances)
                        }
                        maybeRequestNotificationPermission(appSettings.notificationSettings.notificationsEnabled)
                    }

                    var screen by remember {
                        val firstEnabled = instances.firstOrNull { it.enabled }
                        mutableStateOf<AppScreen>(
                            if (firstEnabled != null) AppScreen.Dashboard(firstEnabled)
                            else AppScreen.AddInstance
                        )
                    }

                    // Helper: update instance list and reschedule workers
                    fun onInstancesUpdated(newInstances: List<NagiosInstance>) {
                        store.saveInstances(newInstances)
                        instances = newInstances
                        if (!appSettings.commandSettings.keepMonitoringActive) {
                            BackgroundPollingScheduler.scheduleOrCancel(this@MainActivity, appSettings, newInstances)
                        }
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
                                    if (!appSettings.commandSettings.keepMonitoringActive) {
                                        BackgroundPollingScheduler.scheduleOrCancel(this@MainActivity, appSettings, instances)
                                    }
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
                                    if (!appSettings.commandSettings.keepMonitoringActive) {
                                        BackgroundPollingScheduler.scheduleOrCancel(this@MainActivity, updated, instances)
                                    }
                                    maybeRequestNotificationPermission(newNotif.notificationsEnabled)
                                },
                                onUpdateCommandSettings = { newCmd ->
                                    val oldCmd = appSettings.commandSettings
                                    val updated = appSettings.copy(commandSettings = newCmd)
                                    store.saveAppSettings(updated)
                                    appSettings = updated

                                    // Manage foreground service vs WorkManager
                                    when {
                                        newCmd.keepMonitoringActive && !oldCmd.keepMonitoringActive -> {
                                            BackgroundPollingScheduler.cancel(this@MainActivity)
                                            NagiosMonitoringService.start(this@MainActivity)
                                        }
                                        !newCmd.keepMonitoringActive && oldCmd.keepMonitoringActive -> {
                                            NagiosMonitoringService.stop(this@MainActivity)
                                            BackgroundPollingScheduler.scheduleOrCancel(this@MainActivity, updated, instances)
                                        }
                                    }
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

    private fun maybeRequestNotificationPermission(notificationsEnabled: Boolean) {
        if (!notificationsEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
