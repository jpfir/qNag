package com.exogroup.qnag.sound

import android.content.Context
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NagiosProblem
import com.exogroup.qnag.data.NagiosStatus
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.notifications.ProblemToNotify

/**
 * Unified alert sound decision engine.
 *
 * Used by BOTH the foreground service and WorkManager so both code paths
 * produce identical sound behaviour.  Only one sound event per poll cycle,
 * regardless of how many new problems arrived.
 *
 * Sound state is persisted in plain SharedPreferences so it survives process
 * restarts.  No credentials are stored here.
 *
 * Sound plays when:
 *  - previous state was OK (severity 0) and now there are problems,
 *  - worst severity INCREASED compared to previous poll,
 *  - new problem fingerprints appeared that weren't present last poll AND cooldown allows.
 *
 * Sound does NOT play when:
 *  - the exact same problem set persists unchanged,
 *  - global cooldown is not yet elapsed,
 *  - alertSoundMode == NOTIFICATION_CHANNEL_ONLY.
 */
object AlertSoundController {

    private const val PREFS = "qnag_alert_sound_state"
    private const val KEY_SEVERITY = "worst_severity"
    private const val KEY_LAST_SOUND = "last_sound_ms"
    private const val KEY_FINGERPRINTS = "prev_fingerprints"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluate the current poll results and play a sound if the alert state is new/worse.
     *
     * @param allCurrentProblems All problems that passed notification filters this poll.
     * @param newProblems        Subset of [allCurrentProblems] that are new/changed this poll.
     * @param failedInstanceCount Number of instances that failed their fetch this poll.
     * @param settings           Current notification settings.
     * @param debug              Log safe diagnostics when true.
     */
    fun evaluateAndPlay(
        context: Context,
        allCurrentProblems: List<ProblemToNotify>,
        newProblems: List<ProblemToNotify>,
        failedInstanceCount: Int = 0,
        settings: NotificationSettings,
        debug: Boolean = false,
    ) {
        if (settings.alertSoundMode == AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) return

        val currentSeverity = computeWorstSeverity(allCurrentProblems, failedInstanceCount)
        val currentFps = allCurrentProblems.map { it.instanceId + "|" + it.problem.uniqueId }.toSet()

        val prefs = prefs(context)
        val prevSeverity = prefs.getInt(KEY_SEVERITY, 0)
        val lastSoundMs  = prefs.getLong(KEY_LAST_SOUND, 0L)
        val prevFps      = prefs.getString(KEY_FINGERPRINTS, null)
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

        val hasNewFps = (currentFps - prevFps).isNotEmpty()

        val shouldPlay = shouldPlay(
            hasNewProblems  = hasNewFps || newProblems.isNotEmpty(),
            currentSeverity = currentSeverity,
            prevSeverity    = prevSeverity,
            lastSoundMs     = lastSoundMs,
            settings        = settings,
        )

        if (debug) android.util.Log.d("qNag",
            "[sound] severity=$currentSeverity prevSeverity=$prevSeverity " +
            "newFps=${(currentFps - prevFps).size} hasNewFps=$hasNewFps shouldPlay=$shouldPlay " +
            "mode=${settings.alertSoundMode}")

        AlertSoundPlayer.playIfNeeded(context, shouldPlay, settings, debug)

        // Persist new state
        prefs.edit()
            .putInt(KEY_SEVERITY, currentSeverity)
            .putString(KEY_FINGERPRINTS, currentFps.joinToString(","))
            .also { if (shouldPlay) it.putLong(KEY_LAST_SOUND, System.currentTimeMillis()) }
            .apply()
    }

    /**
     * Reset sound state to "all-clear".  Call after all problems clear so the next
     * problem set will always trigger a sound regardless of cooldown.
     */
    fun resetState(context: Context) {
        prefs(context).edit()
            .putInt(KEY_SEVERITY, 0)
            .putString(KEY_FINGERPRINTS, "")
            .apply()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun computeWorstSeverity(problems: List<ProblemToNotify>, failedCount: Int): Int {
        val hostDown = problems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
        val svcCrit  = problems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
        val hostUnr  = problems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
        val svcWarn  = problems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
        return when {
            hostDown   > 0 -> 5
            svcCrit    > 0 -> 4
            hostUnr    > 0 || failedCount > 0 -> 3
            svcWarn    > 0 -> 2
            problems.isNotEmpty() -> 1
            else -> 0
        }
    }

    private fun shouldPlay(
        hasNewProblems: Boolean,
        currentSeverity: Int,
        prevSeverity: Int,
        lastSoundMs: Long,
        settings: NotificationSettings,
    ): Boolean {
        if (currentSeverity == 0) return false
        val now = System.currentTimeMillis()
        val cooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
        if (settings.globalSoundCooldownSeconds > 0 && (now - lastSoundMs) < cooldownMs) return false
        // Sound on: severity increase, transition from OK, or explicit repeat setting
        return currentSeverity > prevSeverity ||
               (hasNewProblems && prevSeverity == 0) ||
               (hasNewProblems && settings.repeatSameProblemSound)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
