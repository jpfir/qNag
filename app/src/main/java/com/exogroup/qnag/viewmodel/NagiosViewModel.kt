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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // ── Fetch deduplication ───────────────────────────────────────────────────

    private var fetchJob: Job? = null

    /**
     * @param skipIfRunning When true (auto-refresh path), the call is a no-op while a
     *   fetch is already in flight.  When false (manual refresh, post-command refresh),
     *   any in-flight fetch is cancelled and a new one starts immediately.
     */
    fun fetchAlerts(instance: NagiosInstance, skipIfRunning: Boolean = false) {
        if (skipIfRunning && fetchJob?.isActive == true) return

        fetchJob?.cancel()
        val stale = currentProblems()
        uiState = DashboardState.Loading(stale)
        fetchJob = viewModelScope.launch {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }
                reconcileLocalAck(instance.id, problems)
                uiState = DashboardState.Success(problems, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e  // Must propagate so the coroutine cleans up correctly
            } catch (e: Exception) {
                uiState = DashboardState.Error(
                    message = e.message ?: "Unknown network error",
                    previousProblems = stale,
                )
            }
        }
    }

    // ── ACK ───────────────────────────────────────────────────────────────────

    fun acknowledgeProblems(
        instance: NagiosInstance,
        problems: List<NagiosProblem>,
        settings: CommandSettings,
    ) {
        if (problems.isEmpty()) return
        commandState = CommandState.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    api.acknowledgeProblems(instance, problems, settings)
                }
                // Optimistic local ACK overlay — shown until next successful fetch confirms
                val now = System.currentTimeMillis()
                problems.forEach { problem ->
                    localAcknowledgedMap[localAckKey(instance.id, problem)] = now
                }
                val count = problems.size
                commandState = CommandState.Success(
                    "Acknowledged $count problem${if (count != 1) "s" else ""}"
                )
                // Post-command refresh is forced (not skippable)
                fetchAlerts(instance, skipIfRunning = false)
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
                withContext(Dispatchers.IO) {
                    api.recheckProblems(instance, problems, settings)
                }
                val count = problems.size
                commandState = CommandState.Success(
                    "Recheck submitted for $count problem${if (count != 1) "s" else ""}"
                )
                fetchAlerts(instance, skipIfRunning = false)
            } catch (e: Exception) {
                commandState = CommandState.Error(e.message ?: "Recheck failed")
            }
        }
    }

    // ── Local ACK overlay ─────────────────────────────────────────────────────
    //
    // An optimistic acknowledgement is shown immediately after a successful ACK
    // command and kept for up to LOCAL_ACK_TTL_MS (5 min) or until the next
    // successful fetch confirms the server state.

    private val localAcknowledgedMap = mutableStateMapOf<String, Long>()

    /** Returns true if the problem has a live local ACK overlay for the given instance. */
    fun isLocallyAcknowledged(instanceId: String, problem: NagiosProblem): Boolean {
        val ts = localAcknowledgedMap[localAckKey(instanceId, problem)] ?: return false
        return (System.currentTimeMillis() - ts) < LOCAL_ACK_TTL_MS
    }

    private fun localAckKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId${problem.uniqueId}"

    /**
     * Remove local ACK entries that are either confirmed by the server or expired.
     * Called after each successful fetch.
     */
    private fun reconcileLocalAck(instanceId: String, serverProblems: List<NagiosProblem>) {
        val now = System.currentTimeMillis()
        val serverAckedIds = serverProblems.filter { it.acknowledged }.map { it.uniqueId }.toSet()

        val toRemove = localAcknowledgedMap.keys.toList().filter { key ->
            if (!key.startsWith("$instanceId")) return@filter false
            val uniqueId = key.substringAfter("$instanceId")
            val ts = localAcknowledgedMap[key] ?: return@filter true
            // Remove if server confirms OR TTL expired
            uniqueId in serverAckedIds || (now - ts > LOCAL_ACK_TTL_MS)
        }
        toRemove.forEach { localAcknowledgedMap.remove(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun clearCommandState() {
        commandState = CommandState.Idle
    }

    private fun currentProblems(): List<NagiosProblem>? = when (val s = uiState) {
        is DashboardState.Success -> s.problems
        is DashboardState.Loading -> s.previousProblems
        is DashboardState.Error -> s.previousProblems
        else -> null
    }

    companion object {
        private const val LOCAL_ACK_TTL_MS = 5 * 60 * 1_000L  // 5 minutes
    }
}
