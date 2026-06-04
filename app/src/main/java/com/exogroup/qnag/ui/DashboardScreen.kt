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
    // Forced initial fetch when the instance changes; clears selection too
    LaunchedEffect(instance.id) {
        nagiosViewModel.fetchAlerts(instance, skipIfRunning = false)
    }

    // ── Foreground auto-refresh loop ─────────────────────────────────────────
    val refreshMs = remember(notificationSettings.refreshIntervalMinutes) {
        notificationSettings.refreshIntervalMinutes.coerceAtLeast(15) * 60_000L
    }
    LaunchedEffect(instance.id, refreshMs) {
        while (true) {
            delay(refreshMs)
            // Skip if a fetch is already in flight (avoids pile-up during slow responses)
            if (instance.enabled) nagiosViewModel.fetchAlerts(instance, skipIfRunning = true)
        }
    }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    val enabledInstances = remember(allInstances) { allInstances.filter { it.enabled } }

    // Lambda that checks the local ACK overlay for a given problem in this instance.
    // Defined before visibleProblems so derivedStateOf can track localAcknowledgedMap reads.
    val isLocallyAcked: (NagiosProblem) -> Boolean = { p ->
        nagiosViewModel.isLocallyAcknowledged(instance.id, p)
    }

    // ── Reactive visible problems ─────────────────────────────────────────────
    // Also filters out locally ACKed problems when hideAcknowledgedHostsAndServices=true,
    // so the card disappears immediately after a successful ACK without waiting for next fetch.
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

    // Clear selection when instance changes or when items become hidden
    LaunchedEffect(instance.id) { selectedIds = emptySet() }
    LaunchedEffect(visibleProblems) {
        val visibleIds = visibleProblems.map { it.uniqueId }.toSet()
        val updated = selectedIds.intersect(visibleIds)
        if (updated != selectedIds) selectedIds = updated
    }

    // ── Snackbar for command feedback ─────────────────────────────────────────
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text("${selectedIds.size} Selected")
                    } else {
                        InstanceTitle(
                            instance = instance,
                            enabledInstances = enabledInstances,
                            onSwitchInstance = onSwitchInstance,
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
                        val selectedProblems = visibleProblems.filter { selectedIds.contains(it.uniqueId) }
                        IconButton(onClick = {
                            nagiosViewModel.acknowledgeProblems(instance, selectedProblems, commandSettings)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Check, "Acknowledge selected")
                        }
                        IconButton(onClick = {
                            nagiosViewModel.recheckProblems(instance, selectedProblems, commandSettings)
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Refresh, "Recheck selected")
                        }
                    } else {
                        IconButton(onClick = onAddNewInstance) {
                            Icon(Icons.Default.Add, "Add instance")
                        }
                        IconButton(onClick = { nagiosViewModel.fetchAlerts(instance, skipIfRunning = false) }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSelectionMode)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { paddingValues ->
        DashboardContent(
            state = nagiosViewModel.uiState,
            filterSettings = filterSettings,
            selectedIds = selectedIds,
            isSelectionMode = isSelectionMode,
            isLocallyAcknowledged = isLocallyAcked,
            onToggleSelect = { id ->
                selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
            },
            onLongPress = { id -> selectedIds = selectedIds + id },
            onAck = { id ->
                val problem = visibleProblems.firstOrNull { it.uniqueId == id }
                if (problem != null) nagiosViewModel.acknowledgeProblems(instance, listOf(problem), commandSettings)
            },
            onRecheck = { id ->
                val problem = visibleProblems.firstOrNull { it.uniqueId == id }
                if (problem != null) nagiosViewModel.recheckProblems(instance, listOf(problem), commandSettings)
            },
            onRetry = { nagiosViewModel.fetchAlerts(instance, skipIfRunning = false) },
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
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
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
                    ProblemList(
                        problems = visible, selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode, isLocallyAcknowledged = isLocallyAcknowledged,
                        onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                        onAck = onAck, onRecheck = onRecheck,
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) },
                    )
                }
            }

            is DashboardState.Loading -> {
                val stale = state.previousProblems
                if (stale != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    val visible = applyFiltersAndLocalAck(stale, filterSettings, isLocallyAcknowledged)
                    ProblemList(
                        problems = visible, selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode, isLocallyAcknowledged = isLocallyAcknowledged,
                        onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                        onAck = onAck, onRecheck = onRecheck,
                        header = { SummaryRow(visibleCount = visible.size, totalCount = stale.size, lastUpdated = null, stale = true) },
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            is DashboardState.Success -> {
                val visible = applyFiltersAndLocalAck(state.problems, filterSettings, isLocallyAcknowledged)
                when {
                    state.problems.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("All green! No active problems.", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    visible.isEmpty() -> {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            SummaryRow(visibleCount = 0, totalCount = state.problems.size, lastUpdated = state.lastUpdated, stale = false)
                            Spacer(Modifier.height(16.dp))
                            Text("No visible problems.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Some problems may be hidden by filters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        ProblemList(
                            problems = visible, selectedIds = selectedIds,
                            isSelectionMode = isSelectionMode, isLocallyAcknowledged = isLocallyAcknowledged,
                            onToggleSelect = onToggleSelect, onLongPress = onLongPress,
                            onAck = onAck, onRecheck = onRecheck,
                            header = { SummaryRow(visibleCount = visible.size, totalCount = state.problems.size, lastUpdated = state.lastUpdated, stale = false) },
                        )
                    }
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
    isLocallyAcknowledged: (NagiosProblem) -> Boolean,
    onToggleSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAck: (String) -> Unit,
    onRecheck: (String) -> Unit,
    header: @Composable () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        items(problems, key = { it.uniqueId }) { problem ->
            val locallyAcked = isLocallyAcknowledged(problem)
            ProblemCard(
                problem = problem,
                isSelected = selectedIds.contains(problem.uniqueId),
                isSelectionMode = isSelectionMode,
                isAcknowledged = problem.acknowledged || locallyAcked,
                isPendingAck = locallyAcked && !problem.acknowledged,
                onToggleSelect = { onToggleSelect(problem.uniqueId) },
                onLongPress = { onLongPress(problem.uniqueId) },
                onAck = { onAck(problem.uniqueId) },
                onRecheck = { onRecheck(problem.uniqueId) },
            )
        }
    }
}

@Composable
private fun SummaryRow(visibleCount: Int, totalCount: Int, lastUpdated: Long?, stale: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val countText = if (visibleCount == totalCount) {
            "$totalCount problem${if (totalCount != 1) "s" else ""}"
        } else {
            "$visibleCount visible / $totalCount total"
        }
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
            Text(
                text = message, color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun InstanceTitle(
    instance: NagiosInstance,
    enabledInstances: List<NagiosInstance>,
    onSwitchInstance: (NagiosInstance) -> Unit,
) {
    if (enabledInstances.size <= 1) {
        Text(instance.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(instance.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            enabledInstances.forEach { inst ->
                DropdownMenuItem(
                    text = { Text(inst.name) },
                    onClick = {
                        expanded = false
                        if (inst.id != instance.id) onSwitchInstance(inst)
                    },
                )
            }
        }
    }
}

/**
 * Applies server-side filters AND the local ACK overlay.
 * When [filters].hideAcknowledgedHostsAndServices is true, problems that are locally
 * ACKed (but not yet confirmed by the server) are hidden immediately after a successful ACK.
 * Failed ACKs never populate [isLocallyAcked], so nothing is incorrectly hidden on failure.
 */
private fun applyFiltersAndLocalAck(
    problems: List<NagiosProblem>,
    filters: FilterSettings,
    isLocallyAcked: (NagiosProblem) -> Boolean,
): List<NagiosProblem> {
    val filtered = applyFilters(problems, filters)
    return if (filters.hideAcknowledgedHostsAndServices) {
        filtered.filter { !isLocallyAcked(it) }
    } else filtered
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
