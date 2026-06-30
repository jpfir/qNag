package com.exogroup.qnag.widget

import android.content.Context
import androidx.core.content.edit
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NagiosStatusSummary
import com.google.gson.Gson

enum class WidgetRefreshState { IDLE, REFRESHING, FAILED }

data class WidgetStatusSnapshot(
    val sourceTitle: String,
    val lastUpdated: Long,
    val totalProblems: Int,

    // Instance health counts (0 = this poll path does not track them)
    val instanceTotal: Int = 0,
    val instanceOk: Int = 0,
    val instanceFailed: Int = 0,

    // Open problem counts — always present, built from filtered list
    val down: Int,
    val unreachable: Int,
    val critical: Int,
    val warning: Int,
    val unknown: Int,

    // Full totals from Nagios — null until a fetch path provides them
    val hostTotal: Int? = null,
    val hostUp: Int? = null,
    val serviceTotal: Int? = null,
    val serviceOk: Int? = null,

    val topProblems: List<WidgetProblemSummary>,
    val instanceSummaries: List<WidgetInstanceSummary> = emptyList(),

    // null deserialises from old snapshots — treat as IDLE
    val refreshState: WidgetRefreshState? = null,
    val lastRefreshError: String? = null,
    val noEnabledInstances: Boolean = false,
)

data class WidgetProblemSummary(
    val instanceName: String,
    val hostName: String,
    val serviceName: String?,
    val status: String,
    val pluginOutput: String?,
)

data class WidgetInstanceSummary(
    val instanceName: String,
    val totalProblems: Int,
    val down: Int,
    val unreachable: Int = 0,
    val critical: Int,
    val warning: Int,
    val unknown: Int,
    val failed: Boolean = false,
)

object WidgetSnapshotStore {

    internal const val PREFS_NAME = "qnag_widget_snapshot"
    internal const val KEY_SNAPSHOT = "snapshot"

    const val STALE_THRESHOLD_MS = 30 * 60 * 1_000L

    // ── Save / load ───────────────────────────────────────────────────────────

    fun save(
        context: Context,
        problems: List<NagiosProblem>,
        lastUpdated: Long,
        sourceTitle: String,
        instanceSummaries: List<WidgetInstanceSummary> = emptyList(),
        instanceFailed: Int = 0,
        refreshState: WidgetRefreshState = WidgetRefreshState.IDLE,
        lastRefreshError: String? = null,
        noEnabledInstances: Boolean = false,
        statusSummaries: List<NagiosStatusSummary> = emptyList(),
    ) {
        val snapshot = buildSnapshot(
            problems, lastUpdated, sourceTitle,
            instanceSummaries, instanceFailed,
            refreshState, lastRefreshError, noEnabledInstances,
            statusSummaries,
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_SNAPSHOT, Gson().toJson(snapshot)) }
    }

    fun load(context: Context): WidgetStatusSnapshot? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return null
        return try { Gson().fromJson(json, WidgetStatusSnapshot::class.java) }
        catch (_: Exception) { null }
    }

    suspend fun saveAndRefreshWidgets(
        context: Context,
        problems: List<NagiosProblem>,
        lastUpdated: Long,
        sourceTitle: String,
        instanceSummaries: List<WidgetInstanceSummary> = emptyList(),
        instanceFailed: Int = 0,
        refreshState: WidgetRefreshState = WidgetRefreshState.IDLE,
        lastRefreshError: String? = null,
        noEnabledInstances: Boolean = false,
        statusSummaries: List<NagiosStatusSummary> = emptyList(),
    ) {
        save(context, problems, lastUpdated, sourceTitle, instanceSummaries,
            instanceFailed, refreshState, lastRefreshError, noEnabledInstances, statusSummaries)
        WidgetUpdater.updateAll(context)
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    fun setRefreshState(context: Context, state: WidgetRefreshState, error: String? = null) {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = load(context)
        val updated = current?.copy(refreshState = state, lastRefreshError = error)
            ?: WidgetStatusSnapshot(
                sourceTitle = "", lastUpdated = 0L, totalProblems = 0,
                down = 0, unreachable = 0, critical = 0, warning = 0, unknown = 0,
                topProblems = emptyList(), refreshState = state, lastRefreshError = error,
            )
        prefs.edit { putString(KEY_SNAPSHOT, Gson().toJson(updated)) }
    }

    // ── Instance summary builder ──────────────────────────────────────────────

    fun buildInstanceSummary(
        instanceName: String,
        filteredProblems: List<NagiosProblem>,
    ): WidgetInstanceSummary = WidgetInstanceSummary(
        instanceName  = instanceName,
        totalProblems = filteredProblems.size,
        down          = filteredProblems.count { it is NagiosProblem.HostProblem    && it.status == NagiosStatus.HOST_DOWN },
        unreachable   = filteredProblems.count { it is NagiosProblem.HostProblem    && it.status == NagiosStatus.HOST_UNREACHABLE },
        critical      = filteredProblems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_CRITICAL },
        warning       = filteredProblems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_WARNING },
        unknown       = filteredProblems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_UNKNOWN },
    )

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildSnapshot(
        problems: List<NagiosProblem>,
        lastUpdated: Long,
        sourceTitle: String,
        instanceSummaries: List<WidgetInstanceSummary>,
        instanceFailed: Int,
        refreshState: WidgetRefreshState,
        lastRefreshError: String?,
        noEnabledInstances: Boolean,
        statusSummaries: List<NagiosStatusSummary> = emptyList(),
    ): WidgetStatusSnapshot {
        val down        = problems.count { it is NagiosProblem.HostProblem    && it.status == NagiosStatus.HOST_DOWN }
        val unreachable = problems.count { it is NagiosProblem.HostProblem    && it.status == NagiosStatus.HOST_UNREACHABLE }
        val critical    = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_CRITICAL }
        val warning     = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_WARNING }
        val unknown     = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_UNKNOWN }

        val instanceOk    = instanceSummaries.count { !it.failed }
        val instanceTotal = instanceOk + instanceFailed

        val topProblems = problems.sortedWith(SEVERITY_ORDER).take(5).map { p ->
            WidgetProblemSummary(
                instanceName = p.instanceName,
                hostName     = p.hostName,
                serviceName  = (p as? NagiosProblem.ServiceProblem)?.serviceName,
                status       = statusLabel(p),
                pluginOutput = p.pluginOutput.take(80).ifEmpty { null },
            )
        }

        val aggHostTotal    = statusSummaries.mapNotNull { it.hostTotal }.reduceOrNull { a, b -> a + b }
        val aggHostUp       = statusSummaries.mapNotNull { it.hostUp }.reduceOrNull { a, b -> a + b }
        val aggServiceTotal = statusSummaries.mapNotNull { it.serviceTotal }.reduceOrNull { a, b -> a + b }
        val aggServiceOk    = statusSummaries.mapNotNull { it.serviceOk }.reduceOrNull { a, b -> a + b }

        return WidgetStatusSnapshot(
            sourceTitle        = sourceTitle,
            lastUpdated        = lastUpdated,
            totalProblems      = problems.size,
            instanceTotal      = instanceTotal,
            instanceOk         = instanceOk,
            instanceFailed     = instanceFailed,
            down               = down,
            unreachable        = unreachable,
            critical           = critical,
            warning            = warning,
            unknown            = unknown,
            hostTotal          = aggHostTotal,
            hostUp             = aggHostUp,
            serviceTotal       = aggServiceTotal,
            serviceOk          = aggServiceOk,
            topProblems        = topProblems,
            instanceSummaries  = instanceSummaries,
            refreshState       = refreshState,
            lastRefreshError   = lastRefreshError,
            noEnabledInstances = noEnabledInstances,
        )
    }

    private val SEVERITY_ORDER = compareBy<NagiosProblem>(
        { severityRank(it) },
        { if (it.acknowledged) 1 else 0 },
        { if (it.scheduledDowntimeDepth > 0) 1 else 0 },
        { it.hostName },
        { (it as? NagiosProblem.ServiceProblem)?.serviceName ?: "" },
    )

    private fun severityRank(p: NagiosProblem): Int = when {
        p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_DOWN          -> 0
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL   -> 1
        p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_UNREACHABLE   -> 2
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING    -> 3
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN    -> 4
        else                                                                              -> 5
    }

    private fun statusLabel(p: NagiosProblem): String = when {
        p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_DOWN          -> "DOWN"
        p is NagiosProblem.HostProblem    && p.status == NagiosStatus.HOST_UNREACHABLE   -> "UNRCH"
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL   -> "CRIT"
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING    -> "WARN"
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN    -> "UNK"
        else                                                                              -> "?"
    }
}
