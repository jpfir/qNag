// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [parseEpochMs].
 *
 * Nagios statusjson returns timestamps as 10-digit epoch seconds or occasionally
 * 13-digit epoch millis.  The function must handle Int, Long, Double, and numeric
 * String values, and return null for missing, zero, or unparseable values.
 */
class NagiosTimestampTest {

    // ── Null / zero / negative ─────────────────────────────────────────────────

    @Test
    fun `null input returns null`() = assertNull(parseEpochMs(null))

    @Test
    fun `zero Int returns null`() = assertNull(parseEpochMs(0))

    @Test
    fun `zero Long returns null`() = assertNull(parseEpochMs(0L))

    @Test
    fun `negative Int returns null`() = assertNull(parseEpochMs(-1))

    @Test
    fun `negative Long returns null`() = assertNull(parseEpochMs(-1_000L))

    // ── 10-digit epoch seconds → converted to millis ──────────────────────────

    @Test
    fun `10-digit Int seconds converted to millis`() {
        assertEquals(1_700_000_000_000L, parseEpochMs(1_700_000_000))
    }

    @Test
    fun `10-digit Long seconds converted to millis`() {
        assertEquals(1_700_000_000_000L, parseEpochMs(1_700_000_000L))
    }

    @Test
    fun `10-digit Double seconds converted to millis`() {
        assertEquals(1_700_000_000_000L, parseEpochMs(1_700_000_000.0))
    }

    @Test
    fun `10-digit numeric String seconds converted to millis`() {
        assertEquals(1_700_000_000_000L, parseEpochMs("1700000000"))
    }

    @Test
    fun `boundary value 9_999_999_999 treated as seconds`() {
        // Just below the 10_000_000_000 threshold → seconds
        assertEquals(9_999_999_999_000L, parseEpochMs(9_999_999_999L))
    }

    // ── 13-digit epoch millis → returned unchanged ─────────────────────────────

    @Test
    fun `13-digit Long millis returned unchanged`() {
        assertEquals(1_700_000_000_000L, parseEpochMs(1_700_000_000_000L))
    }

    @Test
    fun `13-digit numeric String millis returned unchanged`() {
        assertEquals(1_700_000_000_000L, parseEpochMs("1700000000000"))
    }

    @Test
    fun `boundary value 10_000_000_000 treated as millis`() {
        // Exactly at the threshold → millis (no multiplication)
        assertEquals(10_000_000_000L, parseEpochMs(10_000_000_000L))
    }

    // ── Invalid inputs ─────────────────────────────────────────────────────────

    @Test
    fun `non-numeric string returns null`() = assertNull(parseEpochMs("not-a-number"))

    @Test
    fun `empty string returns null`() = assertNull(parseEpochMs(""))

    @Test
    fun `blank string returns null`() = assertNull(parseEpochMs("   "))

    @Test
    fun `unrecognised type returns null`() = assertNull(parseEpochMs(listOf(1, 2, 3)))

    @Test
    fun `Boolean type returns null`() = assertNull(parseEpochMs(true))

    // ── Smoke: realistic Nagios values ────────────────────────────────────────

    @Test
    fun `realistic Nagios last_check epoch seconds parses correctly`() {
        // Typical Nagios statusjson value: seconds since epoch
        val epochSeconds = 1_716_000_000   // ~May 2024
        val result = parseEpochMs(epochSeconds)
        assertNotNull(result)
        assertEquals(epochSeconds.toLong() * 1000L, result)
    }

    @Test
    fun `zero string returns null`() = assertNull(parseEpochMs("0"))
}
