package com.exogroup.qnag.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exogroup.qnag.data.AlertListStyle
import com.exogroup.qnag.data.CommandActivityTracker
import com.exogroup.qnag.data.CommandJobStatus
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.InstanceSummary
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.HiddenReason
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.classifyHiddenReasons
import com.exogroup.qnag.data.tier2DelayMs
import com.exogroup.qnag.viewmodel.CommandState
import com.exogroup.qnag.viewmodel.DashboardState
import com.exogroup.qnag.viewmodel.NagiosViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Instance selection ─────────────────────────────────────────────────────────

sealed class InstanceSelection {
    /** Fetch and show problems from all enabled instances merged into one list. */
    object All : InstanceSelection()
    /** Fetch and show problems from a single instance. */
    data class Single(val instance: NagiosInstance) : InstanceSelection()
}

// ── Quick filter ───────────────────────────────────────────────────────────────

/** Transient dashboard-level filter applied on top of Settings filters. */
internal enum class QuickFilter {
    HOST_DOWN, HOST_UNREACHABLE, SERVICE_CRITICAL, SERVICE_WARNING, SERVICE_UNKNOWN;

    val displayLabel: String get() = when (this) {
        HOST_DOWN -> "Host DOWN"
        HOST_UNREACHABLE -> "Host UNREACHABLE"
        SERVICE_CRITICAL -> "Critical services"
        SERVICE_WARNING -> "Warning services"
        SERVICE_UNKNOWN -> "Unknown services"
    }
}

private fun applyInstanceAndQuickFilter(
    problems: List<NagiosProblem>,
    filter: QuickFilter?,
    instanceFilter: NagiosInstance?,
): List<NagiosProblem> {
    val byInstance = if (instanceFilter != null) problems.filter { it.instanceId == instanceFilter.id } else problems
    return applyQuickFilter(byInstance, filter)
}

private fun applyQuickFilter(problems: List<NagiosProblem>, filter: QuickFilter?): List<NagiosProblem> {
    if (filter == null) return problems
    return problems.filter { p -> when (filter) {
        QuickFilter.HOST_DOWN         -> p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_DOWN
        QuickFilter.HOST_UNREACHABLE  -> p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_UNREACHABLE
        QuickFilter.SERVICE_CRITICAL  -> p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL
        QuickFilter.SERVICE_WARNING   -> p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING
        QuickFilter.SERVICE_UNKNOWN   -> p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN
    } }
}

// ── Problem list rows (for section headers) ───────────────────────────────────

private sealed class ProblemListRow {
    data class SectionHead(val label: String, val count: Int) : ProblemListRow()
    data class Item(val problem: NagiosProblem, val hiddenReasons: List<HiddenReason> = emptyList()) : ProblemListRow()
}

private fun problemSectionLabel(p: NagiosProblem): String = when {
    p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_DOWN         -> "Host DOWN"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_CRITICAL -> "Critical services"
    p is NagiosProblem.HostProblem && p.status == NagiosStatus.HOST_UNREACHABLE   -> "Host UNREACHABLE"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_UNKNOWN  -> "Unknown services"
    p is NagiosProblem.ServiceProblem && p.status == NagiosStatus.SERVICE_WARNING  -> "Warning services"
    else -> "Other"
}

private fun buildSectionedRows(problems: List<NagiosProblem>): List<ProblemListRow> {
    val countByLabel = problems.groupingBy { problemSectionLabel(it) }.eachCount()
    val rows = mutableListOf<ProblemListRow>()
    var lastLabel: String? = null
    for (p in problems) {
        val label = problemSectionLabel(p)
        if (label != lastLabel) {
            rows += ProblemListRow.SectionHead(label, countByLabel[label] ?: 0)
            lastLabel = label
        }
        rows += ProblemListRow.Item(p)
    }
    return rows
}

private fun buildHiddenRows(
    problems: List<NagiosProblem>,
    hiddenReasonsFor: (NagiosProblem) -> List<HiddenReason>,
): List<ProblemListRow> {
    if (problems.isEmpty()) return emptyList()
    val rows = mutableListOf<ProblemListRow>(ProblemListRow.SectionHead("Hidden by filters", problems.size))
    for (p in problems) rows += ProblemListRow.Item(p, hiddenReasonsFor(p))
    return rows
}

// ── Host-level bulk action (ACK or Recheck all services on a host) ───────────

private data class HostServiceTargets(
    val resolvedInstance: NagiosInstance?,
    val hostName: String,
    val serviceTargets: List<NagiosProblem.ServiceProblem>,
)

private sealed class PendingHostAction {
    abstract val clickedProblem: NagiosProblem
    data class Ack(override val clickedProblem: NagiosProblem) : PendingHostAction()
    data class Recheck(override val clickedProblem: NagiosProblem) : PendingHostAction()
}

private fun resolveHostServiceTargets(
    clickedProblem: NagiosProblem,
    selectedInstance: InstanceSelection,
    currentInstance: NagiosInstance,
    enabledInstances: List<NagiosInstance>,
    currentProblems: List<NagiosProblem>,
): HostServiceTargets {
    val resolvedInstanceId = clickedProblem.instanceId.ifEmpty {
        (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: currentInstance.id
    }
    val resolvedInstance = when (selectedInstance) {
        is InstanceSelection.All -> enabledInstances.find { it.id == resolvedInstanceId }
        is InstanceSelection.Single -> selectedInstance.instance
    }
    val serviceTargets = currentProblems
        .filterIsInstance<NagiosProblem.ServiceProblem>()
        .filter { svc ->
            val svcInstId = svc.instanceId.ifEmpty { resolvedInstanceId }
            svcInstId == resolvedInstanceId && svc.hostName == clickedProblem.hostName
        }
    return HostServiceTargets(
        resolvedInstance = resolvedInstance,
        hostName = clickedProblem.hostName,
        serviceTargets = serviceTargets,
    )
}

// ── Dashboard ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    instance: NagiosInstance,
    allInstances: List<NagiosInstance>,
    filterSettings: FilterSettings,
    notificationSettings: NotificationSettings,
    commandSettings: CommandSettings,
    alertListStyle: AlertListStyle = AlertListStyle.CLASSIC_ROWS,
    onSwitchInstance: (NagiosInstance) -> Unit,
    onAddNewInstance: () -> Unit,
    onManageInstances: () -> Unit = onAddNewInstance,
    onOpenSettings: () -> Unit,
    isMonitoringPaused: Boolean = false,
    onResumeMonitoring: () -> Unit = {},
    onStopMonitoringAndExit: (() -> Unit)? = null,
    // Persisted dashboard scope: "ALL" or "INSTANCE:<uuid>".  Restored from AppSettings on startup.
    initialDashboardScope: String = "ALL",
    // Called whenever the user changes the scope so the caller can persist the new value.
    onScopeChanged: (String) -> Unit = {},
    // Navigate to the full-screen problem detail view.
    onOpenProblemDetail: (NagiosProblem, NagiosInstance?) -> Unit = { _, _ -> },
    // Open the Command Activity screen (from running-commands banner tap).
    onOpenCommandActivity: () -> Unit = {},
    // Persisted summary panel expanded state.
    initialSummaryExpanded: Boolean = true,
    onSummaryExpandedChanged: (Boolean) -> Unit = {},
    nagiosViewModel: NagiosViewModel = viewModel(),
) {
    val enabledInstances = remember(allInstances) { allInstances.filter { it.enabled } }

    // Restore selection from the persisted scope when the screen is first shown or when
    // the user navigates to a different instance (instance.id changes).
    // remember(instance.id) re-runs when instance changes but NOT when only initialDashboardScope
    // changes mid-session (that would reset an in-flight selection the user just made).
    var selectedInstance by remember(instance.id) {
        mutableStateOf<InstanceSelection>(
            resolveDashboardScope(initialDashboardScope, instance, enabledInstances)
        )
    }

    // Trigger an initial (or re-selection) fetch whenever selection or instance list changes.
    // The freshness guard skips the fetch when returning to the dashboard (e.g. from Settings)
    // if data was refreshed within AUTO_REFRESH_MIN_AGE_MS; it always fetches on scope change.
    val enabledInstanceIds = remember(allInstances) { enabledInstances.map { it.id } }
    LaunchedEffect(selectedInstance, enabledInstanceIds) {
        val requestedKey = when (val sel = selectedInstance) {
            is InstanceSelection.All -> "all:${enabledInstances.map { it.id }.sorted().joinToString(",")}"
            is InstanceSelection.Single -> "single:${sel.instance.id}"
        }
        if (nagiosViewModel.isDataFreshEnough(requestedKey)) return@LaunchedEffect
        when (val sel = selectedInstance) {
            is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = false)
            is InstanceSelection.Single -> nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = false)
        }
    }

    // Auto-refresh loop
    // When foreground monitoring is on, match the foreground service interval so the dashboard
    // and service stay in sync.  Otherwise use the WorkManager-style 15-minute minimum.
    val refreshMs = remember(
        commandSettings.keepMonitoringActive,
        commandSettings.foregroundPollingIntervalSeconds,
        notificationSettings.refreshIntervalMinutes,
    ) {
        if (commandSettings.keepMonitoringActive) {
            commandSettings.foregroundPollingIntervalSeconds.coerceAtLeast(30) * 1_000L
        } else {
            notificationSettings.refreshIntervalMinutes.coerceAtLeast(15) * 60_000L
        }
    }
    LaunchedEffect(selectedInstance, enabledInstanceIds, refreshMs) {
        while (true) {
            delay(refreshMs)
            when (val sel = selectedInstance) {
                is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = true)
                is InstanceSelection.Single -> if (sel.instance.enabled) nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = true)
            }
        }
    }

    var selectedIds    by remember { mutableStateOf(setOf<String>()) }
    var quickFilter    by remember { mutableStateOf<QuickFilter?>(null) }
    var instanceFilter by remember { mutableStateOf<NagiosInstance?>(null) }
    var showHidden     by remember { mutableStateOf(false) }
    val isSelectionMode = selectedIds.isNotEmpty()
    val showInstanceNames = selectedInstance is InstanceSelection.All

    // Lambda to derive the selection key — uses instanceId prefix in ALL mode to prevent
    // collision when two instances have the same host/service name.
    val problemKey: (NagiosProblem) -> String = { p ->
        if (p.instanceId.isNotEmpty()) "${p.instanceId}${p.uniqueId}" else p.uniqueId
    }

    // Lambda that checks local ACK overlay — uses problem.instanceId in ALL mode
    val isLocallyAcked: (NagiosProblem) -> Boolean = { p ->
        val instId = p.instanceId.ifEmpty {
            (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: instance.id
        }
        nagiosViewModel.isLocallyAcknowledged(instId, p)
    }

    // Lambda that checks pending-recheck state — shows "Recheck pending" chip on cards
    val isRecheckPendingFn: (NagiosProblem) -> Boolean = { p ->
        val instId = p.instanceId.ifEmpty {
            (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: instance.id
        }
        nagiosViewModel.isRecheckPending(instId, p)
    }

    // Tier 2+ waiting indicator — uses lastStateChange only (no context required for UI)
    val isTier2WaitingFn: (NagiosProblem) -> Boolean = remember(notificationSettings) {
        val enabled = notificationSettings.tier2PlusEnabled;
        { p: NagiosProblem ->
            if (!enabled || p.acknowledged) false
            else {
                val sc = p.lastStateChange
                if (sc == null) false
                else (System.currentTimeMillis() - sc) < tier2DelayMs(p, notificationSettings)
            }
        }
    }

    // Reactive visible problems (also applies local-ACK filter when hideAcknowledged=true)
    val visibleProblems by remember(filterSettings) {
        derivedStateOf {
            val raw = when (val s = nagiosViewModel.uiState) {
                is DashboardState.Success -> s.problems
                is DashboardState.Loading -> s.previousProblems ?: emptyList()
                is DashboardState.Error -> s.previousProblems ?: emptyList()
                else -> emptyList()
            }
            applyFiltersAndLocalAck(raw, filterSettings, isLocallyAcked)
        }
    }

    LaunchedEffect(selectedInstance) { selectedIds = emptySet(); quickFilter = null; instanceFilter = null; showHidden = false }
    LaunchedEffect(visibleProblems) {
        val visibleKeys = visibleProblems.map { problemKey(it) }.toSet()
        val updated = selectedIds.intersect(visibleKeys)
        if (updated != selectedIds) selectedIds = updated
    }

    // Snackbar for command feedback
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(nagiosViewModel.commandState) {
        when (val cs = nagiosViewModel.commandState) {
            is CommandState.Success -> {
                snackbarHostState.showSnackbar(cs.message, duration = SnackbarDuration.Short)
                nagiosViewModel.clearCommandState()
            }
            is CommandState.Warning -> {
                snackbarHostState.showSnackbar(cs.message, duration = SnackbarDuration.Long)
                nagiosViewModel.clearCommandState()
            }
            is CommandState.Error -> {
                snackbarHostState.showSnackbar("Error: ${cs.message}", duration = SnackbarDuration.Long)
                nagiosViewModel.clearCommandState()
            }
            else -> Unit
        }
    }

    // Helper: switch to a specific instance from the summary panel tap (mirrors InstanceSelector logic)
    val onSwitchToInstance: (NagiosInstance) -> Unit = { inst ->
        val scopeStr = "INSTANCE:${inst.id}"
        if (inst.id != instance.id) {
            onScopeChanged(scopeStr)
            onSwitchInstance(inst)
        } else {
            selectedInstance = InstanceSelection.Single(inst)
            onScopeChanged(scopeStr)
        }
    }

    // ── Summary expanded state (persisted) ────────────────────────────────────
    var summaryExpanded by remember { mutableStateOf(initialSummaryExpanded) }

    // ACK / recheck / unack / downtime helpers that route correctly in single vs ALL mode
    fun doAck(problems: List<NagiosProblem>) {
        if (commandSettings.debugCommandSubmission) {
            for (p in problems) {
                val lbl = if (p is NagiosProblem.ServiceProblem) "${p.hostName}/${p.serviceName}" else p.hostName
                android.util.Log.d("qNag", "[SWIPE_ACTION] ack problem=$lbl")
            }
        }
        when (val sel = selectedInstance) {
            is InstanceSelection.All -> nagiosViewModel.acknowledgeProblems(enabledInstances, problems, commandSettings)
            is InstanceSelection.Single -> nagiosViewModel.acknowledgeProblems(sel.instance, problems, commandSettings)
        }
    }
    fun doRecheck(problems: List<NagiosProblem>) {
        if (commandSettings.debugCommandSubmission) {
            for (p in problems) {
                val lbl = if (p is NagiosProblem.ServiceProblem) "${p.hostName}/${p.serviceName}" else p.hostName
                android.util.Log.d("qNag", "[SWIPE_ACTION] recheck problem=$lbl")
            }
        }
        when (val sel = selectedInstance) {
            is InstanceSelection.All -> nagiosViewModel.recheckProblems(enabledInstances, problems, commandSettings)
            is InstanceSelection.Single -> nagiosViewModel.recheckProblems(sel.instance, problems, commandSettings)
        }
    }
    fun doUnack(problems: List<NagiosProblem>) = when (val sel = selectedInstance) {
        is InstanceSelection.All -> nagiosViewModel.unacknowledgeProblems(enabledInstances, problems, commandSettings)
        is InstanceSelection.Single -> nagiosViewModel.unacknowledgeProblems(sel.instance, problems, commandSettings)
    }
    fun doDowntime(problems: List<NagiosProblem>, scope: com.exogroup.qnag.data.DowntimeScope, durationMs: Long, comment: String) =
        when (val sel = selectedInstance) {
            is InstanceSelection.All -> nagiosViewModel.scheduleDowntime(enabledInstances, problems, scope, durationMs, comment, commandSettings)
            is InstanceSelection.Single -> nagiosViewModel.scheduleDowntime(sel.instance, problems, scope, durationMs, comment, commandSettings)
        }

    // Pending confirmation: set to problems to unack, cleared after confirm/dismiss
    var pendingUnack by remember { mutableStateOf<List<NagiosProblem>?>(null) }

    // Pending host-level bulk action (ACK or Recheck all services on a host)
    var pendingHostAction by remember { mutableStateOf<PendingHostAction?>(null) }

    // Pending downtime: list of problems (1 for card overflow, N for multi-select)
    // pendingDowntimeInstance is set for single-problem card overflow; null for multi-select.
    var pendingDowntimeProblems  by remember { mutableStateOf<List<NagiosProblem>?>(null) }
    var pendingDowntimeInstance  by remember { mutableStateOf<NagiosInstance?>(null) }

    // Stop monitoring confirmation dialog
    var showStopDialog by remember { mutableStateOf(false) }

    pendingDowntimeProblems?.let { dtProblems ->
        DowntimeDialog(
            problems = dtProblems,
            instance = pendingDowntimeInstance,
            commandSettings = commandSettings,
            onDismiss = { pendingDowntimeProblems = null; pendingDowntimeInstance = null },
            onSchedule = { scope, durationMs, comment ->
                if (scope != null) {
                    doDowntime(dtProblems, scope, durationMs, comment)
                } else {
                    // Mixed selection: services get SERVICE_ONLY, hosts get HOST_AND_SERVICES
                    val services = dtProblems.filterIsInstance<NagiosProblem.ServiceProblem>()
                    val hosts    = dtProblems.filterIsInstance<NagiosProblem.HostProblem>()
                    if (services.isNotEmpty()) doDowntime(services, com.exogroup.qnag.data.DowntimeScope.SERVICE_ONLY, durationMs, comment)
                    if (hosts.isNotEmpty())    doDowntime(hosts,    com.exogroup.qnag.data.DowntimeScope.HOST_AND_SERVICES, durationMs, comment)
                }
                pendingDowntimeProblems = null
                pendingDowntimeInstance = null
            },
        )
    }

    pendingUnack?.let { problems ->
        AlertDialog(
            onDismissRequest = { pendingUnack = null },
            title = { Text("Remove acknowledgement") },
            text = {
                Text(
                    if (problems.size == 1) "Remove acknowledgement from this alert?"
                    else "Remove acknowledgement from ${problems.size} alerts?"
                )
            },
            confirmButton = {
                TextButton(onClick = { doUnack(problems); pendingUnack = null }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnack = null }) { Text("Cancel") }
            },
        )
    }

    pendingHostAction?.let { action ->
        val isAck = action is PendingHostAction.Ack
        val hostName = action.clickedProblem.hostName
        AlertDialog(
            onDismissRequest = { pendingHostAction = null },
            title = { Text(if (isAck) "ACK all services on host" else "Recheck all services on host") },
            text = {
                if (isAck) {
                    Text("Are you sure you want to acknowledge all problem services on host $hostName?")
                } else {
                    Text("Are you sure you want to recheck all services on host $hostName?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val currentProblems = when (val s = nagiosViewModel.uiState) {
                        is DashboardState.Success -> s.problems
                        is DashboardState.Loading -> s.previousProblems ?: emptyList()
                        is DashboardState.Error -> s.previousProblems ?: emptyList()
                        else -> emptyList()
                    }
                    val targets = resolveHostServiceTargets(
                        clickedProblem = action.clickedProblem,
                        selectedInstance = selectedInstance,
                        currentInstance = instance,
                        enabledInstances = enabledInstances,
                        currentProblems = currentProblems,
                    )
                    val resolvedInstance = targets.resolvedInstance
                    if (resolvedInstance != null) {
                        when (action) {
                            is PendingHostAction.Recheck -> {
                                if (commandSettings.debugCommandSubmission) {
                                    android.util.Log.d("qNag", "[HOST_BULK_RECHECK] instance=${resolvedInstance.id}/${resolvedInstance.name} host=${targets.hostName} targets=${targets.serviceTargets.size} services=${targets.serviceTargets.joinToString(",") { it.serviceName }}")
                                }
                                if (targets.serviceTargets.isNotEmpty()) {
                                    nagiosViewModel.recheckProblems(resolvedInstance, targets.serviceTargets, commandSettings)
                                }
                            }
                            is PendingHostAction.Ack -> {
                                val ackTargets = targets.serviceTargets.filter { !it.acknowledged && !isLocallyAcked(it) }
                                if (commandSettings.debugCommandSubmission) {
                                    val skippedAlreadyAcked = targets.serviceTargets.count { it.acknowledged }
                                    val skippedLocallyAcked = targets.serviceTargets.count { !it.acknowledged && isLocallyAcked(it) }
                                    android.util.Log.d("qNag", "[HOST_BULK_ACK] instance=${resolvedInstance.id}/${resolvedInstance.name} host=${targets.hostName} resolved=${targets.serviceTargets.size} ackTargets=${ackTargets.size} skippedAlreadyAcked=$skippedAlreadyAcked skippedLocallyAcked=$skippedLocallyAcked services=${ackTargets.joinToString(",") { it.serviceName }}")
                                }
                                if (ackTargets.isNotEmpty()) {
                                    nagiosViewModel.acknowledgeProblems(resolvedInstance, ackTargets, commandSettings)
                                }
                            }
                        }
                    }
                    pendingHostAction = null
                }) {
                    Text(if (isAck) "ACK all services" else "Recheck all services")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHostAction = null }) { Text("Cancel") }
            },
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop qNag monitoring?") },
            text = {
                Text(
                    "This will stop the foreground service, auto refresh, boot startup, and watchdog " +
                    "until you resume monitoring.\n\nYou can resume next time you open qNag."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    onStopMonitoringAndExit?.invoke()
                }) { Text("Stop and exit") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text("${selectedIds.size} Selected")
                    } else {
                        InstanceSelector(
                            selected = selectedInstance,
                            enabledInstances = enabledInstances,
                            onSelect = { sel ->
                                when (sel) {
                                    is InstanceSelection.All -> {
                                        selectedInstance = sel
                                        onScopeChanged("ALL")
                                    }
                                    is InstanceSelection.Single -> {
                                        val scopeStr = "INSTANCE:${sel.instance.id}"
                                        if (sel.instance.id != instance.id) {
                                            // Switching to a different instance: persist scope then
                                            // navigate so MainActivity's fromInstance stays in sync.
                                            onScopeChanged(scopeStr)
                                            onSwitchInstance(sel.instance)
                                        } else {
                                            selectedInstance = sel
                                            onScopeChanged(scopeStr)
                                        }
                                    }
                                }
                            },
                        )
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val selectedProblems = visibleProblems.filter { selectedIds.contains(problemKey(it)) }
                        IconButton(onClick = { doAck(selectedProblems); selectedIds = emptySet() }) {
                            Icon(Icons.Default.Check, "Acknowledge selected")
                        }
                        IconButton(onClick = { doRecheck(selectedProblems); selectedIds = emptySet() }) {
                            Icon(Icons.Default.Refresh, "Recheck selected")
                        }
                        // Multi-select overflow menu
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                        var multiMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { multiMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "More actions")
                            }
                            DropdownMenu(expanded = multiMenuExpanded, onDismissRequest = { multiMenuExpanded = false }) {
                                // Details — only when exactly one problem is selected
                                if (selectedProblems.size == 1) {
                                    DropdownMenuItem(
                                        text = { Text("Details") },
                                        onClick = {
                                            multiMenuExpanded = false
                                            selectedIds = emptySet()
                                            selectedProblems.firstOrNull()?.let { p ->
                                                val instId = p.instanceId.ifEmpty {
                                                    (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: instance.id
                                                }
                                                val inst = enabledInstances.find { it.id == instId }
                                                onOpenProblemDetail(p, inst)
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Acknowledge selected") },
                                    onClick = { multiMenuExpanded = false; doAck(selectedProblems); selectedIds = emptySet() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Recheck selected") },
                                    onClick = { multiMenuExpanded = false; doRecheck(selectedProblems); selectedIds = emptySet() },
                                )
                                val anySelectedAcked = selectedProblems.any { it.acknowledged || isLocallyAcked(it) }
                                DropdownMenuItem(
                                    text = { Text("Remove ACK") },
                                    onClick = {
                                        multiMenuExpanded = false
                                        val toUnack = selectedProblems.filter { it.acknowledged || isLocallyAcked(it) }
                                        if (toUnack.isNotEmpty()) pendingUnack = toUnack
                                        selectedIds = emptySet()
                                    },
                                    enabled = anySelectedAcked,
                                )
                                DropdownMenuItem(
                                    text = { Text("Schedule downtime selected") },
                                    onClick = {
                                        multiMenuExpanded = false
                                        pendingDowntimeProblems = selectedProblems
                                        pendingDowntimeInstance = null
                                        selectedIds = emptySet()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy summaries") },
                                    onClick = {
                                        multiMenuExpanded = false
                                        val text = selectedProblems.joinToString("\n\n") { buildAlertSummary(it) }
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                        selectedIds = emptySet()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share summaries") },
                                    onClick = {
                                        multiMenuExpanded = false
                                        val text = selectedProblems.joinToString("\n\n") { buildAlertSummary(it) }
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share alerts"))
                                        selectedIds = emptySet()
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Clear selection") },
                                    onClick = { multiMenuExpanded = false; selectedIds = emptySet() },
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = {
                            when (val sel = selectedInstance) {
                                is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = false)
                                is InstanceSelection.Single -> nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = false)
                            }
                        }) { Icon(Icons.Default.Refresh, "Refresh") }
                        var normalMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { normalMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, "More")
                            }
                            DropdownMenu(
                                expanded = normalMenuExpanded,
                                onDismissRequest = { normalMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Manage instances") },
                                    onClick = { normalMenuExpanded = false; onManageInstances() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { normalMenuExpanded = false; onOpenSettings() },
                                )
                                if (onStopMonitoringAndExit != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Stop monitoring and exit") },
                                        onClick = { normalMenuExpanded = false; showStopDialog = true },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { paddingValues ->
        val activityJobs by CommandActivityTracker.jobs.collectAsState()
        val runningJobCount = activityJobs.count { it.status == CommandJobStatus.RUNNING }

        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Running commands banner — tapping opens Command Activity
            if (runningJobCount > 0) {
                Surface(
                    onClick = onOpenCommandActivity,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "$runningJobCount command${if (runningJobCount != 1) "s" else ""} running",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "View activity",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (isMonitoringPaused) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Monitoring paused",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Auto refresh and boot startup are disabled until you resume.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = onResumeMonitoring) { Text("Resume monitoring") }
                        }
                    }
                }
            }

        DashboardContent(
            state = nagiosViewModel.uiState,
            filterSettings = filterSettings,
            alertListStyle = alertListStyle,
            selectedIds = selectedIds,
            isSelectionMode = isSelectionMode,
            showInstanceNames = showInstanceNames,
            isAllMode = selectedInstance is InstanceSelection.All,
            enabledInstances = enabledInstances,
            selectedInstance = selectedInstance,
            currentInstance = instance,
            onSelectInstance = onSwitchToInstance,
            isLocallyAcknowledged = isLocallyAcked,
            isRecheckPending = isRecheckPendingFn,
            problemKey = problemKey,
            onOpenProblemDetail = { problem ->
                val instId = problem.instanceId.ifEmpty {
                    (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: instance.id
                }
                val inst = enabledInstances.find { it.id == instId }
                onOpenProblemDetail(problem, inst)
            },
            onToggleSelect = { key -> selectedIds = if (selectedIds.contains(key)) selectedIds - key else selectedIds + key },
            onLongPress = { key -> selectedIds = selectedIds + key },
            onAckProblem = { problem -> doAck(listOf(problem)) },
            onRecheckProblem = { problem -> doRecheck(listOf(problem)) },
            onUnackProblem = { problem -> pendingUnack = listOf(problem) },
            onScheduleDowntimeProblem = { problem ->
                val instId = problem.instanceId.ifEmpty {
                    (selectedInstance as? InstanceSelection.Single)?.instance?.id ?: instance.id
                }
                pendingDowntimeProblems = listOf(problem)
                pendingDowntimeInstance = enabledInstances.find { it.id == instId }
            },
            onAckAllServicesOnHost = { clickedProblem ->
                pendingHostAction = PendingHostAction.Ack(clickedProblem)
            },
            onRecheckAllServicesOnHost = { clickedProblem ->
                pendingHostAction = PendingHostAction.Recheck(clickedProblem)
            },
            summaryExpanded = summaryExpanded,
            onSummaryExpandedChanged = { expanded ->
                summaryExpanded = expanded
                onSummaryExpandedChanged(expanded)
            },
            quickFilter = quickFilter,
            onQuickFilterChanged = { filter -> quickFilter = filter; instanceFilter = null },
            instanceFilter = instanceFilter,
            onInstanceChipSelected = { inst, filter -> instanceFilter = inst; quickFilter = filter },
            tier2PlusActive = notificationSettings.tier2PlusEnabled && notificationSettings.notificationsEnabled,
            tier2PlusLabel = if (notificationSettings.tier2PlusUsePerStateDelays) "per-state delays"
                             else "notify after ${notificationSettings.tier2PlusDelayMinutes}m",
            isTier2Waiting = isTier2WaitingFn,
            showHidden = showHidden,
            onShowHiddenChanged = { showHidden = it },
            onRetry = {
                when (val sel = selectedInstance) {
                    is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = false)
                    is InstanceSelection.Single -> nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = false)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
        } // end Column
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    filterSettings: FilterSettings,
    alertListStyle: AlertListStyle = AlertListStyle.CLASSIC_ROWS,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    showInstanceNames: Boolean,
    isAllMode: Boolean,
    enabledInstances: List<NagiosInstance>,
    selectedInstance: InstanceSelection,
    currentInstance: NagiosInstance,
    onSelectInstance: (NagiosInstance) -> Unit,
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    isRecheckPending: (NagiosProblem) -> Boolean = { false },
    problemKey: (NagiosProblem) -> String,
    onOpenProblemDetail: (NagiosProblem) -> Unit = {},
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAckProblem: (NagiosProblem) -> Unit,
    onRecheckProblem: (NagiosProblem) -> Unit,
    onUnackProblem: (NagiosProblem) -> Unit,
    onScheduleDowntimeProblem: (NagiosProblem) -> Unit = {},
    onAckAllServicesOnHost: (NagiosProblem) -> Unit = {},
    onRecheckAllServicesOnHost: (NagiosProblem) -> Unit = {},
    summaryExpanded: Boolean = true,
    onSummaryExpandedChanged: (Boolean) -> Unit = {},
    quickFilter: QuickFilter? = null,
    onQuickFilterChanged: (QuickFilter?) -> Unit = {},
    instanceFilter: NagiosInstance? = null,
    onInstanceChipSelected: ((NagiosInstance, QuickFilter) -> Unit)? = null,
    tier2PlusActive: Boolean = false,
    tier2PlusLabel: String = "",
    isTier2Waiting: (NagiosProblem) -> Boolean = { false },
    showHidden: Boolean = false,
    onShowHiddenChanged: (Boolean) -> Unit = {},
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summaries = when (state) {
        is DashboardState.Success -> state.instanceSummaries
        is DashboardState.Loading -> state.previousSummaries
        is DashboardState.Error -> state.previousSummaries
        else -> emptyList()
    }

    // Stale-or-fresh problem list — single derivation used everywhere below
    val displayProblems = when (state) {
        is DashboardState.Success -> state.problems
        is DashboardState.Loading -> state.previousProblems ?: emptyList()
        is DashboardState.Error -> state.previousProblems ?: emptyList()
        else -> emptyList()
    }
    val partialErrors = (state as? DashboardState.Success)?.partialErrors ?: emptyList()
    val isRefreshing = state is DashboardState.Loading
    // hasStaleData: fetch in progress AND we already have a previous list to show
    val hasStaleData = isRefreshing && (state as? DashboardState.Loading)?.previousProblems != null
    val lastUpdated = when (state) {
        is DashboardState.Success -> state.lastUpdated
        is DashboardState.Loading -> state.previousLastUpdated
        is DashboardState.Error -> state.previousLastUpdated
        else -> null
    }
    val isStale = state !is DashboardState.Success
    // Scope first (instance chip + quick severity chip), then saved filters.
    // This ensures hidden-problems detection is bounded to the current UI scope:
    //   scopedRaw = all problems inside the active instance/severity scope
    //   visible    = scopedRaw after saved filters are applied
    //   hidden     = scopedRaw − visible  (only problems hidden by saved filters inside the scope)
    val scopedRaw = applyInstanceAndQuickFilter(displayProblems, quickFilter, instanceFilter)
    val visible = applyFiltersAndLocalAck(scopedRaw, filterSettings, isLocallyAcknowledged)
    val visibleSet = visible.toHashSet()
    val hiddenByFilters = scopedRaw.filterNot { it in visibleSet }
    val hiddenCount = hiddenByFilters.size
    val hiddenToShow = if (showHidden) hiddenByFilters else emptyList()
    val hiddenReasonsFor: (NagiosProblem) -> List<HiddenReason> = { problem ->
        classifyHiddenReasons(problem, filterSettings, isLocallyAcknowledged(problem))
    }
    val onToggleShowHidden: (() -> Unit)? = if (hiddenCount > 0) { { onShowHiddenChanged(!showHidden) } } else null
    // Hoisted so it survives Success/Loading/Error transitions without resetting scroll position
    val listState = rememberLazyListState()

    Column(modifier = modifier) {
        if (summaries.isNotEmpty()) {
            if (alertListStyle == AlertListStyle.CLASSIC_ROWS) {
                ClassicInstancesSummary(
                    summaries = summaries,
                    enabledInstances = enabledInstances,
                    onSelectInstance = onSelectInstance,
                )
            } else {
                InstanceSummaryPanel(
                    summaries = summaries,
                    isAllMode = isAllMode,
                    enabledInstances = enabledInstances,
                    onSelectInstance = onSelectInstance,
                    expanded = summaryExpanded,
                    onExpandedChanged = onSummaryExpandedChanged,
                    quickFilter = quickFilter,
                    onQuickFilterChanged = onQuickFilterChanged,
                    onInstanceChipSelected = onInstanceChipSelected,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (quickFilter != null) {
                QuickFilterBanner(
                    filter = quickFilter,
                    instanceName = instanceFilter?.name,
                    onClear = { onQuickFilterChanged(null) },
                )
            }
            if (tier2PlusActive) {
                Tier2PlusBanner(label = tier2PlusLabel)
            }
        }
        // Error banner — shown above the stale list so the list remains visible
        if (state is DashboardState.Error) {
            ErrorBanner(message = state.message, onRetry = onRetry)
            Spacer(Modifier.height(4.dp))
        }
        // Partial ALL-mode failures
        if (partialErrors.isNotEmpty()) {
            PartialErrorBanner(partialErrors)
            Spacer(Modifier.height(4.dp))
        }
        // Lightweight refresh indicator — replaces full Loading branch when stale data exists
        if (hasStaleData) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        // Single stable content block — ProblemList is always at the same composition position
        // so LazyListState (and scroll offset) survives Success↔Loading↔Error transitions.
        when {
            isRefreshing && !hasStaleData ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state is DashboardState.Idle -> Unit
            displayProblems.isEmpty() && state is DashboardState.Success && partialErrors.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("All green! No active problems.", color = MaterialTheme.colorScheme.primary)
                }
            displayProblems.isEmpty() && state is DashboardState.Success ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No problems from reachable instances.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            displayProblems.isEmpty() -> Unit  // Error with no stale — error banner above is sufficient
            scopedRaw.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No problems in current scope.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            visible.isEmpty() && hiddenToShow.isEmpty() ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (alertListStyle == AlertListStyle.CLASSIC_ROWS) {
                        ClassicProblemsHeader(
                            visibleCount = 0,
                            hiddenCount = hiddenCount,
                            showHidden = showHidden,
                            onToggleShowHidden = onToggleShowHidden,
                        )
                    } else {
                        SummaryRow(
                            visibleCount = 0,
                            totalCount = scopedRaw.size,
                            lastUpdated = lastUpdated,
                            stale = isStale,
                            hiddenCount = hiddenCount,
                            showHidden = showHidden,
                            onToggleShowHidden = onToggleShowHidden,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No visible problems.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else -> ProblemList(
                problems = visible,
                hiddenProblems = hiddenToShow,
                hiddenReasonsFor = hiddenReasonsFor,
                rawProblems = displayProblems,
                alertListStyle = alertListStyle,
                listState = listState,
                selectedInstance = selectedInstance,
                currentInstance = currentInstance,
                enabledInstances = enabledInstances,
                selectedIds = selectedIds,
                isSelectionMode = isSelectionMode,
                showInstanceNames = showInstanceNames,
                isLocallyAcknowledged = isLocallyAcknowledged,
                isRecheckPending = isRecheckPending,
                problemKey = problemKey,
                onOpenProblemDetail = onOpenProblemDetail,
                onToggleSelect = onToggleSelect,
                onLongPress = onLongPress,
                onAckProblem = onAckProblem,
                onRecheckProblem = onRecheckProblem,
                onUnackProblem = onUnackProblem,
                onScheduleDowntimeProblem = onScheduleDowntimeProblem,
                onAckAllServicesOnHost = onAckAllServicesOnHost,
                onRecheckAllServicesOnHost = onRecheckAllServicesOnHost,
                isTier2Waiting = isTier2Waiting,
                header = {
                    if (alertListStyle == AlertListStyle.CLASSIC_ROWS) {
                        ClassicProblemsHeader(
                            visibleCount = visible.size,
                            hiddenCount = hiddenCount,
                            showHidden = showHidden,
                            onToggleShowHidden = onToggleShowHidden,
                        )
                    } else {
                        SummaryRow(
                            visibleCount = visible.size,
                            totalCount = scopedRaw.size,
                            lastUpdated = lastUpdated,
                            stale = isStale,
                            hiddenCount = hiddenCount,
                            showHidden = showHidden,
                            onToggleShowHidden = onToggleShowHidden,
                        )
                    }
                },
                isRefreshing = isRefreshing,
                onRefresh = onRetry,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProblemList(
    problems: List<NagiosProblem>,
    rawProblems: List<NagiosProblem>,
    alertListStyle: AlertListStyle = AlertListStyle.CLASSIC_ROWS,
    listState: LazyListState,
    selectedInstance: InstanceSelection,
    currentInstance: NagiosInstance,
    enabledInstances: List<NagiosInstance>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    showInstanceNames: Boolean,
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    isRecheckPending: (NagiosProblem) -> Boolean = { false },
    problemKey: (NagiosProblem) -> String,
    onOpenProblemDetail: (NagiosProblem) -> Unit = {},
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAckProblem: (NagiosProblem) -> Unit,
    onRecheckProblem: (NagiosProblem) -> Unit,
    onUnackProblem: (NagiosProblem) -> Unit = {},
    onScheduleDowntimeProblem: (NagiosProblem) -> Unit = {},
    onAckAllServicesOnHost: (NagiosProblem) -> Unit = {},
    onRecheckAllServicesOnHost: (NagiosProblem) -> Unit = {},
    isTier2Waiting: (NagiosProblem) -> Boolean = { false },
    hiddenProblems: List<NagiosProblem> = emptyList(),
    hiddenReasonsFor: (NagiosProblem) -> List<HiddenReason> = { emptyList() },
    header: @Composable () -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var swipeAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            swipeAllowed = false
        } else {
            delay(250L)
            swipeAllowed = true
        }
    }

    // Count active service problems per host+instance — used to show an impact hint on HOST DOWN cards.
    // Computed once per rawProblems refresh (O(n)); not re-computed per card.
    val relatedServiceCounts = remember(rawProblems) {
        rawProblems.filterIsInstance<NagiosProblem.ServiceProblem>()
            .groupBy { "${it.instanceId}|${it.hostName}" }
            .mapValues { it.value.size }
    }

    val rows = remember(problems, hiddenProblems) {
        buildSectionedRows(problems) + buildHiddenRows(hiddenProblems, hiddenReasonsFor)
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { header() }
            items(rows, key = { row -> when (row) {
                is ProblemListRow.SectionHead -> "section_${row.label}"
                is ProblemListRow.Item -> if (row.hiddenReasons.isEmpty()) problemKey(row.problem) else "hidden_${problemKey(row.problem)}"
            }}) { row ->
                when (row) {
                    is ProblemListRow.SectionHead -> ProblemSectionHeader(row.label, row.count)
                    is ProblemListRow.Item -> {
                        val problem = row.problem
                        val key = problemKey(problem)
                        val locallyAcked = isLocallyAcknowledged(problem)
                        val hostTargets = resolveHostServiceTargets(problem, selectedInstance, currentInstance, enabledInstances, rawProblems)
                        val hasAckTargets = hostTargets.serviceTargets.any { !it.acknowledged && !isLocallyAcknowledged(it) }
                        val relatedCount = if (problem is NagiosProblem.HostProblem && problem.status == NagiosStatus.HOST_DOWN) {
                            relatedServiceCounts["${problem.instanceId}|${problem.hostName}"] ?: 0
                        } else 0
                        val onCopyOutputFn: () -> Unit = {
                            clipboardManager.setText(
                                androidx.compose.ui.text.AnnotatedString(problem.pluginOutput)
                            )
                        }
                        val onShareFn: () -> Unit = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, buildAlertSummary(problem))
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share alert"))
                        }
                        when (alertListStyle) {
                            AlertListStyle.CLASSIC_ROWS -> ProblemClassicRow(
                                problem = problem,
                                isSelected = selectedIds.contains(key),
                                isSelectionMode = isSelectionMode,
                                isAcknowledged = problem.acknowledged || locallyAcked,
                                isPendingAck = locallyAcked && !problem.acknowledged,
                                instanceName = if (showInstanceNames) problem.instanceName else "",
                                isRecheckPending = isRecheckPending(problem),
                                hiddenReasons = row.hiddenReasons,
                                relatedServiceCount = relatedCount,
                                isTier2Waiting = isTier2Waiting(problem),
                                swipeAllowed = swipeAllowed && !isSelectionMode,
                                onOpenDetail = { onOpenProblemDetail(problem) },
                                onCopyOutput = onCopyOutputFn,
                                onShare = onShareFn,
                                onUnack = if (problem.acknowledged || locallyAcked) { { onUnackProblem(problem) } } else null,
                                onScheduleDowntime = { onScheduleDowntimeProblem(problem) },
                                onAckAllServicesOnHost = if (hasAckTargets) { { onAckAllServicesOnHost(problem) } } else null,
                                onRecheckAllServicesOnHost = if (hostTargets.serviceTargets.isNotEmpty()) { { onRecheckAllServicesOnHost(problem) } } else null,
                                onToggleSelect = { onToggleSelect(key) },
                                onLongPress = { onLongPress(key) },
                                onAck = { onAckProblem(problem) },
                                onRecheck = { onRecheckProblem(problem) },
                            )
                            else -> ProblemCard(
                                problem = problem,
                                isSelected = selectedIds.contains(key),
                                isSelectionMode = isSelectionMode,
                                isAcknowledged = problem.acknowledged || locallyAcked,
                                isPendingAck = locallyAcked && !problem.acknowledged,
                                instanceName = if (showInstanceNames) problem.instanceName else "",
                                isRecheckPending = isRecheckPending(problem),
                                hiddenReasons = row.hiddenReasons,
                                relatedServiceCount = relatedCount,
                                initialExpanded = alertListStyle == AlertListStyle.DETAILED_CARDS,
                                swipeAllowed = swipeAllowed && !isSelectionMode,
                                onOpenDetail = { onOpenProblemDetail(problem) },
                                onCopyOutput = onCopyOutputFn,
                                onShare = onShareFn,
                                onUnack = if (problem.acknowledged || locallyAcked) { { onUnackProblem(problem) } } else null,
                                onScheduleDowntime = { onScheduleDowntimeProblem(problem) },
                                onAckAllServicesOnHost = if (hasAckTargets) { { onAckAllServicesOnHost(problem) } } else null,
                                onRecheckAllServicesOnHost = if (hostTargets.serviceTargets.isNotEmpty()) { { onRecheckAllServicesOnHost(problem) } } else null,
                                isTier2Waiting = isTier2Waiting(problem),
                                onToggleSelect = { onToggleSelect(key) },
                                onLongPress = { onLongPress(key) },
                                onAck = { onAckProblem(problem) },
                                onRecheck = { onRecheckProblem(problem) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Instance selector ─────────────────────────────────────────────────────────

@Composable
private fun InstanceSelector(
    selected: InstanceSelection,
    enabledInstances: List<NagiosInstance>,
    onSelect: (InstanceSelection) -> Unit,
) {
    if (enabledInstances.size <= 1) {
        Text(enabledInstances.firstOrNull()?.name ?: "qNag", maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            val label = when (selected) {
                is InstanceSelection.All -> "ALL (${enabledInstances.size})"
                is InstanceSelection.Single -> selected.instance.name
            }
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("ALL — ${enabledInstances.size} instances") },
                onClick = { onSelect(InstanceSelection.All); expanded = false },
                leadingIcon = if (selected is InstanceSelection.All) {
                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
            HorizontalDivider()
            enabledInstances.forEach { inst ->
                DropdownMenuItem(
                    text = { Text(inst.name) },
                    onClick = { onSelect(InstanceSelection.Single(inst)); expanded = false },
                    leadingIcon = if ((selected as? InstanceSelection.Single)?.instance?.id == inst.id) {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
    }
}

// ── Summary row ───────────────────────────────────────────────────────────────

@Composable
private fun SummaryRow(
    visibleCount: Int,
    totalCount: Int,
    lastUpdated: Long?,
    stale: Boolean,
    hiddenCount: Int = 0,
    showHidden: Boolean = false,
    onToggleShowHidden: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val countText = if (visibleCount == totalCount) "$totalCount problem${if (totalCount != 1) "s" else ""}"
            else "$visibleCount visible / $totalCount total"
            Text(countText, style = MaterialTheme.typography.labelMedium)
            val timeText = when {
                lastUpdated != null -> "Updated: ${formatTime(lastUpdated)}"
                stale -> "Stale data"
                else -> ""
            }
            if (timeText.isNotEmpty()) Text(timeText, style = MaterialTheme.typography.labelSmall)
        }
        if (hiddenCount > 0 && onToggleShowHidden != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$hiddenCount hidden by filters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onToggleShowHidden,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (showHidden) "Hide filtered" else "Show hidden",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            "Severity first · newest within state",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
    }
}

// ── Tier 2+ active banner ─────────────────────────────────────────────────────

@Composable
private fun Tier2PlusBanner(label: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tier 2+ active · $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Quick filter banner ───────────────────────────────────────────────────────

@Composable
private fun QuickFilterBanner(filter: QuickFilter, instanceName: String? = null, onClear: () -> Unit) {
    val label = if (instanceName != null) "$instanceName · ${filter.displayLabel}" else filter.displayLabel
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Showing: $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text("Clear", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Section header inside problem list ────────────────────────────────────────

@Composable
private fun ProblemSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Non-fatal banner shown when some (but not all) instances failed in ALL mode. */
@Composable
private fun PartialErrorBanner(errors: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Failed to refresh ${errors.size} instance${if (errors.size != 1) "s" else ""}:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            errors.forEach { msg ->
                Text(
                    "• $msg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Converts a persisted scope string to an [InstanceSelection].
 *
 * Fallback rules (applied when the requested scope is unavailable):
 *  - "ALL" with exactly 1 enabled instance → Single(that instance)
 *  - "INSTANCE:id" where the instance is missing/disabled:
 *      · multiple enabled instances → All
 *      · exactly 1 → Single(that instance)
 *  - Unrecognised string → Single(fallbackInstance)
 */
private fun resolveDashboardScope(
    scope: String,
    fallbackInstance: NagiosInstance,
    enabledInstances: List<NagiosInstance>,
): InstanceSelection {
    val moreThanOne = enabledInstances.size > 1
    return when {
        scope == "ALL" ->
            if (moreThanOne) InstanceSelection.All
            else InstanceSelection.Single(enabledInstances.firstOrNull() ?: fallbackInstance)

        scope.startsWith("INSTANCE:") -> {
            val id = scope.removePrefix("INSTANCE:")
            val inst = enabledInstances.find { it.id == id }
            when {
                inst != null -> InstanceSelection.Single(inst)
                moreThanOne -> InstanceSelection.All
                else -> InstanceSelection.Single(enabledInstances.firstOrNull() ?: fallbackInstance)
            }
        }

        else -> InstanceSelection.Single(fallbackInstance)
    }
}

private fun applyFiltersAndLocalAck(
    problems: List<NagiosProblem>,
    filters: FilterSettings,
    isLocallyAcked: (NagiosProblem) -> Boolean,
): List<NagiosProblem> {
    val filtered = applyFilters(problems, filters)
    return if (filters.hideAcknowledgedHostsAndServices) filtered.filter { !isLocallyAcked(it) }
    else filtered
}

// ── Classic mode: aNag-style INSTANCES section ────────────────────────────────

@Composable
private fun ClassicInstancesSummary(
    summaries: List<InstanceSummary>,
    enabledInstances: List<NagiosInstance>,
    onSelectInstance: (NagiosInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            "INSTANCES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        HorizontalDivider()
        summaries.forEach { summary ->
            val inst = enabledInstances.find { it.id == summary.instanceId }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (inst != null) Modifier.clickable { onSelectInstance(inst) } else Modifier)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    summary.instanceName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val timeStr = summary.lastUpdated?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                } ?: "N/A"
                Text(
                    "Last update: $timeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Host: D:${summary.hostDown}  U:${summary.hostUnreachable}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Service: C:${summary.serviceCritical}  W:${summary.serviceWarning}  N:${summary.serviceUnknown}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.fetchError != null) {
                    Text(
                        "⚠ Fetch failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ── Classic mode: PROBLEMS section header ─────────────────────────────────────

@Composable
private fun ClassicProblemsHeader(
    visibleCount: Int,
    hiddenCount: Int = 0,
    showHidden: Boolean = false,
    onToggleShowHidden: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PROBLEMS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (visibleCount > 0) {
                Text(
                    "$visibleCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        if (hiddenCount > 0 && onToggleShowHidden != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$hiddenCount hidden by filters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onToggleShowHidden,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (showHidden) "Hide filtered" else "Show hidden",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
