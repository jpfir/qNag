package com.exogroup.qnag.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.exogroup.qnag.data.NagiosApi
import com.exogroup.qnag.data.SecureInstanceStore
import com.exogroup.qnag.data.applyFilters
import com.exogroup.qnag.data.fetchFailureNotificationId
import com.exogroup.qnag.data.instanceFingerprintPrefix
import com.exogroup.qnag.data.problemFingerprint
import com.exogroup.qnag.data.shouldNotify
import com.exogroup.qnag.notifications.NotificationHelper
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

        // Skip entirely if global notifications are disabled
        if (!notifSettings.notificationsEnabled) return Result.success()

        val api = NagiosApi()
        var fingerprints = loadFingerprints()
        var failedInstanceIds = loadFailedInstances()

        val targets = instances.filter { it.enabled && it.notificationsEnabled }

        for (instance in targets) {
            try {
                val problems = withContext(Dispatchers.IO) { api.fetchProblems(instance) }

                // Apply the user's dashboard filters so notifications respect them
                val filtered = applyFilters(problems, settings.filterSettings)

                // Fingerprints for all problems in this poll (regardless of shouldNotify)
                val currentFingerprints = filtered
                    .map { problemFingerprint(instance.id, it) }
                    .toSet()

                // Fingerprints previously stored for this instance
                // Use separator-based prefix to avoid a UUID being a prefix of another UUID
                val prefix = instanceFingerprintPrefix(instance.id)
                val knownForInstance = fingerprints
                    .filter { it.startsWith(prefix) }
                    .toSet()

                // Remove fingerprints for problems that have resolved
                fingerprints = (fingerprints - knownForInstance) + currentFingerprints

                // Notify for problems that pass the notification guards
                for (problem in filtered) {
                    if (!shouldNotify(problem, notifSettings)) continue
                    val fp = problemFingerprint(instance.id, problem)
                    val isNew = fp !in knownForInstance
                    if (!cmdSettings.notifyOnlyNewProblems || isNew) {
                        NotificationHelper.notifyProblem(
                            applicationContext,
                            instance.id,
                            instance.name,
                            problem,
                        )
                    }
                }

                // Clear any lingering fetch-failure state for this instance
                if (instance.id in failedInstanceIds) {
                    failedInstanceIds = failedInstanceIds - instance.id
                    NotificationHelper.cancelFetchFailure(applicationContext, instance.id)
                }

            } catch (e: Exception) {
                // Never log the exception verbatim — it may contain the Nagios URL which
                // could include credentials in some configurations.
                val safeError = sanitizeError(e.message)

                if (cmdSettings.notifyOnFetchFailure && instance.id !in failedInstanceIds) {
                    NotificationHelper.notifyFetchFailure(
                        applicationContext,
                        instance.id,
                        instance.name,
                        safeError,
                    )
                    failedInstanceIds = failedInstanceIds + instance.id
                }
            }
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
