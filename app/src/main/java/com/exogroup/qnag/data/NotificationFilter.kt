package com.exogroup.qnag.data

// Separator that cannot appear in UUIDs or Nagios host/service names
private const val SEP = ""

/** Returns true if [problem] should trigger a notification given [settings]. */
fun shouldNotify(problem: NagiosProblem, settings: NotificationSettings): Boolean {
    val statusAllowed = when (problem) {
        is NagiosProblem.ServiceProblem -> when (problem.status) {
            NagiosStatus.SERVICE_CRITICAL -> settings.notifyOnCriticalServices
            NagiosStatus.SERVICE_WARNING -> settings.notifyOnWarningServices
            NagiosStatus.SERVICE_UNKNOWN -> settings.notifyOnUnknownServices
            else -> false
        }
        is NagiosProblem.HostProblem -> when (problem.status) {
            NagiosStatus.HOST_DOWN -> settings.notifyOnDownHosts
            NagiosStatus.HOST_UNREACHABLE -> settings.notifyOnUnreachableHosts
            else -> false
        }
    }
    if (!statusAllowed) return false

    if (settings.notifyOnlyUnacknowledged && problem.acknowledged) return false
    if (settings.notifyOnlyHardState && problem.isSoftState) return false
    if (settings.respectDowntime && problem.scheduledDowntimeDepth > 0) return false
    if (settings.respectDowntime &&
        problem is NagiosProblem.ServiceProblem &&
        problem.hostScheduledDowntimeDepth > 0
    ) return false

    return true
}

/** Short status string used in notification titles. */
fun notificationStatusLabel(problem: NagiosProblem): String = when (problem) {
    is NagiosProblem.ServiceProblem -> when (problem.status) {
        NagiosStatus.SERVICE_CRITICAL -> "CRITICAL"
        NagiosStatus.SERVICE_WARNING -> "WARNING"
        NagiosStatus.SERVICE_UNKNOWN -> "UNKNOWN"
        else -> "STATUS ${problem.status}"
    }
    is NagiosProblem.HostProblem -> when (problem.status) {
        NagiosStatus.HOST_DOWN -> "DOWN"
        NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
        else -> "STATUS ${problem.status}"
    }
}

/**
 * Stable fingerprint encoding instance + problem identity + current status.
 * Uses U+001F (unit separator) between components so a UUID prefix never accidentally
 * matches a longer UUID or a problem whose uniqueId starts with that UUID.
 */
fun problemFingerprint(instanceId: String, problem: NagiosProblem): String =
    "$instanceId$SEP${problem.uniqueId}$SEP${problem.status}"

/**
 * Prefix used to filter all fingerprints belonging to a single instance.
 * Must be used with startsWith() to avoid false matches.
 */
fun instanceFingerprintPrefix(instanceId: String): String = "$instanceId$SEP"

/**
 * Stable notification ID derived from instance + problem identity (no status, so the
 * same problem always maps to the same Android notification slot regardless of status change).
 */
fun notificationId(instanceId: String, problem: NagiosProblem): Int =
    ("$instanceId$SEP${problem.uniqueId}").hashCode().let { if (it < 0) it.inv() else it }

/** Notification ID for a per-instance fetch-failure alert. */
fun fetchFailureNotificationId(instanceId: String): Int =
    ("fetch_fail$SEP$instanceId").hashCode().let { if (it < 0) it.inv() else it }
