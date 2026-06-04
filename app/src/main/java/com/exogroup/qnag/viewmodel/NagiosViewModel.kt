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

    // ── ACK ───────────────────────────────────────────────────────────────────

    /** Single-instance ACK (single-instance mode). */
    fun acknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.acknowledgeProblems(instance, problems, settings) }
                val now = System.currentTimeMillis()
                problems.forEach { localAcknowledgedMap[localAckKey(instance.id, it)] = now }
                commandState = CommandState.Success(ackMsg(problems.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "ACK failed")
            }
        }
    }

    /** ALL-mode ACK: routes each problem to its correct instance via problem.instanceId. */
    fun acknowledgeProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = problems.groupBy { it.instanceId }

        // Shortcut: single instance → reuse existing overload
        if (grouped.size == 1) {
            val (instanceId, group) = grouped.entries.first()
            val instance = instanceMap[instanceId]
            if (instance != null) { acknowledgeProblems(instance, group, settings); return }
        }

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
                problems.forEach { localAcknowledgedMap[localAckKey(it.instanceId, it)] = now }
                commandState = CommandState.Success(ackMsg(problems.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "ACK failed")
            }
        }
    }

    // ── Recheck ───────────────────────────────────────────────────────────────

    fun recheckProblems(instance: NagiosInstance, problems: List<NagiosProblem>, settings: CommandSettings) {
        if (problems.isEmpty()) return
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.recheckProblems(instance, problems, settings) }
                commandState = CommandState.Success(recheckMsg(problems.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            }
        }
    }

    /** ALL-mode recheck: routes each problem to its correct instance via problem.instanceId. */
    fun recheckProblems(
        allInstances: List<NagiosInstance>,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        val instanceMap = allInstances.associateBy { it.id }
        val grouped = problems.groupBy { it.instanceId }

        if (grouped.size == 1) {
            val (instanceId, group) = grouped.entries.first()
            val instance = instanceMap[instanceId]
            if (instance != null) { recheckProblems(instance, group, settings); return }
        }

        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        api.recheckProblems(instance, group, settings)
                    }
                }
                commandState = CommandState.Success(recheckMsg(problems.size))
                refreshAfterCommand()
            } catch (e: Exception) {
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
    }
}
