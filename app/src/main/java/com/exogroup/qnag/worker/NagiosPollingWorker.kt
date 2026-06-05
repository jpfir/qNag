package com.exogroup.qnag.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exogroup.qnag.data.AckSuppressCache
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.NotificationMode
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.instanceFingerprintPrefix
import com.exogroup.qnag.data.problemFingerprint
import com.exogroup.qnag.data.shouldNotify
import com.exogroup.qnag.notifications.NotificationHelper
import com.exogroup.qnag.notifications.ProblemToNotify
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        val store = SecureInstanceStore(applicationContext)
        val instances = store.getInstances()
        val settings = store.getAppSettings()
        val notifSettings = settings.notificationSettings
        val cmdSettings = settings.commandSettings

        if (!notifSettings.notificationsEnabled) return Result.success()

        // Evict expired ACK-suppress entries before the poll cycle
        AckSuppressCache.evictExpired(applicationContext)

        val api = NagiosApi()
        var fingerprints = loadFingerprints()
        var failedInstanceIds = loadFailedInstances()

        val targets = instances.filter { it.enabled && it.notificationsEnabled }
        val toNotify = mutableListOf<ProblemToNotify>()          // new/changed — drives sound in all modes
        val allCurrentProblems = mutableListOf<ProblemToNotify>() // all current — drives summary text
        val failedInstanceNames = mutableListOf<String>()         // names of instances that failed

        for (instance in targets) {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }

                val filtered = applyFilters(problems, settings.filterSettings)

                val currentFingerprints = filtered
                    .map { problemFingerprint(instance.id, it) }
                    .toSet()

                val prefix = instanceFingerprintPrefix(instance.id)
                val knownForInstance = fingerprints
                    .filter { it.startsWith(prefix) }
                    .toSet()

                fingerprints = (fingerprints - knownForInstance) + currentFingerprints

                for (problem in filtered) {
                    if (!shouldNotify(problem, notifSettings)) continue
                    if (notifSettings.notifyOnlyUnacknowledged &&
                        AckSuppressCache.isSuppressed(applicationContext, instance.id, problem)) continue
                    // All current problems for summary text
                    allCurrentProblems += ProblemToNotify(instance.id, instance.name, problem)
                    // New/changed problems for sound and PER_PROBLEM mode
                    val fp = problemFingerprint(instance.id, problem)
                    val isNew = fp !in knownForInstance
                    if (!cmdSettings.notifyOnlyNewProblems || isNew) {
                        toNotify += ProblemToNotify(instance.id, instance.name, problem)
                    }
                }

                // Recovery: clear failure state
                if (instance.id in failedInstanceIds) {
                    failedInstanceIds = failedInstanceIds - instance.id
                    if (notifSettings.notificationMode == NotificationMode.PER_PROBLEM) {
                        NotificationHelper.cancelFetchFailure(applicationContext, instance.id)
                    }
                }

            } catch (e: Exception) {
                val safeError = sanitizeError(e.message)
                when (notifSettings.notificationMode) {
                    NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS ->
                        failedInstanceNames += instance.name
                    NotificationMode.PER_PROBLEM ->
                        if (cmdSettings.notifyOnFetchFailure && instance.id !in failedInstanceIds) {
                            NotificationHelper.notifyFetchFailure(applicationContext, instance.id, instance.name, safeError)
                            failedInstanceIds = failedInstanceIds + instance.id
                        }
                }
            }
        }

        when (notifSettings.notificationMode) {
            NotificationMode.SUMMARY_ONLY, NotificationMode.GROUPED_DETAILS ->
                NotificationHelper.notifySummary(applicationContext, toNotify, allCurrentProblems, failedInstanceNames, notifSettings)
            NotificationMode.PER_PROBLEM ->
                NotificationHelper.notifyBatch(applicationContext, toNotify, notifSettings)
        }

        saveFingerprints(fingerprints)
        saveFailedInstances(failedInstanceIds)

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
        prefs().edit().putString(KEY_FINGERPRINTS, gson.toJson(fps)).apply()
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
        prefs().edit().putString(KEY_FAILED, gson.toJson(failed)).apply()
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
        const val KEY_FINGERPRINTS = "fingerprints"
        const val KEY_FAILED = "failed_instances"
    }
}
