package com.exogroup.qnag.widget

import android.content.Context
import com.exogroup.qnag.data.EventLog
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NagiosFetchRetry
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.notifications.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared widget refresh logic.
 *
 * Fetches problems from all enabled instances, applies FilterSettings,
 * builds per-instance summaries, and saves the snapshot — completely independent
 * of NagiosViewModel, WorkManager, and the foreground service.
 *
 * No ACK/Recheck/Downtime commands are ever issued here.
 * No credentials, tokens, or URLs are stored in the snapshot.
 */
object WidgetRefresher {

    /**
     * Full network refresh. Safe to call from any suspend context.
     *
     * Flow:
     * 1. Sets REFRESHING state + triggers immediate widget re-render.
     * 2. Fetches from all enabled instances (best-effort; per-instance failures are skipped).
     * 3. On partial success: saves data, clears state.
     * 4. On total failure: keeps previous data, sets FAILED state.
     * 5. On no enabled instances: saves a "no instances" snapshot.
     */
    suspend fun refresh(context: Context) {
        // Immediately show "Refreshing…"
        WidgetSnapshotStore.setRefreshState(context, WidgetRefreshState.REFRESHING)
        WidgetUpdater.updateAll(context)

        try {
            val store     = SecureInstanceStore(context)
            val instances = store.getInstances()
            val settings  = store.getAppSettings()
            val targets   = instances.filter { it.enabled }

            if (targets.isEmpty()) {
                WidgetSnapshotStore.saveAndRefreshWidgets(
                    context          = context,
                    problems         = emptyList(),
                    lastUpdated      = System.currentTimeMillis(),
                    sourceTitle      = "",
                    noEnabledInstances = true,
                )
                return
            }

            val api         = NagiosApi()
            val allFiltered = mutableListOf<NagiosProblem>()
            val summaries   = mutableListOf<WidgetInstanceSummary>()
            var failCount   = 0

            for (instance in targets) {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        NagiosFetchRetry.fetchProblems(api, instance) { attempt, max, err ->
                            EventLog.warn(context, EventLog.CAT_POLLING,
                                "Widget retry — ${instance.name}: attempt $attempt/$max after ${sanitizeError(err.message)}")
                        }
                    }
                    val filtered = applyFilters(raw, settings.filterSettings)
                    allFiltered.addAll(filtered)
                    summaries += WidgetSnapshotStore.buildInstanceSummary(instance.name, filtered)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failCount++
                    summaries += WidgetInstanceSummary(
                        instanceName  = instance.name,
                        totalProblems = 0, down = 0, unreachable = 0,
                        critical = 0, warning = 0, unknown = 0,
                        failed = true,
                    )
                }
            }

            val sourceTitle  = if (targets.size == 1) targets[0].name else "All instances"
            val partialError = if (failCount in 1 until targets.size)
                "$failCount instance${if (failCount != 1) "s" else ""} unreachable"
            else null

            WidgetSnapshotStore.saveAndRefreshWidgets(
                context           = context,
                problems          = allFiltered,
                lastUpdated       = System.currentTimeMillis(),
                sourceTitle       = sourceTitle,
                instanceSummaries = summaries,
                instanceFailed    = failCount,
                lastRefreshError  = partialError,
            )

            // Only post refresh failure notification when the foreground service is not active.
            // If service is running, the foreground notification already shows failure state.
            val serviceRunning = MonitoringHealth.getSnapshot(context).isServiceRunning
            if (!serviceRunning) {
                if (failCount > 0) {
                    NotificationHelper.notifyRefreshFailure(context, failCount, targets.size)
                } else {
                    NotificationHelper.cancelRefreshFailure(context)
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetSnapshotStore.setRefreshState(
                context, WidgetRefreshState.FAILED, sanitizeError(e.message)
            )
            WidgetUpdater.updateAll(context)
        }
    }

    /**
     * Fire-and-forget wrapper — safe to call from non-suspend contexts (e.g. Compose callbacks,
     * Activity event handlers). Errors are swallowed so a widget failure never disrupts the UI.
     */
    fun launchRefresh(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { refresh(context) }
        }
    }

    /**
     * Called whenever the instance list changes (add / edit / delete / enable / import).
     *
     * If no enabled instances remain: immediately saves a "no instances" snapshot.
     * Otherwise: triggers a full background refresh so widget counts stay accurate.
     */
    fun onInstancesChanged(context: Context) {
        launchRefresh(context)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sanitizeError(msg: String?): String {
        if (msg == null) return "Unknown error"
        return msg
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .take(120)
    }
}
