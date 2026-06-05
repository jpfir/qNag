package com.exogroup.qnag.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.exogroup.qnag.data.AckSuppressCache
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.InstanceSummary
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
    data class Loading(
        val previousProblems: List<NagiosProblem>? = null,
        val previousSummaries: List<InstanceSummary> = emptyList(),
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
    ) : DashboardState()
}

// ── Command (ACK / recheck) state ─────────────────────────────────────────────

sealed class CommandState {
    object Idle : CommandState()
    object Loading : CommandState()
    data class Success(val message: String) : CommandState()
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
        uiState = DashboardState.Loading(stale, staleSummaries)
        fetchJob = viewModelScope.launch {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }
                val now = System.currentTimeMillis()
                reconcileLocalAck(instance.id, problems)
                val summary = buildInstanceSummary(instance, problems, now)
                uiState = DashboardState.Success(problems, now, instanceSummaries = listOf(summary))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errSummary = buildErrorSummary(
                    instance, sanitizeError(e.message),
                    staleSummaries.find { it.instanceId == instance.id },
                )
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale, listOf(errSummary))
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
        uiState = DashboardState.Loading(stale, staleSummaries)

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
                    .sortedWith(compareBy({ severityRank(it) }, { it.hostName }, { serviceNameOf(it) }))
                val errors = deferreds.mapNotNull { it.second }
                val summaries = deferreds.map { it.third }

                reconcileLocalAckAll(allProblems)

                if (allProblems.isEmpty() && errors.size == instances.size) {
                    uiState = DashboardState.Error(
                        "Failed to refresh all instances. ${errors.firstOrNull() ?: ""}",
                        stale,
                        summaries,
                    )
                } else {
                    uiState = DashboardState.Success(allProblems, now, errors, summaries)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = DashboardState.Error(e.message ?: "Unknown network error", stale, staleSummaries)
            }
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
        "$kind$instanceId${problem.uniqueId}"

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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.acknowledgeProblems(instance, fresh, settings) }
                val now = System.currentTimeMillis()
                fresh.forEach { localAcknowledgedMap[localAckKey(instance.id, it)] = now }
                // Write to suppress cache so background polling doesn't re-notify before Nagios confirms
                AckSuppressCache.recordAcked(
                    appContext,
                    fresh.map { AckSuppressCache.suppressKey(instance.id, it) }.toSet(),
                )
                recordCompleted("ack", instance.id, fresh)
                commandState = CommandState.Success(buildAckMsg(fresh.size, hostCount, addedServiceCount))
                refreshAfterCommand()
            } catch (e: Exception) {
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
                AckSuppressCache.recordAcked(
                    appContext,
                    fresh.map { AckSuppressCache.suppressKey(it.instanceId, it) }.toSet(),
                )
                grouped.forEach { (id, group) -> recordCompleted("ack", id, group) }
                commandState = CommandState.Success(buildAckMsg(fresh.size, hostCount, addedServiceCount))
                refreshAfterCommand()
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "ACK failed")
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.recheckProblems(instance, fresh, settings) }
                recordCompleted("recheck", instance.id, fresh)
                commandState = CommandState.Success(recheckMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    grouped.forEach { (id, group) ->
                        val instance = instanceMap[id] ?: return@forEach
                        api.recheckProblems(instance, group, settings)
                    }
                }
                grouped.forEach { (id, group) -> recordCompleted("recheck", id, group) }
                commandState = CommandState.Success(recheckMsg(fresh.size))
                refreshAfterCommand()
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            } finally {
                deactivateKeys(activeKeys)
            }
        }
    }

    // ── Local ACK overlay ─────────────────────────────────────────────────────
    //
    // Keys use U+001F as separator between instanceId and uniqueId to prevent
    // false prefix matches when one instance UUID is a prefix of another.

    private val localAcknowledgedMap = mutableStateMapOf<String, Long>()

    fun isLocallyAcknowledged(instanceId: String, problem: NagiosProblem): Boolean {
        val ts = localAcknowledgedMap[localAckKey(instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - ts) < LOCAL_ACK_TTL_MS
    }

    private fun localAckKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId$SEP${problem.uniqueId}"

    /** Remove local ACK entries confirmed by server or expired (single-instance fetch). */
    private fun reconcileLocalAck(instanceId: String, serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverAckedIds = serverProblems.filter { it.acknowledged }.map { it.uniqueId }.toSet()
        val prefix = "$instanceId$SEP"

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            if (!key.startsWith(prefix)) return@filter false
            val uniqueId = key.removePrefix(prefix)
            val ts = localAcknowledgedMap[key] ?: return@filter true
            uniqueId in serverAckedIds || (now - ts > LOCAL_ACK_TTL_MS)
        }
        toRemove.forEach { localAcknowledgedMap.remove(it) }
    }

    /** Remove local ACK entries for all instances (ALL-mode fetch). */
    private fun reconcileLocalAckAll(serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverAckedKeys = serverProblems
            .filter { it.acknowledged }
            .map { localAckKey(it.instanceId, it) }
            .toSet()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            val ts = localAcknowledgedMap[key] ?: return@filter true
            (now - ts) > LOCAL_ACK_TTL_MS || key in serverAckedKeys
        }
        toRemove.forEach { localAcknowledgedMap.remove(it) }
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

    private fun buildAckMsg(totalFresh: Int, hostCount: Int, addedServiceCount: Int): String {
        return if (addedServiceCount > 0 && hostCount > 0) {
            "Acknowledged $hostCount host${if (hostCount != 1) "s" else ""} and $addedServiceCount related service${if (addedServiceCount != 1) "s" else ""}"
        } else {
            "Acknowledged $totalFresh problem${if (totalFresh != 1) "s" else ""}"
        }
    }

    private fun recheckMsg(count: Int) = "Recheck submitted for $count problem${if (count != 1) "s" else ""}"

    private fun sanitizeError(msg: String?): String =
        (msg ?: "Unknown error")
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .take(200)

    companion object {
        private const val LOCAL_ACK_TTL_MS = 5 * 60 * 1_000L
        private const val COMMAND_COOLDOWN_MS = 5_000L
        // U+001F (unit separator): cannot appear in UUIDs or Nagios host/service names,
        // preventing false prefix matches in key lookups.
        private const val SEP = ""
    }
}
