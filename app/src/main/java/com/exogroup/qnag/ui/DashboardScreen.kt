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
    nagiosViewModel: NagiosViewModel = viewModel(),
) {
    val enabledInstances = remember(allInstances) { allInstances.filter { it.enabled } }

    // Internal selection state — resets when `instance` changes (MainActivity navigation)
    var selectedInstance by remember(instance.id) {
        mutableStateOf<InstanceSelection>(InstanceSelection.Single(instance))
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
    val refreshMs = remember(notificationSettings.refreshIntervalMinutes) {
        notificationSettings.refreshIntervalMinutes.coerceAtLeast(15) * 60_000L
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
                                    // Switching to a different single instance also syncs MainActivity
                                    // so that "Open Settings" shows the correct fromInstance.
                                    is InstanceSelection.Single ->
                                        if (sel.instance.id != instance.id) onSwitchInstance(sel.instance)
                                        else selectedInstance = sel
                                    else -> selectedInstance = sel
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
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    problemKey: (NagiosProblem) -> String,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAck: (String) -> Unit,
    onRecheck: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) })
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
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) })
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            is DashboardState.Success -> {
                val visible = applyFiltersAndLocalAck(state.problems, filterSettings, isLocallyAcknowledged)
                when {
                    state.problems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("All green! No active problems.", color = MaterialTheme.colorScheme.primary)
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
                        header = { SummaryRow(visibleCount = visible.size, totalCount = state.problems.size, lastUpdated = state.lastUpdated, stale = false) })
                }
            }

            else -> Unit
        }
    }
}

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
