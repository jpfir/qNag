package com.exogroup.qnag.notifications

import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NagiosStatusSummary
import com.exogroup.qnag.widget.WidgetStatusSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Model ─────────────────────────────────────────────────────────────────────

data class CompactMonitoringSummary(
    val sourceTitle: String,
    val instanceOk: Int,
    val instanceFailed: Int,
    val instanceTotal: Int,
    val hostDown: Int,
    val hostUnreachable: Int,
    val serviceCritical: Int,
    val serviceWarning: Int,
    val serviceUnknown: Int,
    val totalProblems: Int,
    val lastUpdated: Long?,
    val isPartialFailure: Boolean,
    val isFullFailure: Boolean,
    val topProblems: List<CompactProblemSummary> = emptyList(),
    // Full Nagios totals — null until a fetch path provides them (best-effort)
    val hostTotal: Int? = null,
    val hostUp: Int? = null,
    val serviceTotal: Int? = null,
    val serviceOk: Int? = null,
)

data class CompactProblemSummary(
    val status: String,
    val instanceName: String?,
    val hostName: String,
    val serviceName: String?,
    val pluginOutput: String?,
)

// ── Formatters ────────────────────────────────────────────────────────────────

fun CompactMonitoringSummary.toNotificationTitle(hideDetails: Boolean = false): String = when {
    isFullFailure || isPartialFailure -> "qNag FAILURE"
    hideDetails -> "qNag alert"
    totalProblems == 0 -> "qNag: all clear"
    serviceTotal != null -> {
        // aNag-like: title shows monitoring instance health; content carries service totals
        val instText = if (instanceTotal > 1) "$instanceOk ok / $instanceFailed failed" else "OK"
        "qNag service: $instText"
    }
    else -> buildList {
        if (hostDown > 0) add("$hostDown host${if (hostDown > 1) "s" else ""} down")
        if (hostUnreachable > 0) add("$hostUnreachable unreachable")
        if (serviceCritical > 0) add("$serviceCritical critical")
        if (serviceWarning > 0) add("$serviceWarning warning")
        if (serviceUnknown > 0) add("$serviceUnknown unknown")
    }.take(2).let { parts ->
        if (parts.isEmpty()) "qNag: monitoring active"
        else "qNag: ${parts.joinToString(" · ")}"
    }
}

fun CompactMonitoringSummary.toOneLineText(): String = when {
    isFullFailure    -> "All $instanceTotal instance${if (instanceTotal != 1) "s" else ""} failed to update"
    isPartialFailure -> "$instanceOk of $instanceTotal ok  ·  $instanceFailed failed"
    totalProblems == 0 -> if (instanceTotal > 1) "$instanceTotal instances OK" else "All clear"
    serviceTotal != null -> "T:$serviceTotal / O:${serviceOk ?: "?"} / C:$serviceCritical / W:$serviceWarning / U:$serviceUnknown"
    else -> buildList {
        if (instanceTotal > 1) add("$instanceTotal instances")
        if (hostDown == 0 && hostUnreachable == 0) {
            add("hosts OK")
        } else {
            val hostParts = buildList {
                if (hostDown > 0) add("DOWN:$hostDown")
                if (hostUnreachable > 0) add("UNR:$hostUnreachable")
            }.joinToString(" ")
            add(hostParts)
        }
        add("C:$serviceCritical W:$serviceWarning U:$serviceUnknown")
    }.joinToString("  ·  ")
}

fun CompactMonitoringSummary.toTwoLineText(): Pair<String, String> {
    val time = lastUpdated?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    return when {
        isFullFailure -> "0/$instanceTotal instances reachable" to "Open qNag for details"
        isPartialFailure -> {
            val line1 = "$instanceOk ok / $instanceFailed failed  ·  partial"
            val line2 = buildString {
                append("S C:$serviceCritical W:$serviceWarning U:$serviceUnknown")
                if (time != null) append("  ·  $time")
            }
            line1 to line2
        }
        totalProblems == 0 -> {
            val line1 = if (instanceTotal > 0) "All clear  ·  Inst $instanceOk/$instanceTotal" else "All clear"
            val line2 = if (time != null) "Updated $time" else ""
            line1 to line2
        }
        else -> {
            val line1 = buildString {
                if (instanceTotal > 1) append("Inst $instanceOk/$instanceTotal  ·  ")
                append("H D:$hostDown U:$hostUnreachable")
            }
            line1 to "S C:$serviceCritical W:$serviceWarning U:$serviceUnknown"
        }
    }
}

fun CompactMonitoringSummary.toBigText(): String {
    val time = lastUpdated?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    return buildString {
        appendLine(sourceTitle)
        when {
            isFullFailure -> {
                appendLine("All $instanceTotal instance${if (instanceTotal != 1) "s" else ""} failed to update")
                append("Open qNag for details")
            }
            isPartialFailure -> {
                appendLine("Instances: $instanceOk ok / $instanceFailed failed")
                if (hostTotal != null && hostUp != null) {
                    appendLine("Hosts: T:$hostTotal / UP:$hostUp / DOWN:$hostDown / UNR:$hostUnreachable")
                } else if (hostDown == 0 && hostUnreachable == 0) {
                    appendLine("Hosts: OK")
                } else {
                    appendLine("Hosts: DOWN:$hostDown  UNR:$hostUnreachable")
                }
                if (serviceTotal != null && serviceOk != null) {
                    appendLine("Services: T:$serviceTotal / OK:$serviceOk / CRIT:$serviceCritical / WARN:$serviceWarning / UNK:$serviceUnknown")
                } else {
                    appendLine("Services: C:$serviceCritical  W:$serviceWarning  U:$serviceUnknown")
                }
                if (time != null) append("Updated $time  ·  partial failure") else append("partial failure")
            }
            else -> {
                if (instanceTotal > 0) {
                    if (instanceFailed == 0) appendLine("Instances: $instanceOk ok")
                    else appendLine("Instances: $instanceOk ok / $instanceFailed failed")
                }
                if (hostTotal != null && hostUp != null) {
                    appendLine("Hosts: T:$hostTotal / UP:$hostUp / DOWN:$hostDown / UNR:$hostUnreachable")
                } else if (hostDown == 0 && hostUnreachable == 0) {
                    appendLine("Hosts: OK")
                } else {
                    appendLine("Hosts: DOWN:$hostDown  UNR:$hostUnreachable")
                }
                if (serviceTotal != null && serviceOk != null) {
                    appendLine("Services: T:$serviceTotal / OK:$serviceOk / CRIT:$serviceCritical / WARN:$serviceWarning / UNK:$serviceUnknown")
                } else {
                    appendLine("Services: C:$serviceCritical  W:$serviceWarning  U:$serviceUnknown")
                }
                if (time != null) append("Updated $time")
            }
        }
    }.trimEnd()
}

fun CompactProblemSummary.toWatchLine(): String {
    val target = if (serviceName != null) "$hostName / $serviceName" else hostName
    return "$status $target"
}

// ── Builders ──────────────────────────────────────────────────────────────────

fun WidgetStatusSnapshot.toCompactSummary(): CompactMonitoringSummary {
    val isPartialFailure = instanceFailed > 0 && instanceOk > 0 && instanceTotal > 0
    val isFullFailure    = instanceFailed > 0 && instanceOk == 0 && instanceTotal > 0
    return CompactMonitoringSummary(
        sourceTitle      = sourceTitle,
        instanceOk       = instanceOk,
        instanceFailed   = instanceFailed,
        instanceTotal    = instanceTotal,
        hostDown         = down,
        hostUnreachable  = unreachable,
        serviceCritical  = critical,
        serviceWarning   = warning,
        serviceUnknown   = unknown,
        totalProblems    = totalProblems,
        lastUpdated      = if (lastUpdated > 0) lastUpdated else null,
        isPartialFailure = isPartialFailure,
        isFullFailure    = isFullFailure,
        topProblems      = topProblems.map {
            CompactProblemSummary(
                status       = it.status,
                instanceName = it.instanceName.takeIf { n -> n.isNotEmpty() },
                hostName     = it.hostName,
                serviceName  = it.serviceName,
                pluginOutput = it.pluginOutput,
            )
        },
        hostTotal    = this.hostTotal,
        hostUp       = this.hostUp,
        serviceTotal = this.serviceTotal,
        serviceOk    = this.serviceOk,
    )
}

fun buildCompactSummaryFromProblems(
    sourceTitle: String,
    allProblems: List<ProblemToNotify>,
    failedInstances: List<String>,
    instanceTotal: Int,
    lastUpdated: Long?,
    statusSummaries: List<NagiosStatusSummary> = emptyList(),
): CompactMonitoringSummary {
    val hostDown = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
    val hostUnr  = allProblems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
    val svcCrit  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
    val svcWarn  = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
    val svcUnk   = allProblems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_UNKNOWN }
    val instanceOk   = (instanceTotal - failedInstances.size).coerceAtLeast(0)
    val instanceFail = failedInstances.size
    val isPartial = instanceFail > 0 && instanceOk > 0
    val isFull    = instanceFail > 0 && instanceOk == 0 && instanceTotal > 0
    val topProblems = allProblems
        .sortedWith(compareBy(
            { problemSeverityRank(it.problem) },
            { if (it.problem.acknowledged) 1 else 0 },
            { it.problem.hostName },
        ))
        .take(5).map {
        CompactProblemSummary(
            status       = statusLabelForProblem(it.problem),
            instanceName = it.instanceName.takeIf { n -> n.isNotEmpty() },
            hostName     = it.problem.hostName,
            serviceName  = (it.problem as? NagiosProblem.ServiceProblem)?.serviceName,
            pluginOutput = it.problem.pluginOutput.take(80).ifEmpty { null },
        )
    }
    // Aggregate totals across all instances that returned summary data (best-effort)
    val aggHostTotal    = statusSummaries.mapNotNull { it.hostTotal }.reduceOrNull { a, b -> a + b }
    val aggHostUp       = statusSummaries.mapNotNull { it.hostUp }.reduceOrNull { a, b -> a + b }
    val aggServiceTotal = statusSummaries.mapNotNull { it.serviceTotal }.reduceOrNull { a, b -> a + b }
    val aggServiceOk    = statusSummaries.mapNotNull { it.serviceOk }.reduceOrNull { a, b -> a + b }

    return CompactMonitoringSummary(
        sourceTitle      = sourceTitle,
        instanceOk       = instanceOk,
        instanceFailed   = instanceFail,
        instanceTotal    = instanceTotal,
        hostDown         = hostDown,
        hostUnreachable  = hostUnr,
        serviceCritical  = svcCrit,
        serviceWarning   = svcWarn,
        serviceUnknown   = svcUnk,
        totalProblems    = allProblems.size,
        lastUpdated      = lastUpdated,
        isPartialFailure = isPartial,
        isFullFailure    = isFull,
        topProblems      = topProblems,
        hostTotal        = aggHostTotal,
        hostUp           = aggHostUp,
        serviceTotal     = aggServiceTotal,
        serviceOk        = aggServiceOk,
    )
}

private fun problemSeverityRank(p: NagiosProblem): Int = when {
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_DOWN         -> 0
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL  -> 1
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_UNREACHABLE  -> 2
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING   -> 3
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN   -> 4
    else                                                                             -> 5
}

internal fun statusLabelForProblem(p: NagiosProblem): String = when {
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_DOWN          -> "DOWN"
    p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_UNREACHABLE   -> "UNRCH"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL   -> "CRIT"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING    -> "WARN"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN    -> "UNK"
    else                                                                              -> "?"
}
