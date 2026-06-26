package com.exogroup.qnag.notifications

import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
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
)

data class CompactProblemSummary(
    val status: String,
    val instanceName: String?,
    val hostName: String,
    val serviceName: String?,
    val pluginOutput: String?,
)

// ── Formatters ────────────────────────────────────────────────────────────────

fun CompactMonitoringSummary.toOneLineText(): String = when {
    isFullFailure    -> "0/$instanceTotal instances reachable"
    isPartialFailure -> "$instanceOk ok / $instanceFailed failed  ·  S C:$serviceCritical W:$serviceWarning U:$serviceUnknown"
    totalProblems == 0 -> if (instanceTotal > 0) "All clear  ·  Inst $instanceOk/$instanceTotal" else "All clear"
    else -> buildString {
        if (instanceTotal > 1) append("Inst $instanceOk/$instanceTotal  ·  ")
        append("H D:$hostDown U:$hostUnreachable  ·  S C:$serviceCritical W:$serviceWarning U:$serviceUnknown")
    }
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
                appendLine("0/$instanceTotal instances reachable")
                append("Open qNag for details")
            }
            isPartialFailure -> {
                appendLine("Inst: $instanceOk ok / $instanceFailed failed")
                appendLine("Hosts: DOWN $hostDown / UNR $hostUnreachable")
                appendLine("Svc: CRIT $serviceCritical / WARN $serviceWarning / UNK $serviceUnknown")
                append(if (time != null) "Updated $time  ·  partial" else "partial")
            }
            else -> {
                if (instanceTotal > 0) appendLine("Inst: $instanceOk ok / $instanceFailed failed")
                appendLine("Hosts: DOWN $hostDown / UNR $hostUnreachable")
                appendLine("Svc: CRIT $serviceCritical / WARN $serviceWarning / UNK $serviceUnknown")
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
    )
}

fun buildCompactSummaryFromProblems(
    sourceTitle: String,
    allProblems: List<ProblemToNotify>,
    failedInstances: List<String>,
    instanceTotal: Int,
    lastUpdated: Long?,
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
