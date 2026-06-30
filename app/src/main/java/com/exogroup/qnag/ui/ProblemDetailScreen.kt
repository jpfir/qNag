package com.exogroup.qnag.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exogroup.qnag.data.AckComment
import com.exogroup.qnag.data.CommandSettings
import com.exogroup.qnag.data.NagiosInstance
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NagiosUrl
import com.exogroup.qnag.viewmodel.AckCommentState
import com.exogroup.qnag.viewmodel.CommandState
import com.exogroup.qnag.viewmodel.DashboardState
import com.exogroup.qnag.viewmodel.NagiosViewModel
import java.net.URLEncoder

/** Full-screen detail view for a single host or service problem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemDetailScreen(
    problem: NagiosProblem,
    instance: NagiosInstance?,
    commandSettings: CommandSettings,
    onBack: () -> Unit,
    nagiosViewModel: NagiosViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showUnackConfirm    by remember { mutableStateOf(false) }
    var showDowntimeDialog  by remember { mutableStateOf(false) }

    if (showDowntimeDialog) {
        DowntimeDialog(
            problems = listOf(problem),
            instance = instance,
            commandSettings = commandSettings,
            onDismiss = { showDowntimeDialog = false },
            onSchedule = { scope, durationMs, comment ->
                // scope is never null for a single problem (dialog uses non-mixed mode)
                if (instance != null && scope != null) {
                    nagiosViewModel.scheduleDowntime(instance, listOf(problem), scope, durationMs, comment, commandSettings)
                }
                showDowntimeDialog = false
            },
        )
    }

    if (showUnackConfirm) {
        AlertDialog(
            onDismissRequest = { showUnackConfirm = false },
            title = { Text("Remove acknowledgement") },
            text = { Text("Remove acknowledgement from this alert?") },
            confirmButton = {
                TextButton(onClick = {
                    if (instance != null) {
                        nagiosViewModel.unacknowledgeProblems(instance, listOf(problem), commandSettings)
                    }
                    showUnackConfirm = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showUnackConfirm = false }) { Text("Cancel") }
            },
        )
    }

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

    // Fetch ACK comment from commentlist when the problem is server-acknowledged.
    // Keys include problem identity so navigating to a different acknowledged problem re-fetches.
    // Local-only (optimistic) ACKs are shown as "pending" without fetching — the comment
    // will not be in Nagios yet.
    LaunchedEffect(problem.uniqueId, problem.instanceId, problem.acknowledged, instance?.id) {
        nagiosViewModel.clearAckCommentState()
        if (problem.acknowledged && instance != null) {
            nagiosViewModel.fetchAckComment(instance, problem)
        }
    }
    DisposableEffect(Unit) {
        onDispose { nagiosViewModel.clearAckCommentState() }
    }

    // Raw problem list from the ViewModel — Null when no fetch has completed yet (Idle state).
    // Non-null even during Loading/Error if stale data exists from a prior successful fetch.
    val rawProblems: List<NagiosProblem>? = when (val s = nagiosViewModel.uiState) {
        is DashboardState.Success -> s.problems
        is DashboardState.Loading -> s.previousProblems
        is DashboardState.Error   -> s.previousProblems
        else                      -> null
    }
    // Related service problems: same instance + same hostName, sorted by severity.
    // Only computed for host problems; null when rawProblems is not yet available (Idle state).
    // Memoized on rawProblems reference + problem identity — recomputes only when data changes.
    val relatedServices: List<NagiosProblem.ServiceProblem>? = remember(
        problem.uniqueId,
        problem.instanceId,
        problem.hostName,
        instance?.id,
        rawProblems,
    ) {
        if (problem is NagiosProblem.HostProblem) {
            val resolvedInstanceId = problem.instanceId.ifEmpty { instance?.id ?: "" }
            rawProblems?.filterIsInstance<NagiosProblem.ServiceProblem>()
                ?.filter { svc ->
                    val svcInstId = svc.instanceId.ifEmpty { resolvedInstanceId }
                    svcInstId == resolvedInstanceId && svc.hostName == problem.hostName
                }
                ?.sortedBy { svc ->
                    when (svc.status) {
                        NagiosStatus.SERVICE_CRITICAL -> 0
                        NagiosStatus.SERVICE_WARNING  -> 1
                        NagiosStatus.SERVICE_UNKNOWN  -> 2
                        else                          -> 3
                    }
                }
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val title = when (problem) {
                            is NagiosProblem.ServiceProblem -> "${problem.hostName} / ${problem.serviceName}"
                            is NagiosProblem.HostProblem    -> "[HOST] ${problem.hostName}"
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (instance != null) {
                            Text(
                                instance.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Open in Nagios web UI
                    if (instance != null) {
                        IconButton(onClick = {
                            val url = nagiosExtInfoUrl(instance.url, problem)
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in Nagios")
                        }
                    }
                    // Share
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, buildAlertSummary(problem))
                        }
                        context.startActivity(Intent.createChooser(intent, "Share alert"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ACK / Remove ACK + Recheck
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (problem.acknowledged) {
                        OutlinedButton(
                            onClick = { showUnackConfirm = true },
                            enabled = instance != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Remove ACK") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                if (instance != null) {
                                    nagiosViewModel.acknowledgeProblems(instance, listOf(problem), commandSettings)
                                }
                            },
                            enabled = instance != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Acknowledge") }
                    }
                    Button(
                        onClick = {
                            if (instance != null) {
                                nagiosViewModel.recheckProblems(instance, listOf(problem), commandSettings)
                            }
                        },
                        enabled = instance != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Recheck") }
                }
                // Downtime
                OutlinedButton(
                    onClick = { showDowntimeDialog = true },
                    enabled = instance != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Schedule downtime…") }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Status + badge row
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusLabel = when (problem) {
                        is NagiosProblem.ServiceProblem -> serviceStatusLabel(problem.status)
                        is NagiosProblem.HostProblem    -> hostStatusLabel(problem.status)
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (problem.acknowledged) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF388E3C),
                            contentColor = Color.White,
                        ) {
                            Text(
                                "ACK",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (problem.scheduledDowntimeDepth > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFE3F2FD),
                            contentColor = Color(0xFF1565C0),
                        ) {
                            Text(
                                "IN DOWNTIME",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Plugin output (full, selectable)
            item {
                DetailSectionHeader("Output")
                SelectionContainer {
                    Text(
                        problem.pluginOutput,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Copy output button
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(problem.pluginOutput))
                    },
                    modifier = Modifier.padding(start = 0.dp),
                ) { Text("Copy output", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
            }

            // Related service alerts — host problems only, shown when data is available or list is non-empty
            if (problem is NagiosProblem.HostProblem && (relatedServices == null || relatedServices.isNotEmpty())) {
                item {
                    RelatedServicesSection(relatedServices)
                }
            }

            // Acknowledgement section — server-acked, locally-acked, or any ack metadata present
            val instanceId = instance?.id ?: problem.instanceId.ifEmpty { "" }
            val isLocallyAcked = nagiosViewModel.isLocallyAcknowledged(instanceId, problem)
            val hasAckSection = problem.acknowledged || isLocallyAcked ||
                !problem.acknowledgedBy.isNullOrBlank() ||
                !problem.acknowledgementComment.isNullOrBlank() ||
                problem.acknowledgementTime != null
            if (hasAckSection) {
                item {
                    DetailSectionHeader("Acknowledgement")
                    DetailAcknowledgementSection(
                        problem = problem,
                        isLocallyAcked = isLocallyAcked,
                        ackCommentState = nagiosViewModel.ackCommentState,
                        clipboardManager = clipboardManager,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Full metadata
            item {
                DetailSectionHeader("Details")
                DetailMetadataFull(problem, instance)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── Metadata section ──────────────────────────────────────────────────────────

/**
 * Shows acknowledgement status, author, time, and comment for the detail screen.
 *
 * Priority: commentlist data (server, freshest) > statusjson fields (from NagiosProblem) > fallbacks.
 *
 * States:
 *  - Idle / Loading with no data yet  → "Loading…"
 *  - Loading with existing statusjson data → show statusjson fields while fetching
 *  - Loaded(comment != null)          → show commentlist data (server wins)
 *  - Loaded(null) with statusjson     → show statusjson fields
 *  - Loaded(null) without any data    → "ACKed, details unavailable"
 *  - Error                            → show statusjson fallback or "unavailable"
 *  - isLocallyAcked && !serverAcked   → "ACK pending confirmation" (no fetch yet)
 */
@Composable
private fun DetailAcknowledgementSection(
    problem: NagiosProblem,
    isLocallyAcked: Boolean,
    ackCommentState: AckCommentState,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    val now = System.currentTimeMillis()
    val isServerAcked = problem.acknowledged

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ── Status row ────────────────────────────────────────────────────────
        DetailRow(
            "Status",
            when {
                isServerAcked  -> "acknowledged"
                isLocallyAcked -> "ACK pending confirmation"
                else           -> "not acknowledged"
            },
        )

        // Pending local ACK: nothing from the server yet — stop here
        if (!isServerAcked) return@Column

        // ── Resolve effective fields: commentlist > statusjson ────────────────
        val serverComment: AckComment? = (ackCommentState as? AckCommentState.Loaded)?.comment
        val dataResolved = ackCommentState is AckCommentState.Loaded
        val isLoading = ackCommentState is AckCommentState.Loading

        val effectiveAuthor = serverComment?.author?.takeIf { it.isNotBlank() }
            ?: problem.acknowledgedBy?.takeIf { it.isNotBlank() }
        val effectiveTime = serverComment?.entryTime ?: problem.acknowledgementTime
        val effectiveComment = serverComment?.comment?.takeIf { it.isNotBlank() }
            ?: problem.acknowledgementComment?.takeIf { it.isNotBlank() }
        val hasAnyDetail = effectiveAuthor != null || effectiveTime != null || effectiveComment != null

        // ── Detail rows ───────────────────────────────────────────────────────
        when {
            // Data not yet available and still loading
            !hasAnyDetail && isLoading -> {
                DetailRow("Details", "Loading…")
                return@Column
            }
            // Fetch done, nothing found anywhere
            !hasAnyDetail && dataResolved -> {
                DetailRow("Details", "ACKed, details unavailable")
                return@Column
            }
            // Idle with nothing from statusjson: show nothing extra (fetch will start shortly)
            !hasAnyDetail -> return@Column
        }

        // Author
        if (effectiveAuthor != null) {
            DetailRow("By", effectiveAuthor)
        } else if (dataResolved) {
            // Fetch finished but no author found
            DetailRow("By", "not reported by Nagios")
        }

        // ACK time
        effectiveTime?.let { ts ->
            DetailRow("ACK time", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }

        // Comment — only attempt once data is resolved or we already have statusjson comment
        val showCommentFallback = dataResolved && effectiveComment == null
        when {
            !effectiveComment.isNullOrBlank() -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Comment:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                SelectionContainer {
                    Text(effectiveComment, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { clipboardManager.setText(AnnotatedString(effectiveComment)) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text("Copy comment", style = MaterialTheme.typography.bodySmall)
                }
            }
            showCommentFallback -> DetailRow("Comment", "not reported by Nagios")
        }
    }
}

@Composable
private fun DetailMetadataFull(problem: NagiosProblem, instance: NagiosInstance? = null) {
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Instance — shown when available; helps when scrolled past the app bar subtitle
        if (instance != null && instance.name.isNotEmpty()) {
            DetailRow("Instance", instance.name)
        }

        // Check timing
        problem.lastCheck?.let { ts ->
            DetailRow("Last check", "${checkTime(ts)}  (${checkAge(now - ts)})")
        }
        problem.nextCheck?.let { ts ->
            val nextText = if (ts > now) checkIn(ts - now) else checkOverdue(now - ts)
            DetailRow("Next check", nextText)
        }
        problem.lastStateChange?.let { ts ->
            DetailRow("State for", checkDuration(now - ts))
        }
        problem.lastHardStateChange?.let { ts ->
            DetailRow("Hard state for", "${checkTime(ts)}  (${checkDuration(now - ts)})")
        }
        val attempt = problem.currentAttempt
        val maxAtt  = problem.maxAttempts
        if (attempt != null) {
            val stateLabel = if (problem.isSoftState) "SOFT" else "HARD"
            DetailRow("Attempt", if (maxAtt != null) "$attempt/$maxAtt  $stateLabel" else "$attempt  $stateLabel")
        }
        // check_type = last result type, not configured check mode
        problem.checkType?.let { DetailRow("Last result", it) }
        problem.passiveChecksEnabled?.let {
            DetailRow("Passive checks", if (it) "enabled" else "disabled")
        }
        problem.freshnessChecksEnabled?.let { f ->
            DetailRow("Freshness checks", if (f) "enabled" else "disabled")
            if (f) problem.freshnessThresholdSeconds?.let { DetailRow("Freshness threshold", "${it}s") }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))

        // State flags
        DetailRow("Acknowledged", if (problem.acknowledged) "yes" else "no")
        DetailRow("In downtime", if (problem.scheduledDowntimeDepth > 0) "yes (depth ${problem.scheduledDowntimeDepth})" else "no")
        DetailRow("Notifications", if (problem.notificationsEnabled) "enabled" else "disabled")
        DetailRow("Active checks", if (problem.checksEnabled) "enabled" else "disabled")
        DetailRow("Flapping", if (problem.isFlapping) "yes" else "no")

        // Host state (service problems only)
        if (problem is NagiosProblem.ServiceProblem) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            val hostStateLabel = when (problem.hostStatus) {
                NagiosStatus.HOST_DOWN        -> "DOWN"
                NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
                NagiosStatus.HOST_UP          -> "UP"
                null                          -> "unknown"
                else                          -> "status ${problem.hostStatus}"
            }
            DetailRow("Host state", hostStateLabel)
            DetailRow("Host acknowledged", if (problem.hostAcknowledged) "yes" else "no")
            DetailRow("Host in downtime", if (problem.hostScheduledDowntimeDepth > 0) "yes" else "no")
        }
    }
}

@Composable
private fun DetailSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Related services section (host problems) ─────────────────────────────────

private const val RELATED_SERVICES_INITIAL_SHOW = 8

/**
 * Shows related service alerts for a host problem.
 *
 * [relatedServices] is null when the raw problem list has not loaded yet (app just opened);
 * an empty list means the host has no active service alerts.
 */
@Composable
private fun RelatedServicesSection(relatedServices: List<NagiosProblem.ServiceProblem>?) {
    DetailSectionHeader("Related service alerts")
    if (relatedServices == null) {
        Text(
            "Related service alerts unavailable.\nRefresh qNag to load current related services.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        return
    }
    val critCount = relatedServices.count { it.status == NagiosStatus.SERVICE_CRITICAL }
    val warnCount = relatedServices.count { it.status == NagiosStatus.SERVICE_WARNING }
    val unkCount  = relatedServices.count { it.status == NagiosStatus.SERVICE_UNKNOWN }
    val ackCount  = relatedServices.count { it.acknowledged }
    val summaryParts = buildList {
        add("${relatedServices.size} service${if (relatedServices.size != 1) "s" else ""}")
        if (critCount > 0) add("CRIT $critCount")
        if (warnCount > 0) add("WARN $warnCount")
        if (unkCount > 0)  add("UNK $unkCount")
        if (ackCount > 0)  add("$ackCount ACKed")
    }
    Text(
        summaryParts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    var showAll by remember(relatedServices.size) {
        mutableStateOf(relatedServices.size <= RELATED_SERVICES_INITIAL_SHOW)
    }
    val toShow = if (showAll) relatedServices else relatedServices.take(RELATED_SERVICES_INITIAL_SHOW)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        toShow.forEach { svc -> RelatedServiceRow(svc) }
    }
    if (relatedServices.size > RELATED_SERVICES_INITIAL_SHOW) {
        TextButton(
            onClick = { showAll = !showAll },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                if (showAll) "Show fewer" else "Show all ${relatedServices.size}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RelatedServiceRow(svc: NagiosProblem.ServiceProblem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Status chip
        val (bgColor, fgColor) = when (svc.status) {
            NagiosStatus.SERVICE_CRITICAL -> Color(0xFFB71C1C) to Color.White
            NagiosStatus.SERVICE_WARNING  -> Color(0xFFE65100) to Color.White
            NagiosStatus.SERVICE_UNKNOWN  -> Color(0xFF6A1B9A) to Color.White
            else                          -> Color(0xFF616161) to Color.White
        }
        val statusLabel = when (svc.status) {
            NagiosStatus.SERVICE_CRITICAL -> "CRIT"
            NagiosStatus.SERVICE_WARNING  -> "WARN"
            NagiosStatus.SERVICE_UNKNOWN  -> "UNK"
            else                          -> serviceStatusLabel(svc.status)
        }
        Surface(shape = RoundedCornerShape(4.dp), color = bgColor, contentColor = fgColor) {
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        // Service name + badges + output
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    svc.serviceName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (svc.acknowledged) {
                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF388E3C), contentColor = Color.White) {
                        Text("ACK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
                if (svc.scheduledDowntimeDepth > 0) {
                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE3F2FD), contentColor = Color(0xFF1565C0)) {
                        Text("DT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
                if (!svc.notificationsEnabled) {
                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF5F5F5), contentColor = Color(0xFF616161)) {
                        Text("NOTIF OFF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
            }
            if (svc.pluginOutput.isNotBlank()) {
                Text(
                    svc.pluginOutput,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Utilities ────────────────────────────────────────────────────

/** Generate a Nagios extinfo.cgi URL for host or service details. */
fun nagiosExtInfoUrl(baseUrl: String, problem: NagiosProblem): String {
    val cgiBase = NagiosUrl.cgi(baseUrl, "extinfo.cgi")
    return when (problem) {
        is NagiosProblem.ServiceProblem -> {
            val host = URLEncoder.encode(problem.hostName, "UTF-8")
            val svc  = URLEncoder.encode(problem.serviceName, "UTF-8")
            "$cgiBase?type=2&host=$host&service=$svc"
        }
        is NagiosProblem.HostProblem -> {
            val host = URLEncoder.encode(problem.hostName, "UTF-8")
            "$cgiBase?type=1&host=$host"
        }
    }
}

/** Build a plain-text alert summary for sharing / copying (no credentials). */
fun buildAlertSummary(problem: NagiosProblem): String = buildString {
    val statusLabel = when (problem) {
        is NagiosProblem.ServiceProblem -> serviceStatusLabel(problem.status)
        is NagiosProblem.HostProblem    -> hostStatusLabel(problem.status)
    }
    appendLine("$statusLabel: ${problem.hostName}")
    if (problem is NagiosProblem.ServiceProblem) appendLine("Service: ${problem.serviceName}")
    if (problem.instanceName.isNotEmpty()) appendLine("Instance: ${problem.instanceName}")
    problem.lastStateChange?.let {
        appendLine("State for: ${checkDuration(System.currentTimeMillis() - it)}")
    }
    problem.lastCheck?.let {
        appendLine("Last check: ${checkTime(it)} (${checkAge(System.currentTimeMillis() - it)})")
    }
    append("Output: ${problem.pluginOutput}")
}
