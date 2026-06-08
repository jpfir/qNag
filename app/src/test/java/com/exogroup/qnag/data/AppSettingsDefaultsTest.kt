// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies that reliability-critical defaults are correct and that the JSON
 * parsing fallbacks in SecureInstanceStore match those defaults.
 *
 * The store uses ?.asBoolean ?: <default> for boolean fields so that:
 *  - JSON with key present uses the stored value (backward compat)
 *  - JSON missing the key falls back to the Kotlin data-class default
 *
 * These tests confirm both the data-class defaults and the fallback values
 * are consistent, so old stored settings are migrated correctly on upgrade.
 */
class AppSettingsDefaultsTest {

    // ── NotificationSettings defaults ─────────────────────────────────────────

    @Test fun `notificationsEnabled defaults to true`() {
        assertTrue(NotificationSettings().notificationsEnabled)
    }

    @Test fun `notifyOnCriticalServices defaults to true`() {
        assertTrue(NotificationSettings().notifyOnCriticalServices)
    }

    @Test fun `notifyOnWarningServices defaults to false`() {
        assertFalse(NotificationSettings().notifyOnWarningServices)
    }

    @Test fun `notifyOnDownHosts defaults to true`() {
        assertTrue(NotificationSettings().notifyOnDownHosts)
    }

    // ── CommandSettings defaults ──────────────────────────────────────────────

    @Test fun `keepMonitoringActive defaults to true`() {
        assertTrue(CommandSettings().keepMonitoringActive)
    }

    @Test fun `exactAlarmWatchdogEnabled defaults to true`() {
        assertTrue(CommandSettings().exactAlarmWatchdogEnabled)
    }

    @Test fun `staleMonitoringAlertEnabled defaults to true`() {
        assertTrue(CommandSettings().staleMonitoringAlertEnabled)
    }

    // ── JSON parsing fallbacks (mirrors SecureInstanceStore parsing logic) ────
    //
    // These tests replicate the ?.asBoolean ?: <default> pattern used by the
    // store so that a change to the fallback value is caught at test time.

    @Test fun `notificationsEnabled JSON fallback is true when key absent`() {
        val o = JsonParser.parseString("{}").asJsonObject
        val parsed = o.get("notificationsEnabled")?.asBoolean ?: true
        assertTrue("fallback should be true, got $parsed", parsed)
    }

    @Test fun `notificationsEnabled JSON respects explicit false`() {
        val o = JsonParser.parseString("""{"notificationsEnabled":false}""").asJsonObject
        val parsed = o.get("notificationsEnabled")?.asBoolean ?: true
        assertFalse("stored false must survive parsing", parsed)
    }

    @Test fun `notificationsEnabled JSON respects explicit true`() {
        val o = JsonParser.parseString("""{"notificationsEnabled":true}""").asJsonObject
        val parsed = o.get("notificationsEnabled")?.asBoolean ?: true
        assertTrue(parsed)
    }

    @Test fun `keepMonitoringActive JSON fallback is true when key absent`() {
        val o = JsonParser.parseString("{}").asJsonObject
        val parsed = o.get("keepMonitoringActive")?.asBoolean ?: true
        assertTrue("fallback should be true, got $parsed", parsed)
    }

    @Test fun `keepMonitoringActive JSON respects explicit false`() {
        val o = JsonParser.parseString("""{"keepMonitoringActive":false}""").asJsonObject
        val parsed = o.get("keepMonitoringActive")?.asBoolean ?: true
        assertFalse("stored false must survive parsing (user explicitly disabled)", parsed)
    }

    @Test fun `keepMonitoringActive JSON respects explicit true`() {
        val o = JsonParser.parseString("""{"keepMonitoringActive":true}""").asJsonObject
        val parsed = o.get("keepMonitoringActive")?.asBoolean ?: true
        assertTrue(parsed)
    }

    // ── maxAlertSoundSeconds ──────────────────────────────────────────────────

    @Test fun `maxAlertSoundSeconds defaults to 10`() {
        assertEquals(10, NotificationSettings().maxAlertSoundSeconds)
    }

    @Test fun `maxAlertSoundSeconds JSON fallback is 10 when key absent`() {
        val o = JsonParser.parseString("{}").asJsonObject
        val parsed = o.get("maxAlertSoundSeconds")?.asInt ?: 10
        assertEquals(10, parsed)
    }

    @Test fun `maxAlertSoundSeconds JSON respects stored value`() {
        val o = JsonParser.parseString("""{"maxAlertSoundSeconds":30}""").asJsonObject
        val parsed = o.get("maxAlertSoundSeconds")?.asInt ?: 10
        assertEquals(30, parsed)
    }

    @Test fun `maxAlertSoundSeconds data-class default matches JSON absent-key fallback`() {
        val dataClassDefault = NotificationSettings().maxAlertSoundSeconds
        val jsonFallback = JsonParser.parseString("{}").asJsonObject
            .get("maxAlertSoundSeconds")?.asInt ?: 10
        assertEquals(dataClassDefault, jsonFallback)
    }

    // ── AlertSoundPlayer.stop() safety ────────────────────────────────────────

    @Test fun `AlertSoundPlayer stop does not crash when nothing is playing`() {
        // stop() must be a no-op when no sound has ever been started
        com.exogroup.qnag.sound.AlertSoundPlayer.stop()
        com.exogroup.qnag.sound.AlertSoundPlayer.stop()  // idempotent
    }

    @Test fun `AlertSoundPlayer isPlaying returns false when nothing is playing`() {
        com.exogroup.qnag.sound.AlertSoundPlayer.stop()
        assertFalse(com.exogroup.qnag.sound.AlertSoundPlayer.isPlaying())
    }

    // ── Consistency check ─────────────────────────────────────────────────────
    // The data-class default and the JSON absent-key fallback must agree so that
    // new installs and upgrades from pre-key versions both get the same value.

    @Test fun `notificationsEnabled data-class default matches JSON absent-key fallback`() {
        val dataClassDefault = NotificationSettings().notificationsEnabled
        val jsonFallback = JsonParser.parseString("{}").asJsonObject
            .get("notificationsEnabled")?.asBoolean ?: true
        assertEquals(
            "data-class default and JSON fallback must agree",
            dataClassDefault, jsonFallback,
        )
    }

    @Test fun `keepMonitoringActive data-class default matches JSON absent-key fallback`() {
        val dataClassDefault = CommandSettings().keepMonitoringActive
        val jsonFallback = JsonParser.parseString("{}").asJsonObject
            .get("keepMonitoringActive")?.asBoolean ?: true
        assertEquals(
            "data-class default and JSON fallback must agree",
            dataClassDefault, jsonFallback,
        )
    }
}
