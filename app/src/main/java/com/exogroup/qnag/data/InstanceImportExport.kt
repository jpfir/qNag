package com.exogroup.qnag.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.util.UUID

// ── JSON DTOs — qnagstamon.instances v1 interchange format ───────────────────

data class InstanceExportBundle(
    @SerializedName("type")
    val type: String = "qnagstamon.instances",

    val version: Int = 1,

    @SerializedName("exported_at")
    val exportedAt: String,

    @SerializedName("include_passwords")
    val includePasswords: Boolean,

    val instances: List<InstanceExportItem>,
)

data class InstanceExportItem(
    val name: String,
    val url: String,

    @SerializedName("user")
    val user: String,

    val password: String,
    val enabled: Boolean,

    @SerializedName("refresh_interval")
    val refreshInterval: Int,

    val timeout: Int,

    @SerializedName("filter_notif")
    val filterNotif: Boolean,

    @SerializedName("filter_maint")
    val filterMaint: Boolean,

    @SerializedName("filter_down_hosts")
    val filterDownHosts: Boolean,

    @SerializedName("play_sound")
    val playSound: Boolean,

    @SerializedName("desktop_notifications")
    val desktopNotifications: Boolean,
)

// ── Export ────────────────────────────────────────────────────────────────────

fun exportInstancesToJson(
    instances: List<NagiosInstance>,
    filterSettings: FilterSettings,
    notificationSettings: NotificationSettings,
    commandSettings: CommandSettings,
    includePasswords: Boolean,
    nowUtcIso: String,
): String {
    val refreshInterval = if (commandSettings.keepMonitoringActive)
        commandSettings.foregroundPollingIntervalSeconds
    else
        notificationSettings.refreshIntervalMinutes * 60

    val items = instances.map { inst ->
        InstanceExportItem(
            name = inst.name,
            url = inst.url,
            user = inst.username,
            password = if (includePasswords) inst.password else "",
            enabled = inst.enabled,
            refreshInterval = refreshInterval,
            timeout = 30,
            filterNotif = filterSettings.hideHostsAndServicesWithDisabledNotifications,
            filterMaint = filterSettings.hideHostsAndServicesDownForDowntime,
            filterDownHosts = filterSettings.hideServicesOnDownHosts,
            playSound = true,
            desktopNotifications = inst.notificationsEnabled,
        )
    }

    val bundle = InstanceExportBundle(
        exportedAt = nowUtcIso,
        includePasswords = includePasswords,
        instances = items,
    )

    return GsonBuilder().setPrettyPrinting().create().toJson(bundle)
}

// ── Import ────────────────────────────────────────────────────────────────────

data class ImportPreview(
    val toAdd: List<NagiosInstance>,
    val toUpdate: List<NagiosInstance>,
    val passwordsIncluded: Boolean,
)

sealed class ImportParseResult {
    data class Success(val preview: ImportPreview) : ImportParseResult()
    data class Failure(val error: String) : ImportParseResult()
}

fun parseImportJson(json: String, existing: List<NagiosInstance>): ImportParseResult {
    val root = try {
        JsonParser.parseString(json).asJsonObject
    } catch (_: Exception) {
        return ImportParseResult.Failure("Not valid JSON.")
    }

    val type = root.get("type")?.asString
    if (type != "qnagstamon.instances") {
        return ImportParseResult.Failure(
            "Unsupported file type: \"$type\".\nExpected \"qnagstamon.instances\"."
        )
    }

    val version = try { root.get("version")?.asInt } catch (_: Exception) { null }
    if (version != 1) {
        return ImportParseResult.Failure(
            "Unsupported version: $version. Only version 1 is supported."
        )
    }

    val instancesEl = root.get("instances")
        ?: return ImportParseResult.Failure("Missing \"instances\" array.")
    val instancesArr = try { instancesEl.asJsonArray } catch (_: Exception) {
        return ImportParseResult.Failure("\"instances\" is not a JSON array.")
    }

    val passwordsIncluded = root.get("include_passwords")?.asBoolean ?: false

    val errors = mutableListOf<String>()
    val parsed = mutableListOf<NagiosInstance>()

    instancesArr.forEachIndexed { index, el ->
        val label = "#${index + 1}"
        val obj = try { el.asJsonObject } catch (_: Exception) {
            errors += "Instance $label: not a JSON object"
            return@forEachIndexed
        }
        val name = obj.get("name")?.asString?.trim() ?: ""
        val url  = obj.get("url")?.asString?.trim()  ?: ""
        val user = obj.get("user")?.asString?.trim()  ?: ""
        val password      = obj.get("password")?.asString ?: ""
        val enabled       = obj.get("enabled")?.asBoolean ?: true
        val desktopNotifs = obj.get("desktop_notifications")?.asBoolean ?: true

        when {
            name.isBlank() -> {
                errors += "Instance $label: name is blank"
                return@forEachIndexed
            }
            url.isBlank() -> {
                errors += "Instance $label \"$name\": url is blank"
                return@forEachIndexed
            }
            !url.startsWith("http://") && !url.startsWith("https://") -> {
                errors += "Instance $label \"$name\": url must start with http:// or https://"
                return@forEachIndexed
            }
        }

        parsed += NagiosInstance(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            username = user,
            password = password,
            enabled = enabled,
            notificationsEnabled = desktopNotifs,
        )
    }

    if (parsed.isEmpty()) {
        val msg = if (errors.isNotEmpty()) errors.joinToString("\n") else "No valid instances in file."
        return ImportParseResult.Failure(msg)
    }

    val toAdd    = mutableListOf<NagiosInstance>()
    val toUpdate = mutableListOf<NagiosInstance>()

    for (imported in parsed) {
        val matched = existing.firstOrNull { it.matchesImported(imported) }
        if (matched == null) {
            toAdd += imported
        } else {
            toUpdate += imported.copy(
                id = matched.id,
                password = if (imported.password.isNotEmpty()) imported.password else matched.password,
            )
        }
    }

    return ImportParseResult.Success(
        ImportPreview(toAdd = toAdd, toUpdate = toUpdate, passwordsIncluded = passwordsIncluded)
    )
}

fun applyImport(existing: List<NagiosInstance>, preview: ImportPreview): List<NagiosInstance> {
    val result = existing.toMutableList()
    for (updated in preview.toUpdate) {
        val idx = result.indexOfFirst { it.id == updated.id }
        if (idx >= 0) result[idx] = updated
    }
    result.addAll(preview.toAdd)
    return result
}

private fun NagiosInstance.matchesImported(other: NagiosInstance): Boolean {
    val thisUrl  = url.trim().trimEnd('/')
    val otherUrl = other.url.trim().trimEnd('/')
    return thisUrl.equals(otherUrl, ignoreCase = true) &&
        username.trim() == other.username.trim()
}
