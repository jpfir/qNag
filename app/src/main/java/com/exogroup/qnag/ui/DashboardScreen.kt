package com.exogroup.qnag.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.FilterSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.data.applyFilters
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

// ── Dashboard ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    instance: NagiosInstance,
    allInstances: List<NagiosInstance>,
    filterSettings: FilterSettings,
    notificationSettings: NotificationSettings,
    commandSettings: CommandSettings,
    onSwitchInstance: (NagiosInstance) -> Unit,
    onAddNewInstance: () -> Unit,
    onOpenSettings: () -> Unit,
    // Persisted dashboard scope: "ALL" or "INSTANCE:<uuid>".  Restored from AppSettings on startup.
    initialDashboardScope: String = "ALL",
    // Called whenever the user changes the scope so the caller can persist the new value.
    onScopeChanged: (String) -> Unit = {},
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

    // Trigger an initial (or re-selection) fetch whenever selection or instance list changes
    val enabledInstanceIds = remember(allInstances) { enabledInstances.map { it.id } }
    LaunchedEffect(selectedInstance, enabledInstanceIds) {
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

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
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

    LaunchedEffect(selectedInstance) { selectedIds = emptySet() }
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

    // ACK / recheck helpers that route correctly in single vs ALL mode
    fun doAck(problems: List<NagiosProblem>) = when (val sel = selectedInstance) {
        is InstanceSelection.All -> nagiosViewModel.acknowledgeProblems(enabledInstances, problems, commandSettings)
        is InstanceSelection.Single -> nagiosViewModel.acknowledgeProblems(sel.instance, problems, commandSettings)
    }
    fun doRecheck(problems: List<NagiosProblem>) = when (val sel = selectedInstance) {
        is InstanceSelection.All -> nagiosViewModel.recheckProblems(enabledInstances, problems, commandSettings)
        is InstanceSelection.Single -> nagiosViewModel.recheckProblems(sel.instance, problems, commandSettings)
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
                    } else {
                        IconButton(onClick = onAddNewInstance) { Icon(Icons.Default.Add, "Add instance") }
                        IconButton(onClick = {
                            when (val sel = selectedInstance) {
                                is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = false)
                                is InstanceSelection.Single -> nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = false)
                            }
                        }) { Icon(Icons.Default.Refresh, "Refresh") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { paddingValues ->
        DashboardContent(
            state = nagiosViewModel.uiState,
            filterSettings = filterSettings,
            selectedIds = selectedIds,
            isSelectionMode = isSelectionMode,
            showInstanceNames = showInstanceNames,
            isAllMode = selectedInstance is InstanceSelection.All,
            enabledInstances = enabledInstances,
            onSelectInstance = onSwitchToInstance,
            isLocallyAcknowledged = isLocallyAcked,
            problemKey = problemKey,
            onToggleSelect = { key -> selectedIds = if (selectedIds.contains(key)) selectedIds - key else selectedIds + key },
            onLongPress = { key -> selectedIds = selectedIds + key },
            onAck = { key ->
                visibleProblems.firstOrNull { problemKey(it) == key }?.let { doAck(listOf(it)) }
            },
            onRecheck = { key ->
                visibleProblems.firstOrNull { problemKey(it) == key }?.let { doRecheck(listOf(it)) }
            },
            onRetry = {
                when (val sel = selectedInstance) {
                    is InstanceSelection.All -> nagiosViewModel.fetchAlertsForAll(enabledInstances, skipIfRunning = false)
                    is InstanceSelection.Single -> nagiosViewModel.fetchAlerts(sel.instance, skipIfRunning = false)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    filterSettings: FilterSettings,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    showInstanceNames: Boolean,
    isAllMode: Boolean,
    enabledInstances: List<NagiosInstance>,
    onSelectInstance: (NagiosInstance) -> Unit,
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    problemKey: (NagiosProblem) -> String,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAck: (String) -> Unit,
    onRecheck: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Extract stale or fresh summaries from whichever state is active
    val summaries = when (state) {
        is DashboardState.Success -> state.instanceSummaries
        is DashboardState.Loading -> state.previousSummaries
        is DashboardState.Error -> state.previousSummaries
        else -> emptyList()
    }

    Column(modifier = modifier) {
        if (summaries.isNotEmpty()) {
            InstanceSummaryPanel(
                summaries = summaries,
                isAllMode = isAllMode,
                enabledInstances = enabledInstances,
                onSelectInstance = onSelectInstance,
            )
            Spacer(Modifier.height(4.dp))
        }
        when (state) {
            is DashboardState.Error -> {
                ErrorBanner(message = state.message, onRetry = onRetry)
                val stale = state.previousProblems
                if (!stale.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val visible = applyFiltersAndLocalAck(stale, filterSettings, isLocallyAcknowledged)
                    ProblemList(problems = visible, selectedIds = selectedIds, isSelectionMode = isSelectionMode,
                        showInstanceNames = showInstanceNames, isLocallyAcknowledged = isLocallyAcknowledged,
                        problemKey = problemKey, onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                        onAck = onAck, onRecheck = onRecheck,
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) },
                        isRefreshing = state is DashboardState.Loading, onRefresh = onRetry)
                }
            }

            is DashboardState.Loading -> {
                val stale = state.previousProblems
                if (stale != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    val visible = applyFiltersAndLocalAck(stale, filterSettings, isLocallyAcknowledged)
                    ProblemList(problems = visible, selectedIds = selectedIds, isSelectionMode = isSelectionMode,
                        showInstanceNames = showInstanceNames, isLocallyAcknowledged = isLocallyAcknowledged,
                        problemKey = problemKey, onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                        onAck = onAck, onRecheck = onRecheck,
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) },
                        isRefreshing = state is DashboardState.Loading, onRefresh = onRetry)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            is DashboardState.Success -> {
                // Warning banner for partial ALL-mode failures (some instances reachable, some not)
                if (state.partialErrors.isNotEmpty()) {
                    PartialErrorBanner(state.partialErrors)
                    Spacer(Modifier.height(4.dp))
                }
                val visible = applyFiltersAndLocalAck(state.problems, filterSettings, isLocallyAcknowledged)
                when {
                    state.problems.isEmpty() && state.partialErrors.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("All green! No active problems.", color = MaterialTheme.colorScheme.primary)
                        }
                    state.problems.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No problems from reachable instances.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    visible.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        SummaryRow(visibleCount = 0, totalCount = state.problems.size, lastUpdated = state.lastUpdated, stale = false)
                        Spacer(Modifier.height(16.dp))
                        Text("No visible problems.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Some problems may be hidden by filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> ProblemList(problems = visible, selectedIds = selectedIds, isSelectionMode = isSelectionMode,
                        showInstanceNames = showInstanceNames, isLocallyAcknowledged = isLocallyAcknowledged,
                        problemKey = problemKey, onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                        onAck = onAck, onRecheck = onRecheck,
                        header = { SummaryRow(visibleCount = visible.size, totalCount = state.problems.size, lastUpdated = state.lastUpdated, stale = false) },
                        isRefreshing = false, onRefresh = onRetry)
                }
            }

            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProblemList(
    problems: List<NagiosProblem>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    showInstanceNames: Boolean,
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    problemKey: (NagiosProblem) -> String,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAck: (String) -> Unit,
    onRecheck: (String) -> Unit,
    header: @Composable () -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { header() }
            items(problems, key = { problemKey(it) }) { problem ->
                val key = problemKey(problem)
                val locallyAcked = isLocallyAcknowledged(problem)
                ProblemCard(
                    problem = problem,
                    isSelected = selectedIds.contains(key),
                    isSelectionMode = isSelectionMode,
                    isAcknowledged = problem.acknowledged || locallyAcked,
                    isPendingAck = locallyAcked && !problem.acknowledged,
                    instanceName = if (showInstanceNames) problem.instanceName else "",
                    onToggleSelect = { onToggleSelect(key) },
                    onLongPress = { onLongPress(key) },
                    onAck = { onAck(key) },
                    onRecheck = { onRecheck(key) },
                )
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
private fun SummaryRow(visibleCount: Int, totalCount: Int, lastUpdated: Long?, stale: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
