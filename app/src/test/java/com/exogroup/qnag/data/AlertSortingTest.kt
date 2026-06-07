// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [severityRank] and [problemComparator] from AlertSorting.kt.
 *
 * Verifies the documented dashboard sort order:
 *  1. Severity (HOST_DOWN first, SERVICE_WARNING last)
 *  2. NEW problems (state changed < 15 min) before OLD
 *  3. Unacked / not-in-downtime before acked / in-downtime
 *  4. Notifications-enabled before disabled
 *  5. Newer state-change first within band
 *  6. Alphabetic host / service name as tiebreaker
 */
class AlertSortingTest {

    private val now    = 1_700_000_000_000L
    private val recent = now - 5 * 60_000L    // 5 min ago — NEW
    private val old    = now - 60 * 60_000L   // 60 min ago — not NEW

    private fun svc(
        host: String = "host",
        service: String = "svc",
        status: Int = NagiosStatus.SERVICE_CRITICAL,
        acknowledged: Boolean = false,
        scheduledDowntimeDepth: Int = 0,
        notificationsEnabled: Boolean = true,
        lastStateChange: Long? = null,
    ) = NagiosProblem.ServiceProblem(
        hostName = host, serviceName = service, pluginOutput = "", status = status,
        acknowledged = acknowledged, scheduledDowntimeDepth = scheduledDowntimeDepth,
        notificationsEnabled = notificationsEnabled, lastStateChange = lastStateChange,
    )

    private fun host(
        host: String = "host",
        status: Int = NagiosStatus.HOST_DOWN,
        acknowledged: Boolean = false,
        scheduledDowntimeDepth: Int = 0,
        notificationsEnabled: Boolean = true,
        lastStateChange: Long? = null,
    ) = NagiosProblem.HostProblem(
        hostName = host, pluginOutput = "", status = status,
        acknowledged = acknowledged, scheduledDowntimeDepth = scheduledDowntimeDepth,
        notificationsEnabled = notificationsEnabled, lastStateChange = lastStateChange,
    )

    private fun sort(vararg problems: NagiosProblem): List<NagiosProblem> =
        problems.toList().sortedWith(problemComparator(now))

    // ── severityRank values ────────────────────────────────────────────────────

    @Test
    fun `HOST_DOWN has rank 0`() = assertEquals(0, severityRank(host(status = NagiosStatus.HOST_DOWN)))

    @Test
    fun `SERVICE_CRITICAL has rank 1`() = assertEquals(1, severityRank(svc(status = NagiosStatus.SERVICE_CRITICAL)))

    @Test
    fun `HOST_UNREACHABLE has rank 2`() = assertEquals(2, severityRank(host(status = NagiosStatus.HOST_UNREACHABLE)))

    @Test
    fun `SERVICE_UNKNOWN has rank 3`() = assertEquals(3, severityRank(svc(status = NagiosStatus.SERVICE_UNKNOWN)))

    @Test
    fun `SERVICE_WARNING has rank 4`() = assertEquals(4, severityRank(svc(status = NagiosStatus.SERVICE_WARNING)))

    @Test
    fun `severity order is HOST_DOWN before SERVICE_CRITICAL before WARNING`() {
        val ranks = listOf(
            severityRank(host(status = NagiosStatus.HOST_DOWN)),
            severityRank(svc(status = NagiosStatus.SERVICE_CRITICAL)),
            severityRank(host(status = NagiosStatus.HOST_UNREACHABLE)),
            severityRank(svc(status = NagiosStatus.SERVICE_UNKNOWN)),
            severityRank(svc(status = NagiosStatus.SERVICE_WARNING)),
        )
        assertEquals(ranks, ranks.sorted())
    }

    // ── Sort: severity ─────────────────────────────────────────────────────────

    @Test
    fun `host down sorts before service critical`() {
        val sorted = sort(svc(status = NagiosStatus.SERVICE_CRITICAL), host(status = NagiosStatus.HOST_DOWN))
        assertTrue(sorted[0] is NagiosProblem.HostProblem)
        assertEquals(NagiosStatus.HOST_DOWN, sorted[0].status)
    }

    @Test
    fun `service critical sorts before service warning`() {
        val sorted = sort(
            svc(service = "warn", status = NagiosStatus.SERVICE_WARNING),
            svc(service = "crit", status = NagiosStatus.SERVICE_CRITICAL),
        )
        assertEquals(NagiosStatus.SERVICE_CRITICAL, sorted[0].status)
    }

    @Test
    fun `old critical sorts before new warning`() {
        val sorted = sort(
            svc(service = "warn", status = NagiosStatus.SERVICE_WARNING, lastStateChange = recent),
            svc(service = "crit", status = NagiosStatus.SERVICE_CRITICAL, lastStateChange = old),
        )
        assertEquals(NagiosStatus.SERVICE_CRITICAL, sorted[0].status)
    }

    // ── Sort: recency ──────────────────────────────────────────────────────────

    @Test
    fun `new problem sorts before old problem within same severity`() {
        val sorted = sort(
            svc(service = "old", lastStateChange = old),
            svc(service = "new", lastStateChange = recent),
        )
        assertEquals("new", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    @Test
    fun `problem with no lastStateChange sorts as old`() {
        // null lastStateChange → treated as 0 → sorts as oldest
        val sorted = sort(
            svc(service = "null-ts", lastStateChange = null),
            svc(service = "new",     lastStateChange = recent),
        )
        assertEquals("new", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    // ── Sort: ack / downtime ───────────────────────────────────────────────────

    @Test
    fun `unacked sorts before acked within same severity`() {
        val sorted = sort(
            svc(service = "acked",   acknowledged = true,  lastStateChange = old),
            svc(service = "unacked", acknowledged = false, lastStateChange = old),
        )
        assertEquals("unacked", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    @Test
    fun `no-downtime sorts before in-downtime within same severity`() {
        val sorted = sort(
            svc(service = "dt",     scheduledDowntimeDepth = 1, lastStateChange = old),
            svc(service = "normal", scheduledDowntimeDepth = 0, lastStateChange = old),
        )
        assertEquals("normal", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    // ── Sort: notifications enabled ────────────────────────────────────────────

    @Test
    fun `notifications-enabled sorts before disabled within same severity`() {
        val sorted = sort(
            svc(service = "notif-off", notificationsEnabled = false, lastStateChange = old),
            svc(service = "notif-on",  notificationsEnabled = true,  lastStateChange = old),
        )
        assertEquals("notif-on", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    // ── Sort: alphabetic tiebreaker ────────────────────────────────────────────

    @Test
    fun `problems with identical rank sort alphabetically by host`() {
        val sorted = sort(
            svc(host = "zhost", service = "svc", lastStateChange = old),
            svc(host = "ahost", service = "svc", lastStateChange = old),
        )
        assertEquals("ahost", sorted[0].hostName)
    }

    @Test
    fun `problems with same host sort alphabetically by service name`() {
        val sorted = sort(
            svc(host = "host", service = "z-svc", lastStateChange = old),
            svc(host = "host", service = "a-svc", lastStateChange = old),
        )
        assertEquals("a-svc", (sorted[0] as NagiosProblem.ServiceProblem).serviceName)
    }
}
