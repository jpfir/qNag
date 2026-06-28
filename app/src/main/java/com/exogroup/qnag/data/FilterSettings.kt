package com.exogroup.qnag.data

/** Which field of a problem the regex pattern is matched against. */
enum class RegexFilterField(val displayName: String) {
    ANY("Any field"),
    HOST("Host"),
    SERVICE("Service"),
    STATUS_INFO("Status info"),
}

/**
 * A single regex rule in the multi-rule filter list.
 *
 * [reverse] = false → INCLUDE (show only problems matching [pattern]; qNagstamon semantics)
 * [reverse] = true  → EXCLUDE (hide problems matching [pattern])
 * [field]   → which field to match; defaults to ANY (combined text, same as before).
 */
data class RegexFilterRule(
    val id: String,
    val pattern: String,
    val reverse: Boolean = false,
    val enabled: Boolean = true,
    val field: RegexFilterField = RegexFilterField.ANY,
)

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

    // ── Regex filter rules ────────────────────────────────────────────────────
    // reverse=false → INCLUDE (show only matching; qNagstamon semantics)
    // reverse=true  → EXCLUDE (hide matching)
    // Include rules are OR'd; exclude rules are OR'd; include is applied before exclude.
    val regexRules: List<RegexFilterRule> = emptyList(),
)

/** Returns null if [pattern] is a valid regex, or an error message if not. */
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
 * Normalized search string combining instance, host, service, and plugin output.
 * Used by [applyFilters] and [classifyHiddenReasons] when [field] is [RegexFilterField.ANY].
 */
fun NagiosProblem.regexSearchText(): String = buildString {
    if (instanceName.isNotEmpty()) { append(instanceName); append(' ') }
    append(hostName)
    if (this@regexSearchText is NagiosProblem.ServiceProblem) {
        append(' ')
        append(serviceName)
    }
    append(' ')
    append(pluginOutput)
}

/**
 * Returns the text to match a regex rule against, based on the rule's [field].
 * SERVICE rules return "" for host problems so they never accidentally match.
 */
fun NagiosProblem.regexSearchTextFor(field: RegexFilterField): String = when (field) {
    RegexFilterField.ANY -> regexSearchText()
    RegexFilterField.HOST -> hostName
    RegexFilterField.SERVICE -> if (this is NagiosProblem.ServiceProblem) serviceName else ""
    RegexFilterField.STATUS_INFO -> pluginOutput
}

/**
 * Applies [filters] to [problems] and returns the visible subset.
 *
 * Regex rule semantics (qNagstamon-compatible):
 *   - Include rules (reverse=false): at least one must match, or the problem is hidden.
 *   - Exclude rules (reverse=true):  if any matches, the problem is hidden.
 *   - Include is evaluated before exclude.
 *   - Invalid regexes are silently skipped (never crash).
 */
fun applyFilters(problems: List<NagiosProblem>, filters: FilterSettings): List<NagiosProblem> {
    // Pre-compile all enabled, non-blank rules once; Triple(regex, isExclude, field)
    val compiled: List<Triple<Regex, Boolean, RegexFilterField>> = filters.regexRules
        .filter { it.enabled && it.pattern.isNotBlank() }
        .mapNotNull { rule ->
            try { Triple(Regex(rule.pattern), rule.reverse, rule.field) } catch (_: Exception) { null }
        }
    val includeRules = compiled.filter { !it.second }
    val excludeRules = compiled.filter { it.second }

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

        // ── Regex rules ──────────────────────────────────────────────────────
        if (compiled.isNotEmpty()) {
            // Include: at least one include rule must match its field-specific text
            if (includeRules.isNotEmpty() && includeRules.none { (regex, _, field) ->
                    regex.containsMatchIn(problem.regexSearchTextFor(field))
                }) return@filter false
            // Exclude: must not match any exclude rule's field-specific text
            if (excludeRules.any { (regex, _, field) ->
                    regex.containsMatchIn(problem.regexSearchTextFor(field))
                }) return@filter false
        }

        true
    }
}

// ── Why a problem is hidden by FilterSettings ─────────────────────────────────

enum class HiddenReason(val label: String) {
    ACKNOWLEDGED("ACKED"),
    LOCAL_ACK("LOCAL ACK"),
    DOWNTIME("DOWNTIME"),
    SOFT_STATE("SOFT"),
    NOTIF_DISABLED("NOTIF OFF"),
    CHECKS_DISABLED("CHECKS OFF"),
    FLAPPING("FLAPPING"),
    STATUS_FILTER("STATUS"),
    HOST_DEPENDENCY("HOST"),
    REGEX_FILTER("REGEX"),
}

/**
 * Returns all [HiddenReason]s that cause [problem] to be excluded by [filters].
 * Mirrors the logic in [applyFilters] and [applyFiltersAndLocalAck] exactly.
 */
fun classifyHiddenReasons(
    problem: NagiosProblem,
    filters: FilterSettings,
    isLocallyAcked: Boolean,
): List<HiddenReason> {
    val reasons = mutableListOf<HiddenReason>()

    when (problem) {
        is NagiosProblem.HostProblem -> {
            if (filters.hideDownHosts && problem.status == NagiosStatus.HOST_DOWN) reasons += HiddenReason.STATUS_FILTER
            if (filters.hideUnreachableHosts && problem.status == NagiosStatus.HOST_UNREACHABLE) reasons += HiddenReason.STATUS_FILTER
            if (filters.hideFlappingHosts && problem.isFlapping) reasons += HiddenReason.FLAPPING
            if (filters.hideHostsInSoftState && problem.isSoftState) reasons += HiddenReason.SOFT_STATE
        }
        is NagiosProblem.ServiceProblem -> {
            if (filters.hideCriticalServices && problem.status == NagiosStatus.SERVICE_CRITICAL) reasons += HiddenReason.STATUS_FILTER
            if (filters.hideWarningServices && problem.status == NagiosStatus.SERVICE_WARNING) reasons += HiddenReason.STATUS_FILTER
            if (filters.hideUnknownServices && problem.status == NagiosStatus.SERVICE_UNKNOWN) reasons += HiddenReason.STATUS_FILTER
            if (filters.hideFlappingServices && problem.isFlapping) reasons += HiddenReason.FLAPPING
            if (filters.hideServicesInSoftState && problem.isSoftState) reasons += HiddenReason.SOFT_STATE
            if (filters.hideServicesOnAcknowledgedHosts && problem.hostAcknowledged) reasons += HiddenReason.HOST_DEPENDENCY
            if (filters.hideServicesOnDownHosts && problem.hostStatus == NagiosStatus.HOST_DOWN) reasons += HiddenReason.HOST_DEPENDENCY
            if (filters.hideServicesOnHostsInDowntime && problem.hostScheduledDowntimeDepth > 0) reasons += HiddenReason.HOST_DEPENDENCY
            if (filters.hideServicesOnUnreachableHosts && problem.hostStatus == NagiosStatus.HOST_UNREACHABLE) reasons += HiddenReason.HOST_DEPENDENCY
        }
    }

    if (filters.hideAcknowledgedHostsAndServices) {
        if (problem.acknowledged) reasons += HiddenReason.ACKNOWLEDGED
        else if (isLocallyAcked) reasons += HiddenReason.LOCAL_ACK
    }
    if (filters.hideHostsAndServicesWithDisabledNotifications && !problem.notificationsEnabled) reasons += HiddenReason.NOTIF_DISABLED
    if (filters.hideHostsAndServicesWithDisabledChecks && !problem.checksEnabled) reasons += HiddenReason.CHECKS_DISABLED
    if (filters.hideHostsAndServicesDownForDowntime && problem.scheduledDowntimeDepth > 0) reasons += HiddenReason.DOWNTIME

    // Regex rules: hidden if no include rule matched, or any exclude rule matched
    if (filters.regexRules.isNotEmpty()) {
        val enabledInclude = filters.regexRules.filter { it.enabled && it.pattern.isNotBlank() && !it.reverse }
        val enabledExclude = filters.regexRules.filter { it.enabled && it.pattern.isNotBlank() && it.reverse }

        val hiddenByInclude = enabledInclude.isNotEmpty() && enabledInclude.none { rule ->
            try { Regex(rule.pattern).containsMatchIn(problem.regexSearchTextFor(rule.field)) } catch (_: Exception) { false }
        }
        val hiddenByExclude = enabledExclude.any { rule ->
            try { Regex(rule.pattern).containsMatchIn(problem.regexSearchTextFor(rule.field)) } catch (_: Exception) { false }
        }
        if (hiddenByInclude || hiddenByExclude) reasons += HiddenReason.REGEX_FILTER
    }

    return reasons.distinct()
}
