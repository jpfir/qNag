// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.notifications

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [deriveVisualState] and [visualStateColor].
 *
 * Priority order (high → low): CRITICAL > FETCH_FAILURE > WARNING > UNKNOWN > OK.
 * A silent regression that keeps the indicator green while active alerts exist
 * would be caught here.
 */
class VisualStateTest {

    // ── Base states ────────────────────────────────────────────────────────────

    @Test
    fun `no problems and no failures returns OK`() {
        assertEquals(NotificationVisualState.OK, deriveVisualState(0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `warning only returns WARNING`() {
        assertEquals(NotificationVisualState.WARNING, deriveVisualState(0, 0, 0, 1, 0, 0))
    }

    @Test
    fun `unknown only returns UNKNOWN`() {
        assertEquals(NotificationVisualState.UNKNOWN, deriveVisualState(0, 0, 0, 0, 1, 0))
    }

    @Test
    fun `service critical returns CRITICAL`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(0, 0, 1, 0, 0, 0))
    }

    @Test
    fun `host down returns CRITICAL`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(1, 0, 0, 0, 0, 0))
    }

    @Test
    fun `host unreachable returns CRITICAL`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(0, 1, 0, 0, 0, 0))
    }

    @Test
    fun `fetch failure with no critical returns FETCH_FAILURE`() {
        assertEquals(NotificationVisualState.FETCH_FAILURE, deriveVisualState(0, 0, 0, 0, 0, 1))
    }

    // ── Priority ordering ──────────────────────────────────────────────────────

    @Test
    fun `critical overrides fetch failure`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(0, 0, 1, 0, 0, 1))
    }

    @Test
    fun `host down overrides fetch failure and warning`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(1, 0, 0, 1, 0, 1))
    }

    @Test
    fun `fetch failure overrides warning`() {
        assertEquals(NotificationVisualState.FETCH_FAILURE, deriveVisualState(0, 0, 0, 1, 0, 1))
    }

    @Test
    fun `fetch failure overrides unknown`() {
        assertEquals(NotificationVisualState.FETCH_FAILURE, deriveVisualState(0, 0, 0, 0, 1, 1))
    }

    @Test
    fun `warning overrides unknown`() {
        assertEquals(NotificationVisualState.WARNING, deriveVisualState(0, 0, 0, 1, 1, 0))
    }

    @Test
    fun `full priority chain CRITICAL gt FETCH_FAILURE gt WARNING gt UNKNOWN gt OK`() {
        // Verify the documented priority order from the top-of-file enum comment
        assertEquals(NotificationVisualState.CRITICAL,      deriveVisualState(1, 0, 0, 1, 1, 1))
        assertEquals(NotificationVisualState.FETCH_FAILURE, deriveVisualState(0, 0, 0, 1, 1, 1))
        assertEquals(NotificationVisualState.WARNING,       deriveVisualState(0, 0, 0, 1, 1, 0))
        assertEquals(NotificationVisualState.UNKNOWN,       deriveVisualState(0, 0, 0, 0, 1, 0))
        assertEquals(NotificationVisualState.OK,            deriveVisualState(0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `multiple critical counts still CRITICAL`() {
        assertEquals(NotificationVisualState.CRITICAL, deriveVisualState(3, 2, 5, 10, 0, 0))
    }

    // ── visualStateColor ───────────────────────────────────────────────────────

    @Test
    fun `every state maps to a distinct color`() {
        val colors = NotificationVisualState.values().map { visualStateColor(it) }
        assertEquals("Each visual state must have a unique color", colors.size, colors.toSet().size)
    }

    @Test
    fun `OK is green-dominant`() {
        val color = visualStateColor(NotificationVisualState.OK)
        val red   = (color shr 16) and 0xFF
        val green = (color shr 8)  and 0xFF
        assertTrue("OK should be green-dominant (green=$green red=$red)", green > red)
    }

    @Test
    fun `CRITICAL is red-dominant`() {
        val color = visualStateColor(NotificationVisualState.CRITICAL)
        val red   = (color shr 16) and 0xFF
        val green = (color shr 8)  and 0xFF
        assertTrue("CRITICAL should be red-dominant (red=$red green=$green)", red > green)
    }

    @Test
    fun `WARNING is neither pure red nor pure green`() {
        val color  = visualStateColor(NotificationVisualState.WARNING)
        val red    = (color shr 16) and 0xFF
        val green  = (color shr 8)  and 0xFF
        val blue   = color and 0xFF
        // Amber: significant red AND green, minimal blue
        assertTrue("WARNING amber should have high red",   red   > 200)
        assertTrue("WARNING amber should have high green", green > 140)
        assertTrue("WARNING amber should have low blue",   blue  < 80)
    }

    @Test
    fun `all colors are fully opaque`() {
        NotificationVisualState.values().forEach { state ->
            val alpha = (visualStateColor(state) ushr 24) and 0xFF
            assertEquals("$state color must be fully opaque", 0xFF, alpha)
        }
    }
}
