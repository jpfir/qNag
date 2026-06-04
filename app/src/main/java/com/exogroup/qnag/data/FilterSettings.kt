package com.exogroup.qnag.data

data class FilterSettings(
    // ── Status-based ─────────────────────────────────────────────────────────
    val hideDownHosts: Boolean = false,
    val hideUnreachableHosts: Boolean = false,
    val hideFlappingHosts: Boolean = false,
    val hideCriticalServices: Boolean = false,
    val hideUnknownServices: Boolean = false,
    val hideWarningServices: Boolean = false,
    val hideFlappingServices: Boolean = false,

    // ── State / check-based ───────────────────────────────────────────────────
    val hideAcknowledgedHostsAndServices: Boolean = false,
    val hideHostsAndServicesWithDisabledNotifications: Boolean = false,
    val hideHostsAndServicesWithDisabledChecks: Boolean = false,
    val hideHostsAndServicesDownForDowntime: Boolean = false,
    val hideServicesOnAcknowledgedHosts: Boolean = false,
    val hideServicesOnDownHosts: Boolean = false,
    val hideServicesOnHostsInDowntime: Boolean = false,
    val hideServicesOnUnreachableHosts: Boolean = false,
    val hideHostsInSoftState: Boolean = false,
    val hideServicesInSoftState: Boolean = false,

    // ── Regex filters ─────────────────────────────────────────────────────────
    // Normal (reverse=false): hides problems WHERE the field matches the regex.
    // Reverse (reverse=true): hides problems WHERE the field does NOT match.
    val hostRegexEnabled: Boolean = false,
    val hostRegex: String = "",
    val hostRegexReverse: Boolean = false,

    val serviceRegexEnabled: Boolean = false,
    val serviceRegex: String = "",
    val serviceRegexReverse: Boolean = false,

    val statusInfoRegexEnabled: Boolean = false,
    val statusInfoRegex: String = "",
    val statusInfoRegexReverse: Boolean = false,
)

/** Returns null if the pattern is valid, or an error message if it is not. */
fun validateRegex(pattern: String): String? {
    if (pattern.isBlank()) return null
    return try {
        Regex(pattern)
        null
    } catch (e: Exception) {
        e.message ?: "Invalid regular expression"
    }
}

/**
 * Applies [filters] to [problems] and returns the visible subset.
 *
 * Invalid regexes are silently ignored rather than crashing.
 */
fun applyFilters(problems: List<NagiosProblem>, filters: FilterSettings): List<NagiosProblem> {
    val compiledHostRegex = if (filters.hostRegexEnabled && filters.hostRegex.isNotBlank()) {
        try { Regex(filters.hostRegex) } catch (e: Exception) { null }
    } else null

    val compiledServiceRegex = if (filters.serviceRegexEnabled && filters.serviceRegex.isNotBlank()) {
        try { Regex(filters.serviceRegex) } catch (e: Exception) { null }
    } else null

    val compiledStatusInfoRegex = if (filters.statusInfoRegexEnabled && filters.statusInfoRegex.isNotBlank()) {
        try { Regex(filters.statusInfoRegex) } catch (e: Exception) { null }
    } else null

    return problems.filter { problem ->
        // ── Type-specific filters ────────────────────────────────────────────
        when (problem) {
            is NagiosProblem.HostProblem -> {
                if (filters.hideDownHosts && problem.status == NagiosStatus.HOST_DOWN) return@filter false
                if (filters.hideUnreachableHosts && problem.status == NagiosStatus.HOST_UNREACHABLE) return@filter false
                if (filters.hideFlappingHosts && problem.isFlapping) return@filter false
                if (filters.hideHostsInSoftState && problem.isSoftState) return@filter false
            }
            is NagiosProblem.ServiceProblem -> {
                if (filters.hideCriticalServices && problem.status == NagiosStatus.SERVICE_CRITICAL) return@filter false
                if (filters.hideWarningServices && problem.status == NagiosStatus.SERVICE_WARNING) return@filter false
                if (filters.hideUnknownServices && problem.status == NagiosStatus.SERVICE_UNKNOWN) return@filter false
                if (filters.hideFlappingServices && problem.isFlapping) return@filter false
                if (filters.hideServicesInSoftState && problem.isSoftState) return@filter false
                if (filters.hideServicesOnAcknowledgedHosts && problem.hostAcknowledged) return@filter false
                if (filters.hideServicesOnDownHosts && problem.hostStatus == NagiosStatus.HOST_DOWN) return@filter false
                if (filters.hideServicesOnHostsInDowntime && problem.hostScheduledDowntimeDepth > 0) return@filter false
                if (filters.hideServicesOnUnreachableHosts && problem.hostStatus == NagiosStatus.HOST_UNREACHABLE) return@filter false
            }
        }

        // ── Common filters ───────────────────────────────────────────────────
        if (filters.hideAcknowledgedHostsAndServices && problem.acknowledged) return@filter false
        if (filters.hideHostsAndServicesWithDisabledNotifications && !problem.notificationsEnabled) return@filter false
        if (filters.hideHostsAndServicesWithDisabledChecks && !problem.checksEnabled) return@filter false
        if (filters.hideHostsAndServicesDownForDowntime && problem.scheduledDowntimeDepth > 0) return@filter false

        // ── Regex filters ────────────────────────────────────────────────────
        if (compiledHostRegex != null) {
            val matches = compiledHostRegex.containsMatchIn(problem.hostName)
            val hide = if (filters.hostRegexReverse) !matches else matches
            if (hide) return@filter false
        }

        if (compiledServiceRegex != null && problem is NagiosProblem.ServiceProblem) {
            val matches = compiledServiceRegex.containsMatchIn(problem.serviceName)
            val hide = if (filters.serviceRegexReverse) !matches else matches
            if (hide) return@filter false
        }

        if (compiledStatusInfoRegex != null) {
            val matches = compiledStatusInfoRegex.containsMatchIn(problem.pluginOutput)
            val hide = if (filters.statusInfoRegexReverse) !matches else matches
            if (hide) return@filter false
        }

        true
    }
}
