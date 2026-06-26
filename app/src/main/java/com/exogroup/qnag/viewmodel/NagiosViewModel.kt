package com.exogroup.qnag.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exogroup.qnag.data.AckComment
import com.exogroup.qnag.data.AckSuppressCache
import com.exogroup.qnag.data.CommandActivityTracker
import com.exogroup.qnag.data.CommandJobType
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.CommandTargetResult
import com.exogroup.qnag.data.CommandTargetStatus
import com.exogroup.qnag.data.EventLog
import com.exogroup.qnag.data.DowntimeScope
import com.exogroup.qnag.data.InstanceSummary
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.problemComparator
import com.exogroup.qnag.widget.WidgetInstanceSummary
import com.exogroup.qnag.widget.WidgetSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ── Dashboard data state ──────────────────────────────────────────────────────

sealed class DashboardState {
    object Idle : DashboardState()
    data class Loading(
        val previousProblems: List<NagiosProblem>? = null,
        val previousSummaries: List<InstanceSummary> = emptyList(),
        val previousLastUpdated: Long? = null,
    ) : DashboardState()
    /**
     * @param partialErrors Non-empty when some instances failed in ALL mode; shown as a warning
     *   banner alongside the partial problem list.
     * @param instanceSummaries One entry per fetched instance (success or error).  Empty during
     *   initial load before the first successful fetch.
     */
    data class Success(
        val problems: List<NagiosProblem>,
        val lastUpdated: Long,
        val partialErrors: List<String> = emptyList(),
        val instanceSummaries: List<InstanceSummary> = emptyList(),
    ) : DashboardState()
    data class Error(
        val message: String,
        val previousProblems: List<NagiosProblem>? = null,
        val previousSummaries: List<InstanceSummary> = emptyList(),
        val previousLastUpdated: Long? = null,
    ) : DashboardState()
}

// ── ACK comment state (detail screen) ────────────────────────────────────────

sealed class AckCommentState {
    object Idle : AckCommentState()
    object Loading : AckCommentState()
    /** [comment] is null when the fetch succeeded but no acknowledgement comment was found. */
    data class Loaded(val comment: AckComment?) : AckCommentState()
    data class Error(val message: String) : AckCommentState()
}

// ── Command (ACK / recheck) state ─────────────────────────────────────────────

sealed class CommandState {
    object Idle : CommandState()
    object Loading : CommandState()
    data class Success(val message: String) : CommandState()
    data class Warning(val message: String) : CommandState()
    data class Error(val message: String) : CommandState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class NagiosViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication<Application>().applicationContext

    var uiState by mutableStateOf<DashboardState>(DashboardState.Idle)
        private set

    var commandState by mutableStateOf<CommandState>(CommandState.Idle)
        private set

    private val api = NagiosApi()

    // ── Fetch target tracking ─────────────────────────────────────────────────

    private sealed class FetchTarget {
        data class Single(val instance: NagiosInstance) : FetchTarget()
        data class All(val instances: List<NagiosInstance>) : FetchTarget()
    }

    private var lastFetchTarget: FetchTarget? = null

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private var fetchJob: Job? = null

    fun fetchAlerts(instance: NagiosInstance, skipIfRunning: Boolean = false) {
        if (skipIfRunning && fetchJob?.isActive == true) return

        fetchJob?.cancel()
        lastFetchTarget = FetchTarget.Single(instance)
        val stale = currentProblems()
        val staleSummaries = currentSummaries()
        val staleLastUpdated = currentLastUpdated()
        uiState = DashboardState.Loading(stale, staleSummaries, staleLastUpdated)
        fetchJob = viewModelScope.launch {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }
                val now = System.currentTimeMillis()
                reconcileLocalAck(instance.id, problems)
                reconcilePendingRechecks(instance.id, problems)
                val summary = buildInstanceSummary(instance, problems, now)
                val finalProblems = if (stale != null && problemsMatchStable(problems, stale)) stale else problems
                uiState = DashboardState.Success(finalProblems, now, instanceSummaries = listOf(summary))
                viewModelScope.launch {
                    runCatching {
                        val filterSettings = SecureInstanceStore(appContext).getAppSettings().filterSettings
                        val filtered = applyFilters(problems, filterSettings)
                        val widgetSummary = WidgetSnapshotStore.buildInstanceSummary(instance.name, filtered)
                        WidgetSnapshotStore.saveAndRefreshWidgets(
                            appContext, filtered, now, instance.name, listOf(widgetSummary)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errSummary = buildErrorSummary(
                    instance, sanitizeError(e.message),
                    staleSummaries.find { it.instanceId == instance.id },
                )
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale, listOf(errSummary), staleLastUpdated)
            }
        }
    }

    /**
     * Fetch from all instances concurrently.  Individual failures surface as [DashboardState.Success]
     * with a non-empty [DashboardState.Success.partialErrors] list so the dashboard can show a
     * warning banner alongside the partial result set.  Only when ALL instances fail does this
     * produce [DashboardState.Error].
     */
    fun fetchAlertsForAll(instances: List<NagiosInstance>, skipIfRunning: Boolean = false) {
        if (instances.isEmpty()) return
        if (skipIfRunning && fetchJob?.isActive == true) return

        fetchJob?.cancel()
        lastFetchTarget = FetchTarget.All(instances)
        val stale = currentProblems()
        val staleSummaries = currentSummaries()
        val staleLastUpdated = currentLastUpdated()
        uiState = DashboardState.Loading(stale, staleSummaries, staleLastUpdated)

        fetchJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val deferreds = instances.map { inst ->
                    async(Dispatchers.IO) {
                        try {
                            val problems = api.fetchProblems(inst)
                            Triple(problems, null as String?, buildInstanceSummary(inst, problems, now))
                        } catch (e: Exception) {
                            val errMsg = "${inst.name}: ${sanitizeError(e.message)}"
                            val staleSum = staleSummaries.find { it.instanceId == inst.id }
                            Triple(emptyList<NagiosProblem>(), errMsg, buildErrorSummary(inst, errMsg, staleSum))
                        }
                    }
                }.awaitAll()

                val allProblems = deferreds.flatMap { it.first }
                    .sortedWith(problemComparator(now))
                val errors = deferreds.mapNotNull { it.second }
                val summaries = deferreds.map { it.third }

                reconcileLocalAckAll(allProblems)

                reconcilePendingRechecksAll(allProblems)

                if (allProblems.isEmpty() && errors.size == instances.size) {
                    uiState = DashboardState.Error(
                        "Failed to refresh all instances. ${errors.firstOrNull() ?: ""}",
                        stale,
                        summaries,
                        staleLastUpdated,
                    )
                } else {
                    val finalProblems = if (stale != null && problemsMatchStable(allProblems, stale)) stale else allProblems
                    uiState = DashboardState.Success(finalProblems, now, errors, summaries)
                    viewModelScope.launch {
                        runCatching {
                            val filterSettings = SecureInstanceStore(appContext).getAppSettings().filterSettings
                            val allFiltered = mutableListOf<NagiosProblem>()
                            val widgetSummaries = mutableListOf<WidgetInstanceSummary>()
                            var widgetFailCount = 0
                            // Build one summary per instance so OK instances (zero filtered problems)
                            // appear as "OK" in the widget rather than being dropped by groupBy.
                            instances.zip(deferreds).forEach { (inst, triple) ->
                                if (triple.second == null) {
                                    val filteredForInst = applyFilters(triple.first, filterSettings)
                                    allFiltered += filteredForInst
                                    widgetSummaries += WidgetSnapshotStore.buildInstanceSummary(inst.name, filteredForInst)
                                } else {
                                    widgetFailCount++
                                    widgetSummaries += WidgetInstanceSummary(
                                        instanceName  = inst.name,
                                        totalProblems = 0, down = 0, unreachable = 0,
                                        critical = 0, warning = 0, unknown = 0,
                                        failed = true,
                                    )
                                }
                            }
                            val sourceTitle = if (instances.size == 1) instances[0].name else "All instances"
                            WidgetSnapshotStore.saveAndRefreshWidgets(
                                appContext, allFiltered, now, sourceTitle, widgetSummaries, instanceFailed = widgetFailCount
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale, staleSummaries, staleLastUpdated)
            }
        }
    }

    /** Refresh using previous target with skipIfRunning=true — used for delayed post-recheck refreshes. */
    private fun refreshAfterCommandDelayed() {
        when (val target = lastFetchTarget) {
            is FetchTarget.Single -> fetchAlerts(target.instance, skipIfRunning = true)
            is FetchTarget.All -> fetchAlertsForAll(target.instances, skipIfRunning = true)
            null -> Unit
        }
    }

    private fun refreshAfterCommand() {
        when (val target = lastFetchTarget) {
            is FetchTarget.Single -> fetchAlerts(target.instance, skipIfRunning = false)
            is FetchTarget.All -> fetchAlertsForAll(target.instances, skipIfRunning = false)
            null -> Unit
        }
    }

    // ── Command deduplication ─────────────────────────────────────────────────
    //
    // Two-layer deduplication:
    //  1. activeCommandKeys — blocks while a command is in-flight (prevents concurrent duplicates)
    //  2. recentCommands   — blocks for COMMAND_COOLDOWN_MS after completion (prevents rapid repeats)
    //
    // Key format:  kind + U+001F + instanceId + U+001F + problem.uniqueId

    private val activeCommandKeys = HashSet<String>()
    private val recentCommands = HashMap<String, Long>()

    private fun commandKey(kind: String, instanceId: String, problem: NagiosProblem): String =
        "$kind$instanceId${problem.uniqueId}"

    private fun isCommandBlocked(kind: String, instanceId: String, problem: NagiosProblem): Boolean {
        val key = commandKey(kind, instanceId, problem)
        if (activeCommandKeys.contains(key)) return true
        val ts = recentCommands[key] ?: return false
        return (System.currentTimeMillis() - ts) < COMMAND_COOLDOWN_MS
    }

    private fun activateKeys(kind: String, instanceId: String, problems: List<NagiosProblem>): Set<String> {
        val keys = problems.map { commandKey(kind, instanceId, it) }.toSet()
        activeCommandKeys.addAll(keys)
        return keys
    }

    private fun deactivateKeys(keys: Set<String>) {
        activeCommandKeys.removeAll(keys)
    }

    private fun recordCompleted(kind: String, instanceId: String, problems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        problems.forEach { recentCommands[commandKey(kind, instanceId, it)] = now }
    }

    private fun cleanupRecentCommands() {
        val cutoff = System.currentTimeMillis() - COMMAND_COOLDOWN_MS
        recentCommands.entries.removeIf { (_, ts) -> ts < cutoff }
    }

    // ── Host-ACK cascade ──────────────────────────────────────────────────────

    /**
     * When [CommandSettings.ackServicesOnHostAck] is true, expands any [NagiosProblem.HostProblem]
     * in [selected] to also include all unacknowledged service problems on the same host from
     * the same instance.  Uses the raw current dashboard problem list (pre-filter) so services
     * hidden by dashboard filters are still included.
     */
    private fun expandWithRelatedServices(
        selected: List<NagiosProblem>,
        settings: CommandSettings,
    ): List<NagiosProblem> {
        if (!settings.ackServicesOnHostAck) return selected
        val allCurrent = currentProblems() ?: return selected

        val expanded = selected.toMutableList()
        for (p in selected) {
            if (p !is NagiosProblem.HostProblem) continue
            allCurrent.filterIsInstance<NagiosProblem.ServiceProblem>()
                .filter { svc ->
                    svc.hostName == p.hostName &&
                    svc.instanceId == p.instanceId &&
                    !svc.acknowledged
                }
                .forEach { expanded.add(it) }
        }
        // Deduplicate by instanceId+SEP+uniqueId
        return expanded.distinctBy { it.instanceId + SEP + it.uniqueId }
    }

    // ── Activity tracker helpers ──────────────────────────────────────────────

    private fun activityTargetId(instanceId: String, problem: NagiosProblem) =
        "$instanceId:${problem.uniqueId}"

    private fun commandTarget(
        instanceId: String,
        instanceName: String,
        problem: NagiosProblem,
    ) = CommandTargetResult(
        id = activityTargetId(instanceId, problem),
        instanceName = instanceName,
        instanceId = instanceId,
        hostName = problem.hostName,
        serviceName = (problem as? NagiosProblem.ServiceProblem)?.serviceName,
        status = CommandTargetStatus.PENDING,
    )

    private fun activityJobTitle(kind: String, count: Int, instanceName: String? = null): String {
        val base = "$kind: $count target${if (count != 1) "s" else ""}"
        return if (instanceName != null) "$base on $instanceName" else base
    }

    // ── ACK ───────────────────────────────────────────────────────────────────

    /** Single-instance ACK with host-cascade expansion and two-layer dedup. */
    fun acknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        val expanded = expandWithRelatedServices(problems, settings)
        cleanupRecentCommands()
        val fresh = expanded.distinctBy { it.uniqueId }
            .filter { !isCommandBlocked("ack", instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("ACK already submitted")
            return
        }

        val origKeys = problems.map { it.uniqueId }.toSet()
        val addedServiceCount = fresh.count { it is NagiosProblem.ServiceProblem && it.uniqueId !in origKeys }
        val hostCount = fresh.count { it is NagiosProblem.HostProblem }

        val activeKeys = activateKeys("ack", instance.id, fresh)
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.ACK,
            activityJobTitle("ACK", fresh.size, instance.name),
            fresh.map { commandTarget(instance.id, instance.name, it) },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    fresh.forEach { p ->
                        val tid = activityTargetId(instance.id, p)
                        CommandActivityTracker.markTargetRunning(jobId, tid)
                        try {
                            api.acknowledgeProblem(instance, p, settings)
                            CommandActivityTracker.markTargetSucceeded(jobId, tid)
                            succeeded.add(p)
                        } catch (e: Exception) {
                            CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                            failedCount[0]++
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    succeeded.forEach { p ->
                        localAcknowledgedMap[localAckKey(instance.id, p)] = LocalAckOverlay(
                            submittedAt = now,
                            hostName = p.hostName,
                            serviceName = (p as? NagiosProblem.ServiceProblem)?.serviceName,
                            instanceId = instance.id,
                        )
                    }
                    AckSuppressCache.recordAcked(
                        appContext,
                        succeeded.map { AckSuppressCache.suppressKey(instance.id, it) }.toSet(),
                    )
                    recordCompleted("ack", instance.id, succeeded)
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                val msg: String
                val newState: CommandState
                when {
                    fc == 0 -> {
                        msg = buildAckMsg(sc, hostCount, addedServiceCount)
                        EventLog.info(appContext, EventLog.CAT_COMMAND, "ACK submitted — ${instance.name}: $msg")
                        newState = CommandState.Success(msg)
                    }
                    sc == 0 -> {
                        msg = "ACK failed for all $fc target${if (fc != 1) "s" else ""}"
                        EventLog.error(appContext, EventLog.CAT_COMMAND, "ACK failed — ${instance.name}")
                        newState = CommandState.Error(msg)
                    }
                    else -> {
                        msg = "ACK: $sc succeeded, $fc failed"
                        EventLog.warn(appContext, EventLog.CAT_COMMAND, "ACK partial — ${instance.name}: $msg")
                        newState = CommandState.Warning(msg)
                    }
                }
                commandState = newState
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                val err = sanitizeError(e.message)
                EventLog.error(appContext, EventLog.CAT_COMMAND, "ACK failed — ${instance.name}: $err")
                commandState = CommandState.Error(e.message ?: "ACK failed")
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    /** ALL-mode ACK — routes each problem to its instance; host-cascade expansion; two-layer dedup. */
    fun acknowledgeProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        val expanded = expandWithRelatedServices(problems, settings)
        cleanupRecentCommands()
        val fresh = expanded.distinctBy { it.instanceId + SEP + it.uniqueId }
            .filter { !isCommandBlocked("ack", it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("ACK already submitted")
            return
        }

        val origKeys = problems.map { it.instanceId + SEP + it.uniqueId }.toSet()
        val addedServiceCount = fresh.count { p ->
            p is NagiosProblem.ServiceProblem && (p.instanceId + SEP + p.uniqueId) !in origKeys
        }
        val hostCount = fresh.count { it is NagiosProblem.HostProblem }

        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        val activeKeys = HashSet<String>()
        grouped.forEach { (id, group) ->
            if (instanceMap.containsKey(id)) activeKeys.addAll(activateKeys("ack", id, group))
        }
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.ACK,
            activityJobTitle("ACK", fresh.size),
            fresh.map { p ->
                val inst = instanceMap[p.instanceId]
                commandTarget(p.instanceId, inst?.name ?: p.instanceId, p)
            },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        group.forEach { p ->
                            val tid = activityTargetId(id, p)
                            CommandActivityTracker.markTargetRunning(jobId, tid)
                            try {
                                api.acknowledgeProblem(instance, p, settings)
                                CommandActivityTracker.markTargetSucceeded(jobId, tid)
                                succeeded.add(p)
                            } catch (e: Exception) {
                                CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                                failedCount[0]++
                            }
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    succeeded.forEach { p ->
                        localAcknowledgedMap[localAckKey(p.instanceId, p)] = LocalAckOverlay(
                            submittedAt = now,
                            hostName = p.hostName,
                            serviceName = (p as? NagiosProblem.ServiceProblem)?.serviceName,
                            instanceId = p.instanceId,
                        )
                    }
                    AckSuppressCache.recordAcked(
                        appContext,
                        succeeded.map { AckSuppressCache.suppressKey(it.instanceId, it) }.toSet(),
                    )
                    succeeded.groupBy { it.instanceId }.forEach { (id, group) ->
                        recordCompleted("ack", id, group)
                    }
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                val msg: String
                val newState: CommandState
                when {
                    fc == 0 -> {
                        msg = buildAckMsg(sc, hostCount, addedServiceCount)
                        newState = CommandState.Success(msg)
                    }
                    sc == 0 -> {
                        msg = "ACK failed for all $fc target${if (fc != 1) "s" else ""}"
                        newState = CommandState.Error(msg)
                    }
                    else -> {
                        msg = "ACK: $sc succeeded, $fc failed"
                        newState = CommandState.Warning(msg)
                    }
                }
                commandState = newState
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(e.message ?: "ACK failed")
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    // ── Unacknowledge ─────────────────────────────────────────────────────────

    /** Single-instance remove-ACK with two-layer dedup. */
    fun unacknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        commandSettings: CommandSettings,
    ) {
        val toSend = problems.filter {
            it.acknowledged || localAcknowledgedMap.containsKey(localAckKey(instance.id, it))
        }
        if (toSend.isEmpty()) {
            commandState = CommandState.Success("No acknowledged alerts selected")
            return
        }
        cleanupRecentCommands()
        val fresh = toSend.distinctBy { it.uniqueId }
            .filter { !isCommandBlocked("unack", instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Unack already submitted")
            return
        }
        val activeKeys = activateKeys("unack", instance.id, fresh)
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.REMOVE_ACK,
            activityJobTitle("Remove ACK", fresh.size, instance.name),
            fresh.map { commandTarget(instance.id, instance.name, it) },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    fresh.forEach { p ->
                        val tid = activityTargetId(instance.id, p)
                        CommandActivityTracker.markTargetRunning(jobId, tid)
                        try {
                            api.unacknowledgeProblem(instance, p, commandSettings)
                            CommandActivityTracker.markTargetSucceeded(jobId, tid)
                            succeeded.add(p)
                        } catch (e: Exception) {
                            CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                            failedCount[0]++
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                succeeded.forEach { localAcknowledgedMap.remove(localAckKey(instance.id, it)) }
                if (succeeded.isNotEmpty()) {
                    AckSuppressCache.removeKeys(
                        appContext,
                        succeeded.map { AckSuppressCache.suppressKey(instance.id, it) }.toSet(),
                    )
                    recordCompleted("unack", instance.id, succeeded)
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                commandState = when {
                    fc == 0 -> CommandState.Success(unackMsg(sc))
                    sc == 0 -> CommandState.Error("Remove ACK failed for all $fc target${if (fc != 1) "s" else ""}")
                    else    -> CommandState.Warning("Remove ACK: $sc succeeded, $fc failed")
                }
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(sanitizeError(e.message))
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    /** ALL-mode remove-ACK — routes each problem to its own instance; two-layer dedup. */
    fun unacknowledgeProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        commandSettings: CommandSettings,
    ) {
        val toSend = problems.filter { p ->
            p.acknowledged || localAcknowledgedMap.containsKey(localAckKey(p.instanceId, p))
        }
        if (toSend.isEmpty()) {
            commandState = CommandState.Success("No acknowledged alerts selected")
            return
        }
        cleanupRecentCommands()
        val fresh = toSend.distinctBy { it.instanceId + SEP + it.uniqueId }
            .filter { !isCommandBlocked("unack", it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Unack already submitted")
            return
        }
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        val activeKeys = HashSet<String>()
        grouped.forEach { (id, group) ->
            if (instanceMap.containsKey(id)) activeKeys.addAll(activateKeys("unack", id, group))
        }
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.REMOVE_ACK,
            activityJobTitle("Remove ACK", fresh.size),
            fresh.map { p ->
                val inst = instanceMap[p.instanceId]
                commandTarget(p.instanceId, inst?.name ?: p.instanceId, p)
            },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        group.forEach { p ->
                            val tid = activityTargetId(id, p)
                            CommandActivityTracker.markTargetRunning(jobId, tid)
                            try {
                                api.unacknowledgeProblem(instance, p, commandSettings)
                                CommandActivityTracker.markTargetSucceeded(jobId, tid)
                                succeeded.add(p)
                            } catch (e: Exception) {
                                CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                                failedCount[0]++
                            }
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                succeeded.forEach { localAcknowledgedMap.remove(localAckKey(it.instanceId, it)) }
                if (succeeded.isNotEmpty()) {
                    AckSuppressCache.removeKeys(
                        appContext,
                        succeeded.map { AckSuppressCache.suppressKey(it.instanceId, it) }.toSet(),
                    )
                    succeeded.groupBy { it.instanceId }.forEach { (id, group) ->
                        recordCompleted("unack", id, group)
                    }
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                commandState = when {
                    fc == 0 -> CommandState.Success(unackMsg(sc))
                    sc == 0 -> CommandState.Error("Remove ACK failed for all $fc target${if (fc != 1) "s" else ""}")
                    else    -> CommandState.Warning("Remove ACK: $sc succeeded, $fc failed")
                }
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(sanitizeError(e.message))
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    // ── Downtime ──────────────────────────────────────────────────────────────

    /** Single-instance downtime with two-layer dedup. Command key includes scope for precision. */
    fun scheduleDowntime(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        scope: DowntimeScope,
        durationMs: Long,
        comment: String,
        commandSettings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val kind = "downtime${SEP}${scope.name}"
        val fresh = problems.distinctBy { it.uniqueId }
            .filter { !isCommandBlocked(kind, instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Downtime already submitted")
            return
        }
        val activeKeys = activateKeys(kind, instance.id, fresh)
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.DOWNTIME,
            activityJobTitle("Downtime", fresh.size, instance.name),
            fresh.map { commandTarget(instance.id, instance.name, it) },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    fresh.forEach { p ->
                        val tid = activityTargetId(instance.id, p)
                        CommandActivityTracker.markTargetRunning(jobId, tid)
                        try {
                            api.scheduleDowntime(instance, p, scope, durationMs, comment, commandSettings)
                            CommandActivityTracker.markTargetSucceeded(jobId, tid)
                            succeeded.add(p)
                        } catch (e: Exception) {
                            CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                            failedCount[0]++
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) recordCompleted(kind, instance.id, succeeded)

                val fc = failedCount[0]
                val sc = succeeded.size
                commandState = when {
                    fc == 0 -> CommandState.Success(downtimeMsg(sc))
                    sc == 0 -> CommandState.Error("Downtime failed for all $fc target${if (fc != 1) "s" else ""}")
                    else    -> CommandState.Warning("Downtime: $sc succeeded, $fc failed")
                }
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(sanitizeError(e.message))
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    /** ALL-mode downtime — routes each problem to its own instance; two-layer dedup. */
    fun scheduleDowntime(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        scope: DowntimeScope,
        durationMs: Long,
        comment: String,
        commandSettings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val kind = "downtime${SEP}${scope.name}"
        val fresh = problems.distinctBy { it.instanceId + SEP + it.uniqueId }
            .filter { !isCommandBlocked(kind, it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Downtime already submitted")
            return
        }
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        val activeKeys = HashSet<String>()
        grouped.forEach { (id, group) ->
            if (instanceMap.containsKey(id)) activeKeys.addAll(activateKeys(kind, id, group))
        }
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.DOWNTIME,
            activityJobTitle("Downtime", fresh.size),
            fresh.map { p ->
                val inst = instanceMap[p.instanceId]
                commandTarget(p.instanceId, inst?.name ?: p.instanceId, p)
            },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        group.forEach { p ->
                            val tid = activityTargetId(id, p)
                            CommandActivityTracker.markTargetRunning(jobId, tid)
                            try {
                                api.scheduleDowntime(instance, p, scope, durationMs, comment, commandSettings)
                                CommandActivityTracker.markTargetSucceeded(jobId, tid)
                                succeeded.add(p)
                            } catch (e: Exception) {
                                CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                                failedCount[0]++
                            }
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) {
                    succeeded.groupBy { it.instanceId }.forEach { (id, group) ->
                        recordCompleted(kind, id, group)
                    }
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                commandState = when {
                    fc == 0 -> CommandState.Success(downtimeMsg(sc))
                    sc == 0 -> CommandState.Error("Downtime failed for all $fc target${if (fc != 1) "s" else ""}")
                    else    -> CommandState.Warning("Downtime: $sc succeeded, $fc failed")
                }
                refreshAfterCommand()
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(sanitizeError(e.message))
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    // ── Recheck ───────────────────────────────────────────────────────────────

    /** Single-instance recheck with two-layer dedup. */
    fun recheckProblems(instance: NagiosInstance, problems: List<NagiosProblem>, settings: CommandSettings) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.uniqueId }
            .filter { !isCommandBlocked("recheck", instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Recheck already submitted")
            return
        }
        val activeKeys = activateKeys("recheck", instance.id, fresh)
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.RECHECK,
            activityJobTitle("Recheck", fresh.size, instance.name),
            fresh.map { commandTarget(instance.id, instance.name, it) },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    fresh.forEach { p ->
                        val tid = activityTargetId(instance.id, p)
                        CommandActivityTracker.markTargetRunning(jobId, tid)
                        try {
                            api.recheckProblem(instance, p, settings)
                            CommandActivityTracker.markTargetSucceeded(jobId, tid)
                            succeeded.add(p)
                        } catch (e: Exception) {
                            CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                            failedCount[0]++
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) {
                    recordCompleted("recheck", instance.id, succeeded)
                    // Mark as pending so the card shows "Recheck pending" until Nagios runs the check
                    val submittedAt = System.currentTimeMillis()
                    succeeded.forEach { pendingRecheckMap[recheckPendingKey(instance.id, it)] = submittedAt }
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                val msg: String
                val newState: CommandState
                when {
                    fc == 0 -> {
                        msg = recheckMsg(sc)
                        EventLog.info(appContext, EventLog.CAT_COMMAND, "Recheck submitted — ${instance.name}: $msg")
                        newState = CommandState.Success(msg)
                    }
                    sc == 0 -> {
                        msg = "Recheck failed for all $fc target${if (fc != 1) "s" else ""}"
                        EventLog.error(appContext, EventLog.CAT_COMMAND, "Recheck failed — ${instance.name}")
                        newState = CommandState.Error(msg)
                    }
                    else -> {
                        msg = "Recheck: $sc succeeded, $fc failed"
                        EventLog.warn(appContext, EventLog.CAT_COMMAND, "Recheck partial — ${instance.name}: $msg")
                        newState = CommandState.Warning(msg)
                    }
                }
                commandState = newState
                refreshAfterCommand()
                // Delayed refreshes to catch Nagios after it executes the forced check
                viewModelScope.launch { kotlinx.coroutines.delay(3_000L); refreshAfterCommandDelayed() }
                viewModelScope.launch { kotlinx.coroutines.delay(8_000L); refreshAfterCommandDelayed() }
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                val err = sanitizeError(e.message)
                EventLog.error(appContext, EventLog.CAT_COMMAND, "Recheck failed — ${instance.name}: $err")
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    /** ALL-mode recheck with two-layer dedup. */
    fun recheckProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.instanceId + SEP + it.uniqueId }
            .filter { !isCommandBlocked("recheck", it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Recheck already submitted")
            return
        }
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        val activeKeys = HashSet<String>()
        grouped.forEach { (id, group) ->
            if (instanceMap.containsKey(id)) activeKeys.addAll(activateKeys("recheck", id, group))
        }
        commandState = CommandState.Loading

        val jobId = UUID.randomUUID().toString()
        CommandActivityTracker.startJob(
            jobId, CommandJobType.RECHECK,
            activityJobTitle("Recheck", fresh.size),
            fresh.map { p ->
                val inst = instanceMap[p.instanceId]
                commandTarget(p.instanceId, inst?.name ?: p.instanceId, p)
            },
        )

        viewModelScope.launch {
            val succeeded = mutableListOf<NagiosProblem>()
            val failedCount = intArrayOf(0)
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        group.forEach { p ->
                            val tid = activityTargetId(id, p)
                            CommandActivityTracker.markTargetRunning(jobId, tid)
                            try {
                                api.recheckProblem(instance, p, settings)
                                CommandActivityTracker.markTargetSucceeded(jobId, tid)
                                succeeded.add(p)
                            } catch (e: Exception) {
                                CommandActivityTracker.markTargetFailed(jobId, tid, sanitizeError(e.message))
                                failedCount[0]++
                            }
                        }
                    }
                }
                CommandActivityTracker.finishJob(jobId)

                if (succeeded.isNotEmpty()) {
                    succeeded.groupBy { it.instanceId }.forEach { (id, group) ->
                        recordCompleted("recheck", id, group)
                    }
                    val submittedAt = System.currentTimeMillis()
                    succeeded.forEach { pendingRecheckMap[recheckPendingKey(it.instanceId, it)] = submittedAt }
                }

                val fc = failedCount[0]
                val sc = succeeded.size
                commandState = when {
                    fc == 0 -> CommandState.Success(recheckMsg(sc))
                    sc == 0 -> CommandState.Error("Recheck failed for all $fc target${if (fc != 1) "s" else ""}")
                    else    -> CommandState.Warning("Recheck: $sc succeeded, $fc failed")
                }
                refreshAfterCommand()
                viewModelScope.launch { kotlinx.coroutines.delay(3_000L); refreshAfterCommandDelayed() }
                viewModelScope.launch { kotlinx.coroutines.delay(8_000L); refreshAfterCommandDelayed() }
            } catch (e: Exception) {
                CommandActivityTracker.finishJob(jobId)
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    // Tracks problems for which a forced recheck was submitted but Nagios has not yet
    // executed the check (lastCheck < submittedAt).  While pending, ProblemCard shows
    // "Recheck pending — waiting for fresh result".
    // The map is keyed by instanceId + SEP + uniqueId → submittedAtMillis.

    private val pendingRecheckMap = mutableStateMapOf<String, Long>()

    fun isRecheckPending(instanceId: String, problem: NagiosProblem): Boolean {
        val ts = pendingRecheckMap[recheckPendingKey(instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - ts) < RECHECK_TTL_MS
    }

    private fun recheckPendingKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId$SEP${problem.uniqueId}"

    /** Remove pending recheck entries whose lastCheck is now >= submittedAt (check ran). */
    private fun reconcilePendingRechecks(instanceId: String, serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val prefix = "$instanceId$SEP"
        val toRemove = pendingRecheckMap.keys.toList().filter { key ->
            if (!key.startsWith(prefix)) return@filter false
            val ts = pendingRecheckMap[key] ?: return@filter true
            if ((now - ts) > RECHECK_TTL_MS) return@filter true
            val uniqueId = key.removePrefix(prefix)
            val problem = serverProblems.find { it.uniqueId == uniqueId } ?: return@filter false
            // Clear when Nagios lastCheck is at or after submission (5 s tolerance)
            (problem.lastCheck ?: 0L) >= (ts - 5_000L)
        }
        toRemove.forEach { pendingRecheckMap.remove(it) }
    }

    /** All-mode variant: reconcile across all instances in the merged list. */
    private fun reconcilePendingRechecksAll(serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val toRemove = pendingRecheckMap.keys.toList().filter { key ->
            val ts = pendingRecheckMap[key] ?: return@filter true
            if ((now - ts) > RECHECK_TTL_MS) return@filter true
            val problem = serverProblems.find { recheckPendingKey(it.instanceId, it) == key }
                ?: return@filter false
            (problem.lastCheck ?: 0L) >= (ts - 5_000L)
        }
        toRemove.forEach { pendingRecheckMap.remove(it) }
    }

    // ── Local ACK overlay ─────────────────────────────────────────────────────
    //
    // Keys use U+001F as separator between instanceId and uniqueId to prevent
    // false prefix matches when one instance UUID is a prefix of another.
    //
    // Grace period: keep the optimistic ACK chip visible for LOCAL_ACK_GRACE_MS after submission
    // even if Nagios still returns acknowledged=false.  Remove immediately if Nagios confirms
    // acknowledged=true.  Warn the user if still unconfirmed after the grace period.

    private data class LocalAckOverlay(
        val submittedAt: Long,
        val hostName: String,
        val serviceName: String?,
        val instanceId: String,
    )

    private val localAcknowledgedMap = mutableStateMapOf<String, LocalAckOverlay>()

    fun isLocallyAcknowledged(instanceId: String, problem: NagiosProblem): Boolean {
        val overlay = localAcknowledgedMap[localAckKey(instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - overlay.submittedAt) < LOCAL_ACK_TTL_MS
    }

    private fun localAckKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId$SEP${problem.uniqueId}"

    /**
     * Reconcile local ACK overlays for a single instance.
     *
     * - Server acknowledged=true  → remove (confirmed)
     * - Server acknowledged=false, age < GRACE → keep
     * - Server acknowledged=false, age >= GRACE → remove and warn
     * - Problem missing from server → keep until TTL
     */
    private fun reconcileLocalAck(instanceId: String, serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val prefix = "$instanceId$SEP"
        val unconfirmed = mutableListOf<LocalAckOverlay>()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            if (!key.startsWith(prefix)) return@filter false
            val overlay = localAcknowledgedMap[key] ?: return@filter true
            val ageMs = now - overlay.submittedAt
            if (ageMs > LOCAL_ACK_TTL_MS) return@filter true
            val uniqueId = key.removePrefix(prefix)
            val serverProblem = serverProblems.find { it.uniqueId == uniqueId }
            when {
                serverProblem == null -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] keep pending instance=$instanceId host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"} ageMs=$ageMs")
                    false
                }
                serverProblem.acknowledged -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] confirmed by server instance=$instanceId host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"}")
                    true
                }
                ageMs < LOCAL_ACK_GRACE_MS -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] keep pending instance=$instanceId host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"} ageMs=$ageMs")
                    false
                }
                else -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] not confirmed after grace instance=$instanceId host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"}")
                    unconfirmed.add(overlay)
                    true
                }
            }
        }

        toRemove.forEach { localAcknowledgedMap.remove(it) }
        if (unconfirmed.isNotEmpty() && commandState is CommandState.Idle) {
            commandState = CommandState.Warning(buildUnconfirmedAckWarning(unconfirmed))
        }
    }

    /** Reconcile local ACK overlays across all instances (ALL-mode fetch). */
    private fun reconcileLocalAckAll(serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverProblemsByKey = serverProblems.associateBy { localAckKey(it.instanceId, it) }
        val unconfirmed = mutableListOf<LocalAckOverlay>()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            val overlay = localAcknowledgedMap[key] ?: return@filter true
            val ageMs = now - overlay.submittedAt
            if (ageMs > LOCAL_ACK_TTL_MS) return@filter true
            val serverProblem = serverProblemsByKey[key]
            when {
                serverProblem == null -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] keep pending instance=${overlay.instanceId} host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"} ageMs=$ageMs")
                    false
                }
                serverProblem.acknowledged -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] confirmed by server instance=${overlay.instanceId} host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"}")
                    true
                }
                ageMs < LOCAL_ACK_GRACE_MS -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] keep pending instance=${overlay.instanceId} host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"} ageMs=$ageMs")
                    false
                }
                else -> {
                    android.util.Log.d("qNag", "[LOCAL_ACK] not confirmed after grace instance=${overlay.instanceId} host=${overlay.hostName} service=${overlay.serviceName ?: "(host)"}")
                    unconfirmed.add(overlay)
                    true
                }
            }
        }

        toRemove.forEach { localAcknowledgedMap.remove(it) }
        if (unconfirmed.isNotEmpty() && commandState is CommandState.Idle) {
            commandState = CommandState.Warning(buildUnconfirmedAckWarning(unconfirmed))
        }
    }

    private fun buildUnconfirmedAckWarning(overlays: List<LocalAckOverlay>): String =
        if (overlays.size == 1) {
            val o = overlays.first()
            if (o.serviceName != null) "ACK was submitted but Nagios did not confirm it for ${o.serviceName} on ${o.hostName}."
            else "ACK was submitted but Nagios did not confirm it for host ${o.hostName}."
        } else {
            "ACK was submitted but Nagios did not confirm it for ${overlays.size} services."
        }

    // ── ACK comment (detail screen) ───────────────────────────────────────────

    var ackCommentState by mutableStateOf<AckCommentState>(AckCommentState.Idle)
        private set

    private var ackCommentJob: Job? = null

    /**
     * Fetch the newest acknowledgement comment for [problem] from the Nagios commentlist
     * endpoint.  Replaces any in-flight fetch.  Call [clearAckCommentState] when the detail
     * screen leaves composition to cancel the job and reset the state.
     */
    fun fetchAckComment(instance: NagiosInstance, problem: NagiosProblem) {
        ackCommentJob?.cancel()
        ackCommentState = AckCommentState.Loading
        ackCommentJob = viewModelScope.launch {
            try {
                val comment = withContext(Dispatchers.IO) { api.fetchAckComment(instance, problem) }
                ackCommentState = AckCommentState.Loaded(comment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ackCommentState = AckCommentState.Error(sanitizeError(e.message))
            }
        }
    }

    fun clearAckCommentState() {
        ackCommentJob?.cancel()
        ackCommentJob = null
        ackCommentState = AckCommentState.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun clearCommandState() { commandState = CommandState.Idle }

    // ── Instance summaries ────────────────────────────────────────────────────

    private fun currentSummaries(): List<InstanceSummary> = when (val s = uiState) {
        is DashboardState.Success -> s.instanceSummaries
        is DashboardState.Loading -> s.previousSummaries
        is DashboardState.Error -> s.previousSummaries
        else -> emptyList()
    }

    private fun currentLastUpdated(): Long? = when (val s = uiState) {
        is DashboardState.Success -> s.lastUpdated
        is DashboardState.Loading -> s.previousLastUpdated
        is DashboardState.Error -> s.previousLastUpdated
        else -> null
    }

    private fun currentFetchKey(): String? = when (val t = lastFetchTarget) {
        is FetchTarget.Single -> "single:${t.instance.id}"
        is FetchTarget.All -> "all:${t.instances.map { it.id }.sorted().joinToString(",")}"
        null -> null
    }

    /**
     * Returns true when the current dashboard data is recent enough to skip an automatic
     * re-fetch triggered by screen re-entry (e.g. returning from Settings).
     *
     * Rules:
     *  - Error state → always false (always retry after failure)
     *  - Loading state → true (a fetch is already in flight; don't pile on)
     *  - Requested scope differs from the last fetched scope → false (different target)
     *  - Last successful update within [AUTO_REFRESH_MIN_AGE_MS] → true (data is fresh)
     */
    fun isDataFreshEnough(requestedKey: String): Boolean {
        val state = uiState
        if (state is DashboardState.Error) return false
        if (state is DashboardState.Loading) return true
        if (currentFetchKey() != requestedKey) return false
        val lu = currentLastUpdated() ?: return false
        return (System.currentTimeMillis() - lu) < AUTO_REFRESH_MIN_AGE_MS
    }

    private fun buildInstanceSummary(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        lastUpdated: Long,
    ): InstanceSummary = InstanceSummary(
        instanceId = instance.id,
        instanceName = instance.name,
        enabled = instance.enabled,
        notificationsEnabled = instance.notificationsEnabled,
        lastUpdated = lastUpdated,
        fetchError = null,
        hostDown = problems.count { it is NagiosProblem.HostProblem && it.status == NagiosStatus.HOST_DOWN },
        hostUnreachable = problems.count { it is NagiosProblem.HostProblem && it.status == NagiosStatus.HOST_UNREACHABLE },
        hostAcked = problems.count { it is NagiosProblem.HostProblem && it.acknowledged },
        serviceCritical = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_CRITICAL },
        serviceWarning = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_WARNING },
        serviceUnknown = problems.count { it is NagiosProblem.ServiceProblem && it.status == NagiosStatus.SERVICE_UNKNOWN },
        serviceAcked = problems.count { it is NagiosProblem.ServiceProblem && it.acknowledged },
        totalProblems = problems.size,
    )

    /** Stale-preserving error summary: keeps last-known counts and lastUpdated when a stale entry exists. */
    private fun buildErrorSummary(
        instance: NagiosInstance,
        error: String,
        stale: InstanceSummary?,
    ): InstanceSummary = stale?.copy(fetchError = error)
        ?: InstanceSummary(
            instanceId = instance.id, instanceName = instance.name,
            enabled = instance.enabled, notificationsEnabled = instance.notificationsEnabled,
            lastUpdated = null, fetchError = error,
            hostDown = 0, hostUnreachable = 0, hostAcked = 0,
            serviceCritical = 0, serviceWarning = 0, serviceUnknown = 0,
            serviceAcked = 0, totalProblems = 0,
        )

    private fun problemDisplayFingerprint(p: NagiosProblem): String = buildString {
        append(p.status).append(SEP)
        append(p.acknowledged).append(SEP)
        append(p.notificationsEnabled).append(SEP)
        append(p.checksEnabled).append(SEP)
        append(p.scheduledDowntimeDepth).append(SEP)
        append(p.isFlapping).append(SEP)
        append(p.isSoftState).append(SEP)
        append(p.pluginOutput).append(SEP)
        append(p.currentAttempt).append(SEP)
        append(p.maxAttempts).append(SEP)
        append(p.acknowledgedBy).append(SEP)
        append(p.acknowledgementComment).append(SEP)
        append(p.acknowledgementTime)
        if (p is NagiosProblem.ServiceProblem) {
            append(SEP).append(p.hostStatus)
            append(SEP).append(p.hostAcknowledged)
            append(SEP).append(p.hostScheduledDowntimeDepth)
        }
    }

    /**
     * Returns true when [incoming] and [existing] have the same problems (by identity) with
     * identical display fingerprints.  Order-independent.  When true, callers reuse [existing]
     * to keep the list reference stable and avoid Compose recompositions and scroll resets.
     */
    private fun problemsMatchStable(incoming: List<NagiosProblem>, existing: List<NagiosProblem>): Boolean {
        if (incoming.size != existing.size) return false
        val inMap = incoming.associateBy { "${it.instanceId}$SEP${it.uniqueId}" }
        val exMap = existing.associateBy { "${it.instanceId}$SEP${it.uniqueId}" }
        if (inMap.keys != exMap.keys) return false
        return inMap.all { (key, p) ->
            val e = exMap[key] ?: return false
            problemDisplayFingerprint(p) == problemDisplayFingerprint(e)
        }
    }

    private fun currentProblems(): List<NagiosProblem>? = when (val s = uiState) {
        is DashboardState.Success -> s.problems
        is DashboardState.Loading -> s.previousProblems
        is DashboardState.Error -> s.previousProblems
        else -> null
    }

    private fun buildAckMsg(totalFresh: Int, hostCount: Int, addedServiceCount: Int): String {
        return if (addedServiceCount > 0 && hostCount > 0) {
            "Acknowledged $hostCount host${if (hostCount != 1) "s" else ""} and $addedServiceCount related service${if (addedServiceCount != 1) "s" else ""}"
        } else {
            "Acknowledged $totalFresh problem${if (totalFresh != 1) "s" else ""}"
        }
    }

    private fun recheckMsg(count: Int) = "Recheck submitted for $count problem${if (count != 1) "s" else ""}"

    private fun unackMsg(count: Int) = "Removed ACK from $count alert${if (count != 1) "s" else ""}"

    private fun downtimeMsg(count: Int) = "Downtime scheduled for $count alert${if (count != 1) "s" else ""}"

    private fun sanitizeError(msg: String?): String =
        (msg ?: "Unknown error")
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .take(200)

    companion object {
        private const val LOCAL_ACK_TTL_MS = 5 * 60 * 1_000L
        private const val LOCAL_ACK_GRACE_MS = 60_000L
        private const val RECHECK_TTL_MS = 5 * 60 * 1_000L    // pending recheck expires after 5 min
        private const val COMMAND_COOLDOWN_MS = 5_000L
        // U+001F (unit separator): cannot appear in UUIDs or Nagios host/service names,
        // preventing false prefix matches in key lookups.
        private const val SEP = "\u001F"
        // Minimum age for data to be considered stale by the auto-refresh guard.
        // Data younger than this is treated as fresh; the dashboard re-entry fetch is skipped.
        internal const val AUTO_REFRESH_MIN_AGE_MS = 30_000L
    }
}
