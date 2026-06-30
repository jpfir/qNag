package com.exogroup.qnag.worker

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exogroup.qnag.data.AckAgeStore
import com.exogroup.qnag.data.AckSuppressCache
import com.exogroup.qnag.data.EventLog
import com.exogroup.qnag.data.MonitoringHealth
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NagiosFetchRetry
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatusSummary
import com.exogroup.qnag.data.UserMonitoringPause
import com.exogroup.qnag.data.NotificationDecisionReason
import com.exogroup.qnag.data.NotificationMode
import com.exogroup.qnag.data.ProblemAgeStore
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.evaluateNotificationDecision
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.instanceFingerprintPrefix
import com.exogroup.qnag.data.problemFingerprint
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.notifications.ProblemToNotify
import com.exogroup.qnag.notifications.buildCompactSummaryFromProblems
import com.exogroup.qnag.notifications.deriveVisualStateFromProblems
import com.exogroup.qnag.widget.WidgetInstanceSummary
import com.exogroup.qnag.widget.WidgetSnapshotStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background polling worker.
 *
 * Runs on the WorkManager thread pool (off the main thread).
 * Polls all enabled+notificationsEnabled instances, compares against the last-known
 * problem fingerprints, and posts Android notifications for new or changed problems.
 *
 * Fingerprint storage uses plain SharedPreferences (problem IDs are not secret).
 * Passwords are never logged or stored here.
 */
class NagiosPollingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        if (UserMonitoringPause.isPaused(applicationContext)) return Result.success()

        val store = SecureInstanceStore(applicationContext)
        val instances = store.getInstances()
        val settings = store.getAppSettings()
        val notifSettings = settings.notificationSettings
        val cmdSettings = settings.commandSettings

        if (!notifSettings.notificationsEnabled) return Result.success()

        MonitoringHealth.recordWorkerRun(applicationContext)
        MonitoringHealth.recordPollStart(applicationContext)

        // Evict expired ACK-suppress entries before the poll cycle
        AckSuppressCache.evictExpired(applicationContext)

        val api = NagiosApi()
        var fingerprints = loadFingerprints()
        var failedInstanceIds = loadFailedInstances()

        val targets = instances.filter { it.enabled && it.notificationsEnabled }
        val toNotify = mutableListOf<ProblemToNotify>()          // new/changed — drives sound in all modes
        val allCurrentProblems = mutableListOf<ProblemToNotify>() // all current — drives summary text
        val allFilteredProblems = mutableListOf<NagiosProblem>()  // filtered — drives widget snapshot
        val widgetInstanceSummaries = mutableListOf<WidgetInstanceSummary>()
        var widgetFailCount = 0                                    // instances that threw during widget fetch
        val failedInstanceNames = mutableListOf<String>()         // names of instances that failed
        val statusSummaries = mutableListOf<NagiosStatusSummary>()
        val prevTier2WaitingFps = loadTier2WaitingFps()
        val newTier2WaitingFps  = mutableSetOf<String>()
        // Collect age keys across ALL instances before pruning so one instance's
        // result never removes Tier 2+ first-seen entries belonging to another instance.
        val allActiveAgeKeys    = mutableSetOf<String>()

        for (instance in targets) {
            try {
                val problems = withContext(Dispatchers.IO) {
                    NagiosFetchRetry.fetchProblems(api, instance) { attempt, max, err ->
                        EventLog.warn(applicationContext, EventLog.CAT_POLLING,
                            "Poll retry — ${instance.name}: attempt $attempt/$max after ${sanitizeError(err.message)}")
                    }
                }
                val filtered = applyFilters(problems, settings.filterSettings)
                allFilteredProblems.addAll(filtered)
                widgetInstanceSummaries += WidgetSnapshotStore.buildInstanceSummary(instance.name, filtered)

                val currentFingerprints = filtered
                    .map { problemFingerprint(instance.id, it) }
                    .toSet()

                val prefix = instanceFingerprintPrefix(instance.id)
                val knownForInstance = fingerprints
                    .filter { it.startsWith(prefix) }
                    .toSet()

                fingerprints = (fingerprints - knownForInstance) + currentFingerprints

                val now = System.currentTimeMillis()
                // Update age stores before evaluating decisions
                for (problem in filtered) {
                    if (notifSettings.tier2PlusEnabled) {
                        ProblemAgeStore.recordIfAbsent(applicationContext, instance.id, problem)
                        allActiveAgeKeys += ProblemAgeStore.key(instance.id, problem)
                    }
                    if (problem.acknowledged)
                        AckAgeStore.recordIfAbsent(applicationContext, instance.id, problem)
                    else
                        AckAgeStore.remove(applicationContext, instance.id, problem)
                }

                for (problem in filtered) {
                    val decision = evaluateNotificationDecision(
                        instance.id, problem, notifSettings, now, applicationContext)
                    val fp = problemFingerprint(instance.id, problem)

                    if (decision.reason == NotificationDecisionReason.TIER2_WAITING) {
                        newTier2WaitingFps.add(fp)
                    }

                    if (!decision.shouldNotify) continue
                    if (decision.reason != NotificationDecisionReason.ACKED_RENOTIFY_ELIGIBLE &&
                        AckSuppressCache.isSuppressed(applicationContext, instance.id, problem)) continue
                    // All current notification-eligible problems for summary text and sound
                    allCurrentProblems += ProblemToNotify(instance.id, instance.name, problem)
                    // New/changed problems for sound and PER_PROBLEM mode
                    val isNew = fp !in knownForInstance
                    if (!cmdSettings.notifyOnlyNewProblems || isNew) {
                        toNotify += ProblemToNotify(instance.id, instance.name, problem)
                    }
                }

                MonitoringHealth.recordPollSuccess(applicationContext)

                // Best-effort totals fetch — short timeout, must not block alerting or fail instance
                runCatching {
                    withContext(Dispatchers.IO) { api.fetchStatusSummary(instance) }
                }.getOrNull()?.also { statusSummaries += it }

                // Recovery: clear failure state
                if (instance.id in failedInstanceIds) {
                    failedInstanceIds = failedInstanceIds - instance.id
                    if (notifSettings.notificationMode == NotificationMode.PER_PROBLEM) {
                        NotificationHelper.cancelFetchFailure(applicationContext, instance.id)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeError = sanitizeError(e.message)
                widgetFailCount++
                failedInstanceNames += instance.name
                widgetInstanceSummaries += WidgetInstanceSummary(
                    instanceName  = instance.name,
                    totalProblems = 0, down = 0, unreachable = 0, critical = 0, warning = 0, unknown = 0,
                    failed        = true,
                )
                when (notifSettings.notificationMode) {
                    NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS -> Unit
                    NotificationMode.PER_PROBLEM ->
                        if (cmdSettings.notifyOnFetchFailure && instance.id !in failedInstanceIds) {
                            NotificationHelper.notifyFetchFailure(applicationContext, instance.id, instance.name, safeError)
                            failedInstanceIds = failedInstanceIds + instance.id
                        }
                }
            }
        }

        val widgetSourceTitle = if (targets.size == 1) targets[0].name else "All instances"

        // Skip all summary/status notifications if foreground service is active.
        // The foreground notification (MONITORING_SERVICE_NOTIF_ID) is the one visible
        // status notification in Reliability Mode; posting background summaries here
        // would create duplicate qNag notifications in the notification shade.
        val serviceRunning = MonitoringHealth.getSnapshot(applicationContext).isServiceRunning
        if (!serviceRunning) {
            val visualState = deriveVisualStateFromProblems(allCurrentProblems, failedInstanceNames.size)
            val shouldPulse: Boolean = when (notifSettings.notificationMode) {
                NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS ->
                    NotificationHelper.notifySummary(applicationContext, toNotify, allCurrentProblems, failedInstanceNames, notifSettings)
                NotificationMode.PER_PROBLEM -> {
                    NotificationHelper.notifyBatch(applicationContext, toNotify, notifSettings)
                    false
                }
            }
            if (shouldPulse) {
                val compactSummary = buildCompactSummaryFromProblems(
                    sourceTitle     = widgetSourceTitle,
                    allProblems     = allCurrentProblems,
                    failedInstances = failedInstanceNames,
                    instanceTotal   = targets.size,
                    lastUpdated     = System.currentTimeMillis(),
                    statusSummaries = statusSummaries,
                )
                NotificationHelper.postWearableAlertPulse(applicationContext, compactSummary, visualState, notifSettings)
            } else if (allCurrentProblems.isEmpty() && failedInstanceNames.isEmpty()) {
                NotificationHelper.cancelNagiosAlertNotification(applicationContext)
            }
        }

        MonitoringHealth.recordWorkerSuccess(applicationContext)
        MonitoringHealth.recordPollFinished(applicationContext)

        // Stale monitoring self-check (skip if service is running — service manages stale state)
        if (!serviceRunning && cmdSettings.staleMonitoringAlertEnabled) {
            val lastSuccess = MonitoringHealth.getSnapshot(applicationContext).lastSuccessfulPollAt
            if (lastSuccess != null) {
                val staleMs = cmdSettings.monitoringStaleThresholdMinutes * 60_000L
                if (System.currentTimeMillis() - lastSuccess > staleMs) {
                    val minAgo = (System.currentTimeMillis() - lastSuccess) / 60_000L
                    NotificationHelper.notifyStale(applicationContext, "No successful poll in ${minAgo}m")
                } else {
                    NotificationHelper.cancelStale(applicationContext)
                }
            }
        }

        // Prune stale age entries once per full poll cycle using keys from ALL instances
        if (notifSettings.tier2PlusEnabled) {
            ProblemAgeStore.pruneStale(applicationContext, allActiveAgeKeys)
        }
        saveFingerprints(fingerprints)
        saveFailedInstances(failedInstanceIds)
        saveTier2WaitingFps(newTier2WaitingFps)

        // Post or cancel the qNag refresh failure notification (skip if service is running)
        if (!serviceRunning) {
            if (failedInstanceNames.isNotEmpty()) {
                NotificationHelper.notifyRefreshFailure(applicationContext, failedInstanceNames.size, targets.size)
            } else {
                NotificationHelper.cancelRefreshFailure(applicationContext)
            }
        }

        // Update home screen widgets with the fresh filtered problem snapshot (best-effort)
        runCatching {
            WidgetSnapshotStore.saveAndRefreshWidgets(
                applicationContext, allFilteredProblems, System.currentTimeMillis(),
                widgetSourceTitle, widgetInstanceSummaries, instanceFailed = widgetFailCount,
                statusSummaries = statusSummaries,
            )
        }

        return Result.success()
    }

    // ── State persistence (plain SharedPreferences — no sensitive data here) ──

    private fun loadFingerprints(): Set<String> {
        val json = prefs().getString(KEY_FINGERPRINTS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveFingerprints(fps: Set<String>) {
        prefs().edit { putString(KEY_FINGERPRINTS, gson.toJson(fps)) }
    }

    private fun loadFailedInstances(): Set<String> {
        val json = prefs().getString(KEY_FAILED, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveFailedInstances(failed: Set<String>) {
        prefs().edit { putString(KEY_FAILED, gson.toJson(failed)) }
    }

    private fun loadTier2WaitingFps(): Set<String> {
        val json = prefs().getString(KEY_TIER2_WAITING, null) ?: return emptySet()
        return try { gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type) ?: emptySet() }
        catch (_: Exception) { emptySet() }
    }

    private fun saveTier2WaitingFps(fps: Set<String>) {
        prefs().edit { putString(KEY_TIER2_WAITING, gson.toJson(fps)) }
    }

    private fun prefs() =
        applicationContext.getSharedPreferences("qnag_polling_state", Context.MODE_PRIVATE)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Strip any URL-like substring (which could contain embedded credentials)
     * and truncate to a safe length.
     */
    private fun sanitizeError(msg: String?): String {
        if (msg == null) return "Unknown error"
        return msg
            .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
            .take(200)
    }

    private companion object {
        const val KEY_FINGERPRINTS  = "fingerprints"
        const val KEY_FAILED        = "failed_instances"
        const val KEY_TIER2_WAITING = "tier2_waiting_fps"
    }
}
