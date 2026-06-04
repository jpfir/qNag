package com.exogroup.qnag.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Dashboard data state ──────────────────────────────────────────────────────

sealed class DashboardState {
    object Idle : DashboardState()
    data class Loading(val previousProblems: List<NagiosProblem>? = null) : DashboardState()
    data class Success(val problems: List<NagiosProblem>, val lastUpdated: Long) : DashboardState()
    data class Error(val message: String, val previousProblems: List<NagiosProblem>? = null) : DashboardState()
}

// ── Command (ACK / recheck) state ─────────────────────────────────────────────

sealed class CommandState {
    object Idle : CommandState()
    object Loading : CommandState()
    data class Success(val message: String) : CommandState()
    data class Error(val message: String) : CommandState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class NagiosViewModel : ViewModel() {

    var uiState by mutableStateOf<DashboardState>(DashboardState.Idle)
        private set

    var commandState by mutableStateOf<CommandState>(CommandState.Idle)
        private set

    private val api = NagiosApi()

    // ── Fetch target tracking — used by refreshAfterCommand() ─────────────────

    private sealed class FetchTarget {
        data class Single(val instance: NagiosInstance) : FetchTarget()
        data class All(val instances: List<NagiosInstance>) : FetchTarget()
    }

    private var lastFetchTarget: FetchTarget? = null

    // ── Fetch deduplication ───────────────────────────────────────────────────

    private var fetchJob: Job? = null

    fun fetchAlerts(instance: NagiosInstance, skipIfRunning: Boolean = false) {
        if (skipIfRunning && fetchJob?.isActive == true) return

        fetchJob?.cancel()
        lastFetchTarget = FetchTarget.Single(instance)
        val stale = currentProblems()
        uiState = DashboardState.Loading(stale)
        fetchJob = viewModelScope.launch {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }
                reconcileLocalAck(instance.id, problems)
                uiState = DashboardState.Success(problems, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale)
            }
        }
    }

    /** Fetch from all instances in parallel and merge results into a single flat list. */
    fun fetchAlertsForAll(instances: List<NagiosInstance>, skipIfRunning: Boolean = false) {
        if (instances.isEmpty()) return
        if (skipIfRunning && fetchJob?.isActive == true) return

        fetchJob?.cancel()
        lastFetchTarget = FetchTarget.All(instances)
        val stale = currentProblems()
        uiState = DashboardState.Loading(stale)

        fetchJob = viewModelScope.launch {
            try {
                // Fetch all instances concurrently; individual failures return empty lists
                // so a single unreachable instance doesn't block the combined view.
                val allProblems = instances.map { inst ->
                    async(Dispatchers.IO) {
                        try { api.fetchProblems(inst) } catch (_: Exception) { emptyList() }
                    }
                }.awaitAll().flatten()
                    .sortedWith(compareBy({ severityRank(it) }, { it.hostName }, { serviceNameOf(it) }))

                reconcileLocalAckAll(allProblems)
                uiState = DashboardState.Success(allProblems, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale)
            }
        }
    }

    /** Refresh using the same target (instance or ALL) as the last fetch. */
    private fun refreshAfterCommand() {
        when (val target = lastFetchTarget) {
            is FetchTarget.Single -> fetchAlerts(target.instance, skipIfRunning = false)
            is FetchTarget.All -> fetchAlertsForAll(target.instances, skipIfRunning = false)
            null -> Unit
        }
    }

    // ── Command deduplication ─────────────────────────────────────────────────
    //
    // Prevents the same ACK or recheck from being submitted multiple times within
    // COMMAND_COOLDOWN_MS, whether from a rapid double-tap, a swipe that fires
    // confirmValueChange multiple times, or a bulk action with duplicate entries.
    // Key: "kindinstanceIduniqueId"  (U+001F separates fields to prevent collisions)

    private val recentCommands = HashMap<String, Long>()

    private fun commandKey(kind: String, instanceId: String, problem: NagiosProblem): String =
        "$kind$instanceId${problem.uniqueId}"

    private fun isCommandRecent(kind: String, instanceId: String, problem: NagiosProblem): Boolean {
        val ts = recentCommands[commandKey(kind, instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - ts) < COMMAND_COOLDOWN_MS
    }

    private fun recordCommands(kind: String, instanceId: String, problems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        problems.forEach { recentCommands[commandKey(kind, instanceId, it)] = now }
    }

    private fun removeCommandRecords(kind: String, instanceId: String, problems: List<NagiosProblem>) {
        problems.forEach { recentCommands.remove(commandKey(kind, instanceId, it)) }
    }

    private fun cleanupRecentCommands() {
        val cutoff = System.currentTimeMillis() - COMMAND_COOLDOWN_MS
        recentCommands.entries.removeIf { (_, ts) -> ts < cutoff }
    }

    // ── ACK ───────────────────────────────────────────────────────────────────

    /** Single-instance ACK — de-duplicated by uniqueId and 5 s cooldown. */
    fun acknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.uniqueId }
            .filter { !isCommandRecent("ack", instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("ACK already submitted")
            return
        }
        recordCommands("ack", instance.id, fresh)
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.acknowledgeProblems(instance, fresh, settings) }
                val now = System.currentTimeMillis()
                fresh.forEach { localAcknowledgedMap[localAckKey(instance.id, it)] = now }
                commandState = CommandState.Success(ackMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                removeCommandRecords("ack", instance.id, fresh)  // allow immediate retry after error
                commandState = CommandState.Error(e.message ?: "ACK failed")
            }
        }
    }

    /** ALL-mode ACK — de-duplicated by instanceId+uniqueId; routes each problem to its instance. */
    fun acknowledgeProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.instanceId + "" + it.uniqueId }
            .filter { !isCommandRecent("ack", it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("ACK already submitted")
            return
        }
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        grouped.forEach { (id, group) -> if (instanceMap.containsKey(id)) recordCommands("ack", id, group) }
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        api.acknowledgeProblems(instance, group, settings)
                    }
                }
                val now = System.currentTimeMillis()
                fresh.forEach { localAcknowledgedMap[localAckKey(it.instanceId, it)] = now }
                commandState = CommandState.Success(ackMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                grouped.forEach { (id, group) -> removeCommandRecords("ack", id, group) }
                commandState = CommandState.Error(e.message ?: "ACK failed")
            }
        }
    }

    // ── Recheck ───────────────────────────────────────────────────────────────

    /** Single-instance recheck — de-duplicated by uniqueId and 5 s cooldown. */
    fun recheckProblems(instance: NagiosInstance, problems: List<NagiosProblem>, settings: CommandSettings) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.uniqueId }
            .filter { !isCommandRecent("recheck", instance.id, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Recheck already submitted")
            return
        }
        recordCommands("recheck", instance.id, fresh)
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.recheckProblems(instance, fresh, settings) }
                commandState = CommandState.Success(recheckMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                removeCommandRecords("recheck", instance.id, fresh)
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            }
        }
    }

    /** ALL-mode recheck — de-duplicated by instanceId+uniqueId; routes each problem to its instance. */
    fun recheckProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        cleanupRecentCommands()
        val fresh = problems.distinctBy { it.instanceId + "" + it.uniqueId }
            .filter { !isCommandRecent("recheck", it.instanceId, it) }
        if (fresh.isEmpty()) {
            commandState = CommandState.Success("Recheck already submitted")
            return
        }
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = fresh.groupBy { it.instanceId }
        grouped.forEach { (id, group) -> if (instanceMap.containsKey(id)) recordCommands("recheck", id, group) }
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        api.recheckProblems(instance, group, settings)
                    }
                }
                commandState = CommandState.Success(recheckMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                grouped.forEach { (id, group) -> removeCommandRecords("recheck", id, group) }
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            }
        }
    }

    // ── Local ACK overlay ─────────────────────────────────────────────────────

    private val localAcknowledgedMap = mutableStateMapOf<String, Long>()

    fun isLocallyAcknowledged(instanceId: String, problem: NagiosProblem): Boolean {
        val ts = localAcknowledgedMap[localAckKey(instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - ts) < LOCAL_ACK_TTL_MS
    }

    private fun localAckKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId${problem.uniqueId}"

    /** Remove local ACK entries confirmed by server or expired (single-instance fetch). */
    private fun reconcileLocalAck(instanceId: String, serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverAckedIds = serverProblems.filter { it.acknowledged }.map { it.uniqueId }.toSet()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            if (!key.startsWith(instanceId)) return@filter false
            val uniqueId = key.substringAfter(instanceId)
            val ts = localAcknowledgedMap[key] ?: return@filter true
            uniqueId in serverAckedIds || (now - ts > LOCAL_ACK_TTL_MS)
        }
        toRemove.forEach { localAcknowledgedMap.remove(it) }
    }

    /** Remove local ACK entries for all instances (ALL-mode fetch). */
    private fun reconcileLocalAckAll(serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverAckedKeys = serverProblems
            .filter { it.acknowledged && it.instanceId.isNotEmpty() }
            .map { localAckKey(it.instanceId, it) }
            .toSet()
        val serverAckedUniqueIds = serverProblems.filter { it.acknowledged }.map { it.uniqueId }.toSet()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            val ts = localAcknowledgedMap[key] ?: return@filter true
            if ((now - ts) > LOCAL_ACK_TTL_MS) return@filter true
            key in serverAckedKeys || serverAckedUniqueIds.any { uid -> key.endsWith(uid) }
        }
        toRemove.forEach { localAcknowledgedMap.remove(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun clearCommandState() { commandState = CommandState.Idle }

    private fun currentProblems(): List<NagiosProblem>? = when (val s = uiState) {
        is DashboardState.Success -> s.problems
        is DashboardState.Loading -> s.previousProblems
        is DashboardState.Error -> s.previousProblems
        else -> null
    }

    private fun severityRank(p: NagiosProblem): Int = when {
        p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_DOWN -> 0
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL -> 1
        p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_UNREACHABLE -> 2
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN -> 3
        p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING -> 4
        else -> 5
    }

    private fun serviceNameOf(p: NagiosProblem): String =
        if (p is NagiosProblem.ServiceProblem) p.serviceName else ""

    private fun ackMsg(count: Int) = "Acknowledged $count problem${if (count != 1) "s" else ""}"
    private fun recheckMsg(count: Int) = "Recheck submitted for $count problem${if (count != 1) "s" else ""}"

    companion object {
        private const val LOCAL_ACK_TTL_MS = 5 * 60 * 1_000L
        // Same command/problem pair cannot be submitted again within this window.
        private const val COMMAND_COOLDOWN_MS = 5_000L
    }
}
