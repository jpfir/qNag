// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class InstanceImportExportTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeInstance(
        id: String = "id-1",
        name: String = "Prod",
        url: String = "https://nagios.example.com",
        username: String = "admin",
        password: String = "secret",
        enabled: Boolean = true,
        notificationsEnabled: Boolean = true,
    ) = NagiosInstance(id = id, name = name, url = url, username = username,
        password = password, enabled = enabled, notificationsEnabled = notificationsEnabled)

    private val validJson = """
        {
          "type": "qnagstamon.instances",
          "version": 1,
          "exported_at": "2026-06-08T07:45:30Z",
          "include_passwords": true,
          "instances": [
            {
              "name": "ExADS",
              "url": "https://nagios.example.com/nagios/cgi-bin",
              "user": "admin",
              "password": "s3cr3t",
              "enabled": true,
              "refresh_interval": 60,
              "timeout": 30,
              "filter_notif": true,
              "filter_maint": true,
              "filter_down_hosts": false,
              "play_sound": true,
              "desktop_notifications": true
            }
          ]
        }
    """.trimIndent()

    // ── Import: parse valid export ────────────────────────────────────────────

    @Test
    fun `parse valid qNagstamon export`() {
        val result = parseImportJson(validJson, emptyList())
        assertTrue(result is ImportParseResult.Success)
        val preview = (result as ImportParseResult.Success).preview
        assertEquals(1, preview.toAdd.size)
        assertEquals(0, preview.toUpdate.size)
        val inst = preview.toAdd[0]
        assertEquals("ExADS", inst.name)
        assertEquals("https://nagios.example.com/nagios/cgi-bin", inst.url)
        assertEquals("admin", inst.username)
        assertEquals("s3cr3t", inst.password)
        assertTrue(inst.enabled)
        assertTrue(inst.notificationsEnabled)
    }

    @Test
    fun `desktop_notifications false maps to notificationsEnabled false`() {
        val json = validJson.replace(
            "\"desktop_notifications\": true",
            "\"desktop_notifications\": false"
        )
        val result = parseImportJson(json, emptyList()) as ImportParseResult.Success
        assertFalse(result.preview.toAdd[0].notificationsEnabled)
    }

    @Test
    fun `disabled instance stays disabled`() {
        val json = validJson.replace("\"enabled\": true", "\"enabled\": false")
        val result = parseImportJson(json, emptyList()) as ImportParseResult.Success
        assertFalse(result.preview.toAdd[0].enabled)
    }

    @Test
    fun `passwords_included flag is parsed`() {
        val result = parseImportJson(validJson, emptyList()) as ImportParseResult.Success
        assertTrue(result.preview.passwordsIncluded)

        val noPwdJson = validJson.replace("\"include_passwords\": true", "\"include_passwords\": false")
        val result2 = parseImportJson(noPwdJson, emptyList()) as ImportParseResult.Success
        assertFalse(result2.preview.passwordsIncluded)
    }

    // ── Import: validation failures ───────────────────────────────────────────

    @Test
    fun `reject invalid JSON`() {
        val result = parseImportJson("not json {{{", emptyList())
        assertTrue(result is ImportParseResult.Failure)
    }

    @Test
    fun `reject wrong type`() {
        val json = validJson.replace(
            "\"type\": \"qnagstamon.instances\"",
            "\"type\": \"some.other.format\""
        )
        val result = parseImportJson(json, emptyList())
        assertTrue(result is ImportParseResult.Failure)
        assertTrue((result as ImportParseResult.Failure).error.contains("Unsupported file type"))
    }

    @Test
    fun `reject unsupported version`() {
        val json = validJson.replace("\"version\": 1", "\"version\": 2")
        val result = parseImportJson(json, emptyList())
        assertTrue(result is ImportParseResult.Failure)
        assertTrue((result as ImportParseResult.Failure).error.contains("Unsupported version"))
    }

    @Test
    fun `reject instance with blank name`() {
        val json = validJson.replace("\"name\": \"ExADS\"", "\"name\": \"\"")
        val result = parseImportJson(json, emptyList())
        assertTrue(result is ImportParseResult.Failure)
    }

    @Test
    fun `reject instance with bad url scheme`() {
        val json = validJson.replace(
            "\"url\": \"https://nagios.example.com/nagios/cgi-bin\"",
            "\"url\": \"ftp://nagios.example.com\""
        )
        val result = parseImportJson(json, emptyList())
        assertTrue(result is ImportParseResult.Failure)
    }

    // ── Import: merge behavior ────────────────────────────────────────────────

    @Test
    fun `new instance goes to toAdd`() {
        val existing = listOf(makeInstance(url = "https://other.example.com", username = "admin"))
        val result = parseImportJson(validJson, existing) as ImportParseResult.Success
        assertEquals(1, result.preview.toAdd.size)
        assertEquals(0, result.preview.toUpdate.size)
    }

    @Test
    fun `match existing by url and username goes to toUpdate`() {
        val existing = listOf(
            makeInstance(
                id = "existing-id",
                url = "https://nagios.example.com/nagios/cgi-bin",
                username = "admin",
                password = "old-password",
            )
        )
        val result = parseImportJson(validJson, existing) as ImportParseResult.Success
        assertEquals(0, result.preview.toAdd.size)
        assertEquals(1, result.preview.toUpdate.size)
        assertEquals("existing-id", result.preview.toUpdate[0].id)
    }

    @Test
    fun `import with password replaces existing password`() {
        val existing = listOf(
            makeInstance(id = "eid", url = "https://nagios.example.com/nagios/cgi-bin",
                username = "admin", password = "old")
        )
        val result = parseImportJson(validJson, existing) as ImportParseResult.Success
        assertEquals("s3cr3t", result.preview.toUpdate[0].password)
    }

    @Test
    fun `import without password preserves existing password`() {
        val noPwdJson = validJson
            .replace("\"password\": \"s3cr3t\"", "\"password\": \"\"")
            .replace("\"include_passwords\": true", "\"include_passwords\": false")
        val existing = listOf(
            makeInstance(id = "eid", url = "https://nagios.example.com/nagios/cgi-bin",
                username = "admin", password = "kept-password")
        )
        val result = parseImportJson(noPwdJson, existing) as ImportParseResult.Success
        assertEquals("kept-password", result.preview.toUpdate[0].password)
    }

    @Test
    fun `url trailing slash is normalized for matching`() {
        // existing has trailing slash, import does not
        val existing = listOf(
            makeInstance(id = "eid", url = "https://nagios.example.com/nagios/cgi-bin/",
                username = "admin", password = "pw")
        )
        val result = parseImportJson(validJson, existing) as ImportParseResult.Success
        assertEquals(0, result.preview.toAdd.size)
        assertEquals(1, result.preview.toUpdate.size)
        assertEquals("eid", result.preview.toUpdate[0].id)
    }

    // ── applyImport ───────────────────────────────────────────────────────────

    @Test
    fun `applyImport adds new instances`() {
        val existing = listOf(makeInstance(id = "e1"))
        val newInst  = makeInstance(id = "n1", name = "New", url = "https://new.example.com")
        val preview  = ImportPreview(toAdd = listOf(newInst), toUpdate = emptyList(), passwordsIncluded = false)
        val result   = applyImport(existing, preview)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "e1" })
        assertTrue(result.any { it.id == "n1" })
    }

    @Test
    fun `applyImport updates existing instances in place`() {
        val existing = listOf(makeInstance(id = "e1", name = "Old Name"))
        val updated  = existing[0].copy(name = "New Name")
        val preview  = ImportPreview(toAdd = emptyList(), toUpdate = listOf(updated), passwordsIncluded = false)
        val result   = applyImport(existing, preview)
        assertEquals(1, result.size)
        assertEquals("New Name", result[0].name)
    }

    @Test
    fun `applyImport does not delete unmatched existing instances`() {
        val inst1 = makeInstance(id = "e1", url = "https://a.example.com")
        val inst2 = makeInstance(id = "e2", url = "https://b.example.com")
        val existing = listOf(inst1, inst2)
        // Only import inst1
        val preview = ImportPreview(
            toAdd = emptyList(),
            toUpdate = listOf(inst1.copy(name = "Updated")),
            passwordsIncluded = false,
        )
        val result = applyImport(existing, preview)
        assertEquals(2, result.size)
        assertTrue(result.any { it.id == "e2" })
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportAndParse(
        instances: List<NagiosInstance>,
        filterSettings: FilterSettings = FilterSettings(),
        notificationSettings: NotificationSettings = NotificationSettings(),
        commandSettings: CommandSettings = CommandSettings(),
        includePasswords: Boolean = false,
    ) = JsonParser.parseString(
        exportInstancesToJson(
            instances = instances,
            filterSettings = filterSettings,
            notificationSettings = notificationSettings,
            commandSettings = commandSettings,
            includePasswords = includePasswords,
            nowUtcIso = "2026-06-08T00:00:00Z",
        )
    ).asJsonObject

    @Test
    fun `export type and version match qnagstamon schema`() {
        val root = exportAndParse(listOf(makeInstance()))
        assertEquals("qnagstamon.instances", root.get("type").asString)
        assertEquals(1, root.get("version").asInt)
    }

    @Test
    fun `export with passwords includes plaintext password`() {
        val root = exportAndParse(listOf(makeInstance(password = "my-pass")), includePasswords = true)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals("my-pass", item.get("password").asString)
        assertTrue(root.get("include_passwords").asBoolean)
    }

    @Test
    fun `export without passwords clears password field`() {
        val root = exportAndParse(listOf(makeInstance(password = "my-pass")), includePasswords = false)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals("", item.get("password").asString)
        assertFalse(root.get("include_passwords").asBoolean)
    }

    @Test
    fun `export user field maps from username`() {
        val root = exportAndParse(listOf(makeInstance(username = "nagiosadmin")), includePasswords = true)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals("nagiosadmin", item.get("user").asString)
        assertNull(item.get("username"))  // must not leak under wrong key
    }

    @Test
    fun `export desktop_notifications maps from notificationsEnabled`() {
        val enabled  = exportAndParse(listOf(makeInstance(notificationsEnabled = true)))
        val disabled = exportAndParse(listOf(makeInstance(notificationsEnabled = false)))
        assertTrue(enabled.getAsJsonArray("instances")[0].asJsonObject
            .get("desktop_notifications").asBoolean)
        assertFalse(disabled.getAsJsonArray("instances")[0].asJsonObject
            .get("desktop_notifications").asBoolean)
    }

    @Test
    fun `export disabled instance preserves enabled false`() {
        val root = exportAndParse(listOf(makeInstance(enabled = false)))
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertFalse(item.get("enabled").asBoolean)
    }

    @Test
    fun `export refresh_interval uses foreground polling when keepMonitoringActive`() {
        val cmd = CommandSettings(keepMonitoringActive = true, foregroundPollingIntervalSeconds = 45)
        val root = exportAndParse(listOf(makeInstance()), commandSettings = cmd)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals(45, item.get("refresh_interval").asInt)
    }

    @Test
    fun `export refresh_interval uses workmanager interval when not keepMonitoringActive`() {
        val cmd  = CommandSettings(keepMonitoringActive = false)
        val notif = NotificationSettings(refreshIntervalMinutes = 20)
        val root = exportAndParse(listOf(makeInstance()), notificationSettings = notif, commandSettings = cmd)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals(1200, item.get("refresh_interval").asInt)  // 20 * 60
    }

    @Test
    fun `export filter fields map from FilterSettings`() {
        val fs = FilterSettings(
            hideHostsAndServicesWithDisabledNotifications = true,
            hideHostsAndServicesDownForDowntime = true,
            hideServicesOnDownHosts = false,
        )
        val root = exportAndParse(listOf(makeInstance()), filterSettings = fs)
        val item = root.getAsJsonArray("instances")[0].asJsonObject
        assertTrue(item.get("filter_notif").asBoolean)
        assertTrue(item.get("filter_maint").asBoolean)
        assertFalse(item.get("filter_down_hosts").asBoolean)
    }

    @Test
    fun `re-importing export without passwords preserves stored passwords`() {
        val original = makeInstance(id = "eid", password = "real-password")
        val json = exportInstancesToJson(
            instances = listOf(original),
            filterSettings = FilterSettings(),
            notificationSettings = NotificationSettings(),
            commandSettings = CommandSettings(),
            includePasswords = false,
            nowUtcIso = "2026-06-08T00:00:00Z",
        )
        val result = parseImportJson(json, listOf(original)) as ImportParseResult.Success
        assertEquals("real-password", result.preview.toUpdate[0].password)
    }
}
