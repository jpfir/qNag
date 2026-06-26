package com.exogroup.qnag.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.UUID

class SecureInstanceStore(context: Context) : InstanceStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nagios_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    // ── Instance storage ──────────────────────────────────────────────────────

    override fun getInstances(): List<NagiosInstance> {
        val json = prefs.getString(KEY_INSTANCES, null) ?: return emptyList()
        return try {
            // Manual deserialization: Gson sets Boolean fields to false when missing from JSON,
            // ignoring Kotlin defaults. Explicit defaults here ensure backward compatibility.
            val array = JsonParser.parseString(json).asJsonArray
            array.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    NagiosInstance(
                        id = obj.get("id")?.asString ?: UUID.randomUUID().toString(),
                        name = obj.get("name")?.asString ?: return@mapNotNull null,
                        url = obj.get("url")?.asString ?: return@mapNotNull null,
                        username = obj.get("username")?.asString ?: "",
                        password = obj.get("password")?.asString ?: "",
                        enabled = obj.get("enabled")?.asBoolean ?: true,
                        notificationsEnabled = obj.get("notificationsEnabled")?.asBoolean ?: true,
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun saveInstances(instances: List<NagiosInstance>) {
        prefs.edit().putString(KEY_INSTANCES, gson.toJson(instances)).apply()
    }

    override fun addInstance(instance: NagiosInstance) {
        saveInstances(getInstances() + instance)
    }

    // ── App settings storage ──────────────────────────────────────────────────
    //
    // Each nested object is deserialized independently so that missing sub-objects
    // (e.g. commandSettings absent in old JSON) always fall back to their defaults
    // rather than crashing or silently returning null.

    fun getAppSettings(): AppSettings {
        val json = prefs.getString(KEY_APP_SETTINGS, null) ?: return AppSettings()
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            AppSettings(
                filterSettings = parseFilterSettings(root.get("filterSettings")),
                notificationSettings = parseNotificationSettings(root.get("notificationSettings")),
                commandSettings = parseCommandSettings(root.get("commandSettings")),
                selectedDashboardScope = root.get("selectedDashboardScope")
                    ?.takeIf { !it.isJsonNull }?.asString ?: "ALL",
                summaryExpanded = root.get("summaryExpanded")?.asBoolean ?: true,
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun saveAppSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_APP_SETTINGS, gson.toJson(settings)).apply()
    }

    // ── Explicit field-by-field parsers ───────────────────────────────────────
    //
    // Gson ignores Kotlin default parameter values for missing JSON fields, silently
    // setting booleans to false and ints to 0.  Each parser below provides the intended
    // defaults so stored settings from older app versions are migrated safely.

    private fun parseCommandSettings(el: com.google.gson.JsonElement?): CommandSettings {
        if (el == null || el.isJsonNull) return CommandSettings()
        return try {
            val o = el.asJsonObject
            CommandSettings(
                defaultAckMessage = o.get("defaultAckMessage")?.asString ?: "Not critical or being worked on",
                ackAuthor = o.get("ackAuthor")?.asString ?: "qNag",
                ackSticky = o.get("ackSticky")?.asBoolean ?: true,
                ackNotify = o.get("ackNotify")?.asBoolean ?: false,
                ackPersistent = o.get("ackPersistent")?.asBoolean ?: false,
                notifyOnFetchFailure = o.get("notifyOnFetchFailure")?.asBoolean ?: true,
                notifyOnlyNewProblems = o.get("notifyOnlyNewProblems")?.asBoolean ?: true,
                showBatteryOptimizationHint = o.get("showBatteryOptimizationHint")?.asBoolean ?: true,
                keepMonitoringActive = o.get("keepMonitoringActive")?.asBoolean ?: true,
                foregroundPollingIntervalSeconds = o.get("foregroundPollingIntervalSeconds")?.asInt ?: 60,
                nagiosDateFormat = try {
                    o.get("nagiosDateFormat")?.takeIf { !it.isJsonNull }?.asString
                        ?.let { NagiosDateFormat.valueOf(it) }
                } catch (_: Exception) { null },
                ackServicesOnHostAck = o.get("ackServicesOnHostAck")?.asBoolean ?: true,
                monitoringStaleThresholdMinutes = o.get("monitoringStaleThresholdMinutes")?.asInt ?: 5,
                staleMonitoringAlertEnabled = o.get("staleMonitoringAlertEnabled")?.asBoolean ?: true,
                exactAlarmWatchdogEnabled = o.get("exactAlarmWatchdogEnabled")?.asBoolean ?: true,
                exactAlarmWatchdogIntervalMinutes = o.get("exactAlarmWatchdogIntervalMinutes")?.asInt ?: 2,
                debugCommandSubmission = o.get("debugCommandSubmission")?.asBoolean ?: false,
            )
        } catch (_: Exception) { CommandSettings() }
    }

    private fun parseNotificationSettings(el: com.google.gson.JsonElement?): NotificationSettings {
        if (el == null || el.isJsonNull) return NotificationSettings()
        return try {
            val o = el.asJsonObject
            NotificationSettings(
                notificationsEnabled = o.get("notificationsEnabled")?.asBoolean ?: true,
                notifyOnCriticalServices = o.get("notifyOnCriticalServices")?.asBoolean ?: true,
                notifyOnWarningServices = o.get("notifyOnWarningServices")?.asBoolean ?: false,
                notifyOnUnknownServices = o.get("notifyOnUnknownServices")?.asBoolean ?: true,
                notifyOnDownHosts = o.get("notifyOnDownHosts")?.asBoolean ?: true,
                notifyOnUnreachableHosts = o.get("notifyOnUnreachableHosts")?.asBoolean ?: true,
                respectNagiosNotificationsDisabled = o.get("respectNagiosNotificationsDisabled")?.asBoolean ?: true,
                notifyOnlyUnacknowledged = o.get("notifyOnlyUnacknowledged")?.asBoolean ?: true,
                notifyOnlyHardState = o.get("notifyOnlyHardState")?.asBoolean ?: true,
                respectDowntime = o.get("respectDowntime")?.asBoolean ?: true,
                refreshIntervalMinutes = o.get("refreshIntervalMinutes")?.asInt ?: 15,
                globalSoundCooldownSeconds = o.get("globalSoundCooldownSeconds")?.asInt ?: 300,
                perStateSoundCooldownSeconds = o.get("perStateSoundCooldownSeconds")?.asInt ?: 300,
                repeatSameProblemSound = o.get("repeatSameProblemSound")?.asBoolean ?: false,
                notificationMode = try {
                    o.get("notificationMode")?.takeIf { !it.isJsonNull }?.asString
                        ?.let { NotificationMode.valueOf(it) }
                } catch (_: Exception) { null } ?: NotificationMode.SUMMARY_ONLY,
                alertSoundMode = try {
                    o.get("alertSoundMode")?.takeIf { !it.isJsonNull }?.asString
                        ?.let { AlertSoundMode.valueOf(it) }
                } catch (_: Exception) { null } ?: AlertSoundMode.IN_APP_SOUND,
                playSoundInVibrateMode = o.get("playSoundInVibrateMode")?.asBoolean ?: true,
                useAlarmAudioStream = o.get("useAlarmAudioStream")?.asBoolean ?: true,
                inAppSoundUri = o.get("inAppSoundUri")?.takeIf { !it.isJsonNull }?.asString,
                helpBypassDnd = o.get("helpBypassDnd")?.asBoolean ?: false,
                tier2PlusEnabled = o.get("tier2PlusEnabled")?.asBoolean ?: false,
                tier2PlusDelayMinutes = o.get("tier2PlusDelayMinutes")?.asInt ?: 5,
                tier2PlusUsePerStateDelays = o.get("tier2PlusUsePerStateDelays")?.asBoolean ?: false,
                tier2HostDownDelayMinutes = o.get("tier2HostDownDelayMinutes")?.asInt ?: 5,
                tier2HostUnreachableDelayMinutes = o.get("tier2HostUnreachableDelayMinutes")?.asInt ?: 5,
                tier2ServiceCriticalDelayMinutes = o.get("tier2ServiceCriticalDelayMinutes")?.asInt ?: 5,
                tier2ServiceWarningDelayMinutes = o.get("tier2ServiceWarningDelayMinutes")?.asInt ?: 15,
                tier2ServiceUnknownDelayMinutes = o.get("tier2ServiceUnknownDelayMinutes")?.asInt ?: 10,
                notifyAckedAfterEnabled = o.get("notifyAckedAfterEnabled")?.asBoolean ?: false,
                notifyAckedAfterMinutes = o.get("notifyAckedAfterMinutes")?.asInt ?: 120,
                maxAlertSoundSeconds = o.get("maxAlertSoundSeconds")?.asInt ?: 10,
                wearableNotifDetail = try {
                    o.get("wearableNotifDetail")?.takeIf { !it.isJsonNull }?.asString
                        ?.let { WearableNotifDetail.valueOf(it) }
                } catch (_: Exception) { null } ?: WearableNotifDetail.TOP_PROBLEM_PLUS_SUMMARY,
                hideDetailsOnLockScreen = o.get("hideDetailsOnLockScreen")?.asBoolean ?: false,
            )
        } catch (_: Exception) { NotificationSettings() }
    }

    private fun parseFilterSettings(el: com.google.gson.JsonElement?): FilterSettings {
        if (el == null || el.isJsonNull) return FilterSettings()
        return try {
            val o = el.asJsonObject
            FilterSettings(
                hideDownHosts = o.get("hideDownHosts")?.asBoolean ?: false,
                hideUnreachableHosts = o.get("hideUnreachableHosts")?.asBoolean ?: false,
                hideFlappingHosts = o.get("hideFlappingHosts")?.asBoolean ?: false,
                hideCriticalServices = o.get("hideCriticalServices")?.asBoolean ?: false,
                hideUnknownServices = o.get("hideUnknownServices")?.asBoolean ?: false,
                hideWarningServices = o.get("hideWarningServices")?.asBoolean ?: false,
                hideFlappingServices = o.get("hideFlappingServices")?.asBoolean ?: false,
                hideAcknowledgedHostsAndServices = o.get("hideAcknowledgedHostsAndServices")?.asBoolean ?: false,
                hideHostsAndServicesWithDisabledNotifications = o.get("hideHostsAndServicesWithDisabledNotifications")?.asBoolean ?: false,
                hideHostsAndServicesWithDisabledChecks = o.get("hideHostsAndServicesWithDisabledChecks")?.asBoolean ?: false,
                hideHostsAndServicesDownForDowntime = o.get("hideHostsAndServicesDownForDowntime")?.asBoolean ?: false,
                hideServicesOnAcknowledgedHosts = o.get("hideServicesOnAcknowledgedHosts")?.asBoolean ?: false,
                hideServicesOnDownHosts = o.get("hideServicesOnDownHosts")?.asBoolean ?: false,
                hideServicesOnHostsInDowntime = o.get("hideServicesOnHostsInDowntime")?.asBoolean ?: false,
                hideServicesOnUnreachableHosts = o.get("hideServicesOnUnreachableHosts")?.asBoolean ?: false,
                hideHostsInSoftState = o.get("hideHostsInSoftState")?.asBoolean ?: false,
                hideServicesInSoftState = o.get("hideServicesInSoftState")?.asBoolean ?: false,
                hostRegexEnabled = o.get("hostRegexEnabled")?.asBoolean ?: false,
                hostRegex = o.get("hostRegex")?.asString ?: "",
                hostRegexReverse = o.get("hostRegexReverse")?.asBoolean ?: false,
                serviceRegexEnabled = o.get("serviceRegexEnabled")?.asBoolean ?: false,
                serviceRegex = o.get("serviceRegex")?.asString ?: "",
                serviceRegexReverse = o.get("serviceRegexReverse")?.asBoolean ?: false,
                statusInfoRegexEnabled = o.get("statusInfoRegexEnabled")?.asBoolean ?: false,
                statusInfoRegex = o.get("statusInfoRegex")?.asString ?: "",
                statusInfoRegexReverse = o.get("statusInfoRegexReverse")?.asBoolean ?: false,
            )
        } catch (_: Exception) { FilterSettings() }
    }

    private companion object {
        const val KEY_INSTANCES = "INSTANCE_LIST"
        const val KEY_APP_SETTINGS = "APP_SETTINGS"
    }
}
