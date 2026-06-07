// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [evaluateNotificationDecision] covering state filtering,
 * soft-state suppression, downtime suppression, ACK handling, ACK re-notification,
 * Tier 2+ delay, and per-state Tier 2+ thresholds.
 *
 * Context is always null so no SharedPreferences are touched.
 * Tier 2+ age is injected via [NagiosProblem.lastStateChange] or [testProblemFirstSeenFn].
 * ACK age is injected via [NagiosProblem.acknowledgementTime] or [testAckFirstSeenFn].
 *
 * NOTE: ProblemAgeStore and AckAgeStore write-through paths (SharedPreferences) are not
 * covered here — they require an Android Context (Robolectric).  See docs/testing.md.
 */
class NotificationFilterTest {

    private val now = 1_700_000_000_000L

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun criticalService(
        acknowledged: Boolean = false,
        isSoftState: Boolean = false,
        scheduledDowntimeDepth: Int = 0,
        lastStateChange: Long? = null,
        acknowledgementTime: Long? = null,
        notificationsEnabled: Boolean = true,
    ) = NagiosProblem.ServiceProblem(
        hostName = "host1",
        serviceName = "svc1",
        pluginOutput = "CRITICAL",
        status = NagiosStatus.SERVICE_CRITICAL,
        acknowledged = acknowledged,
        isSoftState = isSoftState,
        scheduledDowntimeDepth = scheduledDowntimeDepth,
        lastStateChange = lastStateChange,
        acknowledgementTime = acknowledgementTime,
        notificationsEnabled = notificationsEnabled,
    )

    private fun warningService(
        acknowledged: Boolean = false,
        isSoftState: Boolean = false,
    ) = NagiosProblem.ServiceProblem(
        hostName = "host1",
        serviceName = "svc1",
        pluginOutput = "WARNING",
        status = NagiosStatus.SERVICE_WARNING,
        acknowledged = acknowledged,
        isSoftState = isSoftState,
    )

    private fun unknownService() = NagiosProblem.ServiceProblem(
        hostName = "host1",
        serviceName = "svc1",
        pluginOutput = "UNKNOWN",
        status = NagiosStatus.SERVICE_UNKNOWN,
    )

    private fun hostDown(
        isSoftState: Boolean = false,
        scheduledDowntimeDepth: Int = 0,
        lastStateChange: Long? = null,
    ) = NagiosProblem.HostProblem(
        hostName = "host1",
        pluginOutput = "HOST DOWN",
        status = NagiosStatus.HOST_DOWN,
        isSoftState = isSoftState,
        scheduledDowntimeDepth = scheduledDowntimeDepth,
        lastStateChange = lastStateChange,
    )

    private val defaults = NotificationSettings(
        notifyOnCriticalServices  = true,
        notifyOnWarningServices   = false,
        notifyOnUnknownServices   = true,
        notifyOnDownHosts         = true,
        notifyOnlyUnacknowledged  = true,
        notifyOnlyHardState       = true,
        respectDowntime           = true,
    )

    private fun decide(problem: NagiosProblem, settings: NotificationSettings) =
        evaluateNotificationDecision("inst", problem, settings, now, context = null)

    // ── 1. State enabled / disabled ────────────────────────────────────────────

    @Test
    fun `critical service allowed by default`() {
        val d = decide(criticalService(), defaults)
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOWED, d.reason)
    }

    @Test
    fun `warning service suppressed when notifyOnWarningServices is false`() {
        val d = decide(warningService(), defaults)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.STATE_DISABLED, d.reason)
    }

    @Test
    fun `warning service allowed when notifyOnWarningServices is true`() {
        val d = decide(warningService(), defaults.copy(notifyOnWarningServices = true))
        assertTrue(d.shouldNotify)
    }

    @Test
    fun `unknown service allowed by default`() {
        val d = decide(unknownService(), defaults)
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOWED, d.reason)
    }

    @Test
    fun `host down allowed by default`() {
        val d = decide(hostDown(), defaults)
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOWED, d.reason)
    }

    // ── 2. Soft-state suppression ──────────────────────────────────────────────

    @Test
    fun `soft state suppressed when notifyOnlyHardState is true`() {
        val d = decide(criticalService(isSoftState = true), defaults)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.SOFT_STATE_SUPPRESSED, d.reason)
    }

    @Test
    fun `soft state allowed when notifyOnlyHardState is false`() {
        val d = decide(criticalService(isSoftState = true), defaults.copy(notifyOnlyHardState = false))
        assertTrue(d.shouldNotify)
    }

    // ── 3. Downtime suppression ────────────────────────────────────────────────

    @Test
    fun `service in downtime suppressed when respectDowntime is true`() {
        val d = decide(criticalService(scheduledDowntimeDepth = 1), defaults)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.DOWNTIME_SUPPRESSED, d.reason)
    }

    @Test
    fun `host in downtime suppressed when respectDowntime is true`() {
        val d = decide(hostDown(scheduledDowntimeDepth = 1), defaults)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.DOWNTIME_SUPPRESSED, d.reason)
    }

    @Test
    fun `downtime not suppressed when respectDowntime is false`() {
        val d = decide(criticalService(scheduledDowntimeDepth = 1), defaults.copy(respectDowntime = false))
        assertTrue(d.shouldNotify)
    }

    @Test
    fun `service on host in downtime suppressed`() {
        val svc = NagiosProblem.ServiceProblem(
            hostName = "host1",
            serviceName = "svc1",
            pluginOutput = "",
            status = NagiosStatus.SERVICE_CRITICAL,
            hostScheduledDowntimeDepth = 1,
        )
        val d = evaluateNotificationDecision("inst", svc, defaults, now, context = null)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.DOWNTIME_SUPPRESSED, d.reason)
    }

    // ── 4. ACK suppression ─────────────────────────────────────────────────────

    @Test
    fun `acked problem suppressed when notifyOnlyUnacknowledged is true`() {
        val d = decide(criticalService(acknowledged = true), defaults)
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
    }

    @Test
    fun `acked problem allowed when notifyOnlyUnacknowledged is false`() {
        val d = decide(
            criticalService(acknowledged = true),
            defaults.copy(notifyOnlyUnacknowledged = false),
        )
        assertTrue(d.shouldNotify)
    }

    // ── 4b. ACK re-notification ────────────────────────────────────────────────

    @Test
    fun `acked re-notify suppressed before threshold`() {
        // Acked 30 min ago; threshold is 120 min
        val ackTime = now - 30 * 60_000L
        val d = decide(
            criticalService(acknowledged = true, acknowledgementTime = ackTime),
            defaults.copy(
                notifyOnlyUnacknowledged  = false,
                notifyAckedAfterEnabled   = true,
                notifyAckedAfterMinutes   = 120,
            ),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
        assertNotNull(d.ackAgeMs)
        assertTrue("ack age should be ~30 min", d.ackAgeMs!! < 120 * 60_000L)
        assertNotNull("remaining time should be set", d.notifyAfterMs)
    }

    @Test
    fun `acked re-notify eligible after threshold`() {
        // Acked 130 min ago; threshold is 120 min
        val ackTime = now - 130 * 60_000L
        val d = decide(
            criticalService(acknowledged = true, acknowledgementTime = ackTime),
            defaults.copy(
                notifyOnlyUnacknowledged  = false,
                notifyAckedAfterEnabled   = true,
                notifyAckedAfterMinutes   = 120,
            ),
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_RENOTIFY_ELIGIBLE, d.reason)
        assertNotNull(d.ackAgeMs)
        assertTrue("ack age should be ≥ 120 min", d.ackAgeMs!! >= 120 * 60_000L)
    }

    @Test
    fun `acked re-notify overrides notifyOnlyUnacknowledged after threshold`() {
        // notifyOnlyUnacknowledged=true should NOT block re-notify once the threshold is exceeded.
        // The re-notify branch fires before the basic guard, so the flag is bypassed.
        val ackTime = now - 130 * 60_000L
        val d = decide(
            criticalService(acknowledged = true, acknowledgementTime = ackTime),
            defaults.copy(
                notifyOnlyUnacknowledged = true,   // would suppress without re-notify
                notifyAckedAfterEnabled  = true,
                notifyAckedAfterMinutes  = 120,
            ),
        )
        assertTrue("Re-notify must fire even with notifyOnlyUnacknowledged=true", d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_RENOTIFY_ELIGIBLE, d.reason)
    }

    @Test
    fun `acked suppressed below threshold with notifyOnlyUnacknowledged true`() {
        // Same settings but ACK age < threshold — must stay suppressed.
        val ackTime = now - 30 * 60_000L
        val d = decide(
            criticalService(acknowledged = true, acknowledgementTime = ackTime),
            defaults.copy(
                notifyOnlyUnacknowledged = true,
                notifyAckedAfterEnabled  = true,
                notifyAckedAfterMinutes  = 120,
            ),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
    }

    @Test
    fun `acked re-notify with no acknowledgementTime falls through to basic guard`() {
        // notifyAckedAfterEnabled=true, but no Nagios ack time and no context → no ackFirstSeen
        val d = decide(
            criticalService(acknowledged = true, acknowledgementTime = null),
            defaults.copy(
                notifyOnlyUnacknowledged = true,
                notifyAckedAfterEnabled  = true,
                notifyAckedAfterMinutes  = 120,
            ),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
    }

    // ── 5. Tier 2+ delay ──────────────────────────────────────────────────────

    @Test
    fun `tier2plus disabled means immediate eligibility`() {
        // Alert is only 1 minute old, but Tier 2+ is off
        val d = decide(
            criticalService(lastStateChange = now - 60_000L),
            defaults.copy(notifyOnlyUnacknowledged = false, tier2PlusEnabled = false),
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ALLOWED, d.reason)
    }

    @Test
    fun `tier2plus enabled suppresses alert before delay`() {
        // Alert 3 min old; delay is 5 min
        val d = decide(
            criticalService(lastStateChange = now - 3 * 60_000L),
            defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_WAITING, d.reason)
        assertNotNull(d.alertAgeMs)
        assertNotNull(d.notifyAfterMs)
    }

    @Test
    fun `tier2plus enabled allows alert after delay`() {
        // Alert 10 min old; delay is 5 min
        val d = decide(
            criticalService(lastStateChange = now - 10 * 60_000L),
            defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_ELIGIBLE, d.reason)
        assertNotNull(d.alertAgeMs)
        assertTrue("alertAgeMs should be ≥ 5 min", d.alertAgeMs!! >= 5 * 60_000L)
    }

    @Test
    fun `tier2plus with unknown age and no context allows notification`() {
        // No lastStateChange, no context → resolveAlertAgeMs returns null → allow
        val d = decide(
            criticalService(lastStateChange = null),
            defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_ELIGIBLE, d.reason)
        assertNull("alertAgeMs should be null when age is unknown", d.alertAgeMs)
    }

    // ── 5b. tier2DelayMs per-state selection ──────────────────────────────────

    @Test
    fun `per-state delay uses host-down threshold`() {
        val settings = defaults.copy(
            tier2PlusEnabled            = true,
            tier2PlusUsePerStateDelays  = true,
            tier2HostDownDelayMinutes   = 10,
            tier2ServiceCriticalDelayMinutes = 2,
        )
        assertEquals(10 * 60_000L, tier2DelayMs(hostDown(), settings))
    }

    @Test
    fun `per-state delay uses service-critical threshold`() {
        val settings = defaults.copy(
            tier2PlusEnabled            = true,
            tier2PlusUsePerStateDelays  = true,
            tier2HostDownDelayMinutes   = 10,
            tier2ServiceCriticalDelayMinutes = 2,
        )
        assertEquals(2 * 60_000L, tier2DelayMs(criticalService(), settings))
    }

    @Test
    fun `per-state delay uses service-warning threshold`() {
        val settings = defaults.copy(
            notifyOnWarningServices         = true,
            tier2PlusEnabled                = true,
            tier2PlusUsePerStateDelays      = true,
            tier2ServiceWarningDelayMinutes  = 15,
            tier2ServiceCriticalDelayMinutes = 2,
        )
        assertEquals(15 * 60_000L, tier2DelayMs(warningService(), settings))
    }

    @Test
    fun `per-state delay uses service-unknown threshold`() {
        val settings = defaults.copy(
            tier2PlusEnabled                = true,
            tier2PlusUsePerStateDelays      = true,
            tier2ServiceUnknownDelayMinutes  = 10,
            tier2ServiceCriticalDelayMinutes = 2,
        )
        assertEquals(10 * 60_000L, tier2DelayMs(unknownService(), settings))
    }

    @Test
    fun `global delay used when per-state is disabled`() {
        val settings = defaults.copy(
            tier2PlusEnabled            = true,
            tier2PlusUsePerStateDelays  = false,
            tier2PlusDelayMinutes       = 7,
            tier2HostDownDelayMinutes   = 10,
            tier2ServiceCriticalDelayMinutes = 2,
        )
        assertEquals(7 * 60_000L, tier2DelayMs(criticalService(), settings))
        assertEquals(7 * 60_000L, tier2DelayMs(hostDown(), settings))
    }

    // ── 5c. Tier 2+ fallback: injected firstSeen (no lastStateChange, no context) ──────

    @Test
    fun `tier2plus injected firstSeen below delay returns TIER2_WAITING`() {
        // No lastStateChange set. The local first-seen resolver says 3 min ago.
        // With a 5 min delay the problem should still be waiting.
        val firstSeen = now - 3 * 60_000L
        val d = evaluateNotificationDecision(
            instanceId = "inst",
            problem = criticalService(lastStateChange = null),
            settings = defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
            now = now,
            context = null,
            testProblemFirstSeenFn = { _, _ -> firstSeen },
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_WAITING, d.reason)
        assertNotNull(d.alertAgeMs)
        assertNotNull(d.notifyAfterMs)
        assertTrue("alertAgeMs should be ~3 min", d.alertAgeMs!! < 5 * 60_000L)
    }

    @Test
    fun `tier2plus injected firstSeen after delay returns TIER2_ELIGIBLE`() {
        val firstSeen = now - 10 * 60_000L
        val d = evaluateNotificationDecision(
            instanceId = "inst",
            problem = criticalService(lastStateChange = null),
            settings = defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
            now = now,
            context = null,
            testProblemFirstSeenFn = { _, _ -> firstSeen },
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_ELIGIBLE, d.reason)
        assertNotNull(d.alertAgeMs)
        assertTrue("alertAgeMs should be ≥ 5 min", d.alertAgeMs!! >= 5 * 60_000L)
    }

    @Test
    fun `tier2plus injected firstSeen returning null means unknown age allows notification`() {
        // firstSeenFn returns null → unknown age → TIER2_ELIGIBLE (allow through)
        val d = evaluateNotificationDecision(
            instanceId = "inst",
            problem = criticalService(lastStateChange = null),
            settings = defaults.copy(
                notifyOnlyUnacknowledged = false,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
            now = now,
            context = null,
            testProblemFirstSeenFn = { _, _ -> null },
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_ELIGIBLE, d.reason)
        assertNull(d.alertAgeMs)
    }

    @Test
    fun `status change resets tier2plus age — new lastStateChange starts fresh`() {
        // Old problem, 60 min lastStateChange → TIER2_ELIGIBLE
        val oldD = decide(
            criticalService(lastStateChange = now - 60 * 60_000L),
            defaults.copy(notifyOnlyUnacknowledged = false, tier2PlusEnabled = true, tier2PlusDelayMinutes = 5),
        )
        assertTrue("Old problem must be eligible", oldD.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_ELIGIBLE, oldD.reason)

        // Same problem escalated 1 min ago → TIER2_WAITING (fresh start)
        val newD = decide(
            criticalService(lastStateChange = now - 60_000L),
            defaults.copy(notifyOnlyUnacknowledged = false, tier2PlusEnabled = true, tier2PlusDelayMinutes = 5),
        )
        assertFalse("Newly escalated problem must be waiting", newD.shouldNotify)
        assertEquals(NotificationDecisionReason.TIER2_WAITING, newD.reason)
    }

    // ── 4c. ACK injected firstSeen ────────────────────────────────────────────

    @Test
    fun `ack injected firstSeen after threshold returns ACKED_RENOTIFY_ELIGIBLE`() {
        // No acknowledgementTime on the problem, no context — but the test hook supplies firstSeen.
        val firstSeen = now - 130 * 60_000L
        val d = evaluateNotificationDecision(
            instanceId = "inst",
            problem = criticalService(acknowledged = true, acknowledgementTime = null),
            settings = defaults.copy(
                notifyOnlyUnacknowledged = false,
                notifyAckedAfterEnabled  = true,
                notifyAckedAfterMinutes  = 120,
            ),
            now = now,
            context = null,
            testAckFirstSeenFn = { _, _ -> firstSeen },
        )
        assertTrue(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_RENOTIFY_ELIGIBLE, d.reason)
        assertNotNull(d.ackAgeMs)
        assertTrue("ackAgeMs should be ≥ 120 min", d.ackAgeMs!! >= 120 * 60_000L)
    }

    @Test
    fun `ack injected firstSeen below threshold returns ACKED_SUPPRESSED`() {
        val firstSeen = now - 30 * 60_000L
        val d = evaluateNotificationDecision(
            instanceId = "inst",
            problem = criticalService(acknowledged = true, acknowledgementTime = null),
            settings = defaults.copy(
                notifyOnlyUnacknowledged = false,
                notifyAckedAfterEnabled  = true,
                notifyAckedAfterMinutes  = 120,
            ),
            now = now,
            context = null,
            testAckFirstSeenFn = { _, _ -> firstSeen },
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
        assertNotNull(d.notifyAfterMs)
    }

    // ── Decision ordering: downtime evaluated before Tier 2+ ──────────────────

    @Test
    fun `downtime suppressed even when tier2plus delay is satisfied`() {
        // Alert 60 min old, Tier 2+ delay is 5 min — but it's in downtime
        val d = decide(
            criticalService(scheduledDowntimeDepth = 1, lastStateChange = now - 60 * 60_000L),
            defaults.copy(tier2PlusEnabled = true, tier2PlusDelayMinutes = 5),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.DOWNTIME_SUPPRESSED, d.reason)
    }

    @Test
    fun `acked suppressed even when tier2plus delay is satisfied`() {
        // Alert 60 min old, Tier 2+ would pass — but it's acked and onlyUnacked=true
        val d = decide(
            criticalService(acknowledged = true, lastStateChange = now - 60 * 60_000L),
            defaults.copy(
                notifyOnlyUnacknowledged = true,
                tier2PlusEnabled         = true,
                tier2PlusDelayMinutes    = 5,
            ),
        )
        assertFalse(d.shouldNotify)
        assertEquals(NotificationDecisionReason.ACKED_SUPPRESSED, d.reason)
    }
}
