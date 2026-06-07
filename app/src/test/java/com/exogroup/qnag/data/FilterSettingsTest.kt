// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [applyFilters] and [validateRegex].
 *
 * Covers status-based quick filters (C/W/D equivalents), common state filters,
 * service-on-host filters, regex filters (normal and reversed), and edge cases.
 */
class FilterSettingsTest {

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun critSvc(
        host: String = "host1",
        service: String = "svc1",
        acknowledged: Boolean = false,
        notificationsEnabled: Boolean = true,
        scheduledDowntimeDepth: Int = 0,
        isFlapping: Boolean = false,
        isSoftState: Boolean = false,
        hostAcknowledged: Boolean = false,
        hostStatus: Int? = null,
        hostScheduledDowntimeDepth: Int = 0,
    ) = NagiosProblem.ServiceProblem(
        hostName = host,
        serviceName = service,
        pluginOutput = "",
        status = NagiosStatus.SERVICE_CRITICAL,
        acknowledged = acknowledged,
        notificationsEnabled = notificationsEnabled,
        scheduledDowntimeDepth = scheduledDowntimeDepth,
        isFlapping = isFlapping,
        isSoftState = isSoftState,
        hostAcknowledged = hostAcknowledged,
        hostStatus = hostStatus,
        hostScheduledDowntimeDepth = hostScheduledDowntimeDepth,
    )

    private fun warnSvc(host: String = "host1", service: String = "svc1") =
        NagiosProblem.ServiceProblem(
            hostName = host, serviceName = service, pluginOutput = "",
            status = NagiosStatus.SERVICE_WARNING,
        )

    private fun unkSvc(host: String = "host1") =
        NagiosProblem.ServiceProblem(
            hostName = host, serviceName = "svc_unk", pluginOutput = "",
            status = NagiosStatus.SERVICE_UNKNOWN,
        )

    private fun downHost(
        host: String = "host1",
        acknowledged: Boolean = false,
        scheduledDowntimeDepth: Int = 0,
        notificationsEnabled: Boolean = true,
        isSoftState: Boolean = false,
        isFlapping: Boolean = false,
    ) = NagiosProblem.HostProblem(
        hostName = host,
        pluginOutput = "",
        status = NagiosStatus.HOST_DOWN,
        acknowledged = acknowledged,
        scheduledDowntimeDepth = scheduledDowntimeDepth,
        notificationsEnabled = notificationsEnabled,
        isSoftState = isSoftState,
        isFlapping = isFlapping,
    )

    // ── Status quick filters ───────────────────────────────────────────────────

    @Test
    fun `quick filter C returns only service critical`() {
        val problems = listOf(critSvc(), warnSvc(), unkSvc(), downHost())
        val result = applyFilters(problems, FilterSettings(
            hideDownHosts        = true,
            hideUnreachableHosts = true,
            hideWarningServices  = true,
            hideUnknownServices  = true,
        ))
        assertEquals(1, result.size)
        assertEquals(NagiosStatus.SERVICE_CRITICAL, result[0].status)
        assertTrue(result[0] is NagiosProblem.ServiceProblem)
    }

    @Test
    fun `quick filter W returns only service warning`() {
        val problems = listOf(critSvc(), warnSvc(), unkSvc(), downHost())
        val result = applyFilters(problems, FilterSettings(
            hideDownHosts        = true,
            hideUnreachableHosts = true,
            hideCriticalServices = true,
            hideUnknownServices  = true,
        ))
        assertEquals(1, result.size)
        assertEquals(NagiosStatus.SERVICE_WARNING, result[0].status)
    }

    @Test
    fun `quick filter D returns only host down`() {
        val problems = listOf(critSvc(), warnSvc(), unkSvc(), downHost())
        val result = applyFilters(problems, FilterSettings(
            hideCriticalServices = true,
            hideUnreachableHosts = true,
            hideWarningServices  = true,
            hideUnknownServices  = true,
        ))
        assertEquals(1, result.size)
        assertTrue(result[0] is NagiosProblem.HostProblem)
        assertEquals(NagiosStatus.HOST_DOWN, result[0].status)
    }

    @Test
    fun `no filters returns full list`() {
        val problems = listOf(critSvc(), warnSvc(), unkSvc(), downHost())
        assertEquals(4, applyFilters(problems, FilterSettings()).size)
    }

    // ── Common state filters ───────────────────────────────────────────────────

    @Test
    fun `hideAcknowledgedHostsAndServices hides acked service`() {
        val problems = listOf(critSvc(acknowledged = true), critSvc(service = "svc2"))
        val result = applyFilters(problems, FilterSettings(hideAcknowledgedHostsAndServices = true))
        assertEquals(1, result.size)
        assertFalse(result[0].acknowledged)
    }

    @Test
    fun `hideHostsAndServicesWithDisabledNotifications hides disabled-notif host`() {
        val problems = listOf(downHost(notificationsEnabled = false), downHost(host = "host2"))
        val result = applyFilters(problems, FilterSettings(hideHostsAndServicesWithDisabledNotifications = true))
        assertEquals(1, result.size)
        assertTrue(result[0].notificationsEnabled)
    }

    @Test
    fun `hideHostsAndServicesDownForDowntime hides host in downtime`() {
        val problems = listOf(downHost(scheduledDowntimeDepth = 1), downHost(host = "host2"))
        val result = applyFilters(problems, FilterSettings(hideHostsAndServicesDownForDowntime = true))
        assertEquals(1, result.size)
        assertEquals(0, result[0].scheduledDowntimeDepth)
    }

    @Test
    fun `hideCriticalServices hides service critical`() {
        assertTrue(applyFilters(listOf(critSvc()), FilterSettings(hideCriticalServices = true)).isEmpty())
    }

    @Test
    fun `hideWarningServices hides service warning`() {
        assertTrue(applyFilters(listOf(warnSvc()), FilterSettings(hideWarningServices = true)).isEmpty())
    }

    @Test
    fun `hideUnknownServices hides service unknown`() {
        assertTrue(applyFilters(listOf(unkSvc()), FilterSettings(hideUnknownServices = true)).isEmpty())
    }

    @Test
    fun `hideDownHosts hides host down`() {
        assertTrue(applyFilters(listOf(downHost()), FilterSettings(hideDownHosts = true)).isEmpty())
    }

    @Test
    fun `hideServicesInSoftState hides soft-state service`() {
        assertTrue(applyFilters(listOf(critSvc(isSoftState = true)), FilterSettings(hideServicesInSoftState = true)).isEmpty())
    }

    @Test
    fun `hideHostsInSoftState hides soft-state host`() {
        assertTrue(applyFilters(listOf(downHost(isSoftState = true)), FilterSettings(hideHostsInSoftState = true)).isEmpty())
    }

    @Test
    fun `hideFlappingServices hides flapping service`() {
        assertTrue(applyFilters(listOf(critSvc(isFlapping = true)), FilterSettings(hideFlappingServices = true)).isEmpty())
    }

    @Test
    fun `hideFlappingHosts hides flapping host`() {
        assertTrue(applyFilters(listOf(downHost(isFlapping = true)), FilterSettings(hideFlappingHosts = true)).isEmpty())
    }

    // ── Service-on-host filters ────────────────────────────────────────────────

    @Test
    fun `hideServicesOnAcknowledgedHosts hides service whose host is acked`() {
        val svcOnAcked = critSvc(host = "host1", hostAcknowledged = true)
        val svcOnNormal = critSvc(host = "host2", service = "svc2")
        val result = applyFilters(listOf(svcOnAcked, svcOnNormal),
            FilterSettings(hideServicesOnAcknowledgedHosts = true))
        assertEquals(1, result.size)
        assertEquals("host2", result[0].hostName)
    }

    @Test
    fun `hideServicesOnDownHosts hides service whose host is down`() {
        val svcOnDown = critSvc(host = "host1", hostStatus = NagiosStatus.HOST_DOWN)
        val svcNormal = critSvc(host = "host2", service = "svc2", hostStatus = NagiosStatus.HOST_UP)
        val result = applyFilters(listOf(svcOnDown, svcNormal),
            FilterSettings(hideServicesOnDownHosts = true))
        assertEquals(1, result.size)
        assertEquals("host2", result[0].hostName)
    }

    @Test
    fun `hideServicesOnHostsInDowntime hides service on host in scheduled downtime`() {
        val svcOnDT = critSvc(host = "host1", hostScheduledDowntimeDepth = 1)
        val svcNormal = critSvc(host = "host2", service = "svc2")
        val result = applyFilters(listOf(svcOnDT, svcNormal),
            FilterSettings(hideServicesOnHostsInDowntime = true))
        assertEquals(1, result.size)
        assertEquals("host2", result[0].hostName)
    }

    @Test
    fun `hideServicesOnUnreachableHosts hides service on unreachable host`() {
        val svcOnUnr = critSvc(host = "host1", hostStatus = NagiosStatus.HOST_UNREACHABLE)
        val svcNormal = critSvc(host = "host2", service = "svc2")
        val result = applyFilters(listOf(svcOnUnr, svcNormal),
            FilterSettings(hideServicesOnUnreachableHosts = true))
        assertEquals(1, result.size)
        assertEquals("host2", result[0].hostName)
    }

    // ── Regex filters ──────────────────────────────────────────────────────────

    @Test
    fun `host regex hides matching host`() {
        val problems = listOf(critSvc(host = "web-01"), critSvc(host = "db-01", service = "svc2"))
        val result = applyFilters(problems, FilterSettings(
            hostRegexEnabled = true,
            hostRegex = "web-",
        ))
        assertEquals(1, result.size)
        assertEquals("db-01", result[0].hostName)
    }

    @Test
    fun `host regex reversed keeps only matching host`() {
        val problems = listOf(critSvc(host = "web-01"), critSvc(host = "db-01", service = "svc2"))
        val result = applyFilters(problems, FilterSettings(
            hostRegexEnabled  = true,
            hostRegex         = "web-",
            hostRegexReverse  = true,
        ))
        assertEquals(1, result.size)
        assertEquals("web-01", result[0].hostName)
    }

    @Test
    fun `service regex hides matching service name`() {
        val problems = listOf(
            NagiosProblem.ServiceProblem("host1", "http", "", NagiosStatus.SERVICE_CRITICAL),
            NagiosProblem.ServiceProblem("host1", "disk", "", NagiosStatus.SERVICE_CRITICAL),
        )
        val result = applyFilters(problems, FilterSettings(
            serviceRegexEnabled = true,
            serviceRegex        = "http",
        ))
        assertEquals(1, result.size)
        assertEquals("disk", (result[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    @Test
    fun `statusInfo regex hides matching plugin output`() {
        val problems = listOf(
            NagiosProblem.ServiceProblem("h", "s1", "Connection refused", NagiosStatus.SERVICE_CRITICAL),
            NagiosProblem.ServiceProblem("h", "s2", "Timeout", NagiosStatus.SERVICE_CRITICAL),
        )
        val result = applyFilters(problems, FilterSettings(
            statusInfoRegexEnabled = true,
            statusInfoRegex        = "refused",
        ))
        assertEquals(1, result.size)
        assertEquals("s2", (result[0] as NagiosProblem.ServiceProblem).serviceName)
    }

    @Test
    fun `invalid regex is silently ignored`() {
        val problems = listOf(critSvc(), warnSvc())
        val result = applyFilters(problems, FilterSettings(
            hostRegexEnabled = true,
            hostRegex        = "[unclosed(",
        ))
        assertEquals(2, result.size)
    }

    @Test
    fun `disabled regex filter has no effect even when pattern is set`() {
        val problems = listOf(critSvc(host = "web-01"), critSvc(host = "db-01", service = "svc2"))
        val result = applyFilters(problems, FilterSettings(
            hostRegexEnabled = false,
            hostRegex        = "web-",
        ))
        assertEquals(2, result.size)
    }

    // ── validateRegex ──────────────────────────────────────────────────────────

    @Test
    fun `validateRegex returns null for a valid pattern`() {
        assertNull(validateRegex("web-\\d+"))
        assertNull(validateRegex("^(foo|bar)$"))
    }

    @Test
    fun `validateRegex returns error message for invalid pattern`() {
        assertNotNull(validateRegex("[unclosed"))
    }

    @Test
    fun `validateRegex returns null for blank pattern`() {
        assertNull(validateRegex(""))
        assertNull(validateRegex("   "))
    }
}
