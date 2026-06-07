// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the fingerprint / notification-ID helpers in NotificationFilter.kt and
 * the key-format contracts of [AckAgeStore] and [ProblemAgeStore].
 *
 * The U+001F unit separator is load-bearing: it prevents false prefix matches
 * because it cannot appear in UUIDs or Nagios host/service names.
 */
class FingerprintTest {

    private val sep = ""

    private fun svc(
        host: String = "host1",
        service: String = "svc1",
        status: Int = NagiosStatus.SERVICE_CRITICAL,
    ) = NagiosProblem.ServiceProblem(
        hostName = host,
        serviceName = service,
        pluginOutput = "",
        status = status,
    )

    private fun host(
        host: String = "host1",
        status: Int = NagiosStatus.HOST_DOWN,
    ) = NagiosProblem.HostProblem(
        hostName = host,
        pluginOutput = "",
        status = status,
    )

    // ── problemFingerprint ────────────────────────────────────────────────────

    @Test
    fun `problemFingerprint contains U+001F separator`() {
        assertTrue(problemFingerprint("inst", svc()).contains(sep))
    }

    @Test
    fun `problemFingerprint structure is instanceId SEP uniqueId SEP status`() {
        val problem = svc("host1", "svc1", NagiosStatus.SERVICE_CRITICAL)
        val expected = "inst$sep${problem.uniqueId}$sep${NagiosStatus.SERVICE_CRITICAL}"
        assertEquals(expected, problemFingerprint("inst", problem))
    }

    @Test
    fun `problemFingerprint differs across statuses for same host+service`() {
        val fpWarn = problemFingerprint("inst", svc(status = NagiosStatus.SERVICE_WARNING))
        val fpCrit = problemFingerprint("inst", svc(status = NagiosStatus.SERVICE_CRITICAL))
        assertNotEquals(fpWarn, fpCrit)
    }

    @Test
    fun `problemFingerprint differs across instances for same problem`() {
        val fp1 = problemFingerprint("inst1", svc())
        val fp2 = problemFingerprint("inst2", svc())
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `problemFingerprint includes instanceId, uniqueId, and status`() {
        val problem = svc("host1", "svc1", NagiosStatus.SERVICE_CRITICAL)
        val fp = problemFingerprint("myInst", problem)
        assertTrue(fp.contains("myInst"))
        assertTrue(fp.contains(problem.uniqueId))
        assertTrue(fp.contains(NagiosStatus.SERVICE_CRITICAL.toString()))
    }

    // ── instanceFingerprintPrefix ─────────────────────────────────────────────

    @Test
    fun `instanceFingerprintPrefix ends with U+001F separator`() {
        assertTrue(instanceFingerprintPrefix("inst1").endsWith(sep))
    }

    @Test
    fun `instanceFingerprintPrefix matches fingerprints from same instance`() {
        val prefix = instanceFingerprintPrefix("inst1")
        assertTrue(problemFingerprint("inst1", svc()).startsWith(prefix))
    }

    @Test
    fun `instanceFingerprintPrefix does not match fingerprints from different instance`() {
        val prefix = instanceFingerprintPrefix("inst1")
        assertFalse(problemFingerprint("inst2", svc()).startsWith(prefix))
    }

    // ── notificationId ────────────────────────────────────────────────────────

    @Test
    fun `notificationId is stable for same instance and problem`() {
        val p = svc()
        assertEquals(notificationId("inst", p), notificationId("inst", p))
    }

    @Test
    fun `notificationId is non-negative`() {
        assertTrue(notificationId("inst", svc()) >= 0)
        assertTrue(notificationId("inst", host()) >= 0)
    }

    @Test
    fun `notificationId is stable across status changes for same host+service`() {
        // Notification slot must not change when WARNING escalates to CRITICAL
        val warn = svc("host1", "svc1", NagiosStatus.SERVICE_WARNING)
        val crit = svc("host1", "svc1", NagiosStatus.SERVICE_CRITICAL)
        assertEquals(notificationId("inst", warn), notificationId("inst", crit))
    }

    @Test
    fun `notificationId differs for different instances`() {
        assertNotEquals(notificationId("inst1", svc()), notificationId("inst2", svc()))
    }

    @Test
    fun `notificationId differs for different services on same host`() {
        assertNotEquals(
            notificationId("inst", svc("host1", "svc1")),
            notificationId("inst", svc("host1", "svc2")),
        )
    }

    // ── fetchFailureNotificationId ────────────────────────────────────────────

    @Test
    fun `fetchFailureNotificationId is stable for same instance`() {
        assertEquals(fetchFailureNotificationId("inst1"), fetchFailureNotificationId("inst1"))
    }

    @Test
    fun `fetchFailureNotificationId is non-negative`() {
        assertTrue(fetchFailureNotificationId("inst1") >= 0)
    }

    @Test
    fun `fetchFailureNotificationId differs for different instances`() {
        assertNotEquals(fetchFailureNotificationId("inst1"), fetchFailureNotificationId("inst2"))
    }

    @Test
    fun `fetchFailureNotificationId does not collide with problem notificationId`() {
        // The "fetch_fail" prefix ensures fetch-failure IDs occupy a separate space
        val hostProblem = host("inst1")
        assertNotEquals(fetchFailureNotificationId("inst1"), notificationId("inst1", hostProblem))
    }

    // ── AckAgeStore key format ────────────────────────────────────────────────

    @Test
    fun `AckAgeStore key contains U+001F separator`() {
        assertTrue(AckAgeStore.key("inst1", svc()).contains(sep))
    }

    @Test
    fun `AckAgeStore key contains instanceId and uniqueId`() {
        val problem = svc("host1", "svc1")
        val key = AckAgeStore.key("inst1", problem)
        assertTrue(key.contains("inst1"))
        assertTrue(key.contains(problem.uniqueId))
    }

    @Test
    fun `AckAgeStore key does not include status so ACK persists across escalation`() {
        val warn = svc("host1", "svc1", NagiosStatus.SERVICE_WARNING)
        val crit = svc("host1", "svc1", NagiosStatus.SERVICE_CRITICAL)
        assertEquals(AckAgeStore.key("inst", warn), AckAgeStore.key("inst", crit))
    }

    @Test
    fun `AckAgeStore key differs for different instances`() {
        assertNotEquals(AckAgeStore.key("inst1", svc()), AckAgeStore.key("inst2", svc()))
    }

    // ── ProblemAgeStore key format ────────────────────────────────────────────

    @Test
    fun `ProblemAgeStore key contains U+001F separator`() {
        assertTrue(ProblemAgeStore.key("inst1", svc()).contains(sep))
    }

    @Test
    fun `ProblemAgeStore key includes status so Tier2+ age resets on escalation`() {
        val warn = svc("host1", "svc1", NagiosStatus.SERVICE_WARNING)
        val crit = svc("host1", "svc1", NagiosStatus.SERVICE_CRITICAL)
        assertNotEquals(ProblemAgeStore.key("inst", warn), ProblemAgeStore.key("inst", crit))
    }

    @Test
    fun `ProblemAgeStore key contains instanceId and uniqueId`() {
        val problem = svc("host1", "svc1")
        val key = ProblemAgeStore.key("inst1", problem)
        assertTrue(key.contains("inst1"))
        assertTrue(key.contains(problem.uniqueId))
    }

    @Test
    fun `ProblemAgeStore key differs for different instances`() {
        assertNotEquals(ProblemAgeStore.key("inst1", svc()), ProblemAgeStore.key("inst2", svc()))
    }
}
