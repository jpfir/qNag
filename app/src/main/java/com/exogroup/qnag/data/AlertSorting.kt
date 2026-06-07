// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

/**
 * Severity rank for dashboard and notification sort order.
 * Lower number = higher severity.
 *
 *  0  HOST_DOWN
 *  1  SERVICE_CRITICAL
 *  2  HOST_UNREACHABLE
 *  3  SERVICE_UNKNOWN
 *  4  SERVICE_WARNING
 *  5  (anything else)
 *
 * Extracted from NagiosApi so it can be unit-tested without a network layer.
 */
internal fun severityRank(p: NagiosProblem): Int = when {
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_DOWN        -> 0
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL -> 1
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_UNREACHABLE -> 2
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN  -> 3
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING  -> 4
    else -> 5
}

internal fun serviceNameOf(p: NagiosProblem): String =
    if (p is NagiosProblem.ServiceProblem) p.serviceName else ""

/**
 * Dashboard sort comparator — matches the sort used in [NagiosApi.fetchProblems]
 * and [NagiosViewModel].
 *
 * Priority (highest first):
 *  1. Severity rank (HOST_DOWN first, SERVICE_WARNING last)
 *  2. NEW problems (state changed < 15 min ago) before old
 *  3. Unacked / not-in-downtime before acked / in-downtime
 *  4. Notifications-enabled before disabled
 *  5. Newer state-change timestamp first
 *  6. Host name alphabetic
 *  7. Service name alphabetic
 */
internal fun problemComparator(now: Long): Comparator<NagiosProblem> = compareBy(
    { severityRank(it) },
    { if ((it.lastStateChange ?: 0L) > now - 15 * 60 * 1_000L) 0 else 1 },
    { if (it.acknowledged || it.scheduledDowntimeDepth > 0) 1 else 0 },
    { if (it.notificationsEnabled) 0 else 1 },
    { -(it.lastStateChange ?: 0L) },
    { it.hostName },
    { serviceNameOf(it) },
)
