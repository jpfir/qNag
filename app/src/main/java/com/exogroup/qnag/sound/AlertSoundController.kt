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
 * Used by BOTH the foreground service and WorkManager so both code paths produce
 * identical sound behaviour.  Only one sound event per poll cycle.
 *
 * Sound state is persisted in plain SharedPreferences (no credentials stored here).
 *
 * ── Sound decision rules (in priority order) ─────────────────────────────────
 *
 * 1. Severity escalation (e.g. WARNING → CRITICAL, CRITICAL → HOST DOWN):
 *    Sound immediately, bypassing the full global cooldown.
 *    Only a short race-prevention cooldown (ESCALATION_COOLDOWN_MS = 5 s) applies
 *    to avoid double-triggering when two rapid polls arrive.
 *
 * 2. Transition from all-clear (prevSeverity == 0 → currentSeverity > 0):
 *    Same as escalation — sounds immediately with the short cooldown.
 *
 * 3. New problem fingerprints at same severity:
 *    A problem that was not present in the previous poll is genuinely new.
 *    Sounds if the global cooldown has elapsed.
 *    Example: CRITICAL service A already sounded; 5 minutes later CRITICAL service B
 *    appears → sounds once because it is a new fingerprint.
 *
 * 4. Unchanged problem set, same severity:
 *    No sound unless repeatSameProblemSound = true (and cooldown allows).
 *
 * ── Fingerprint format ────────────────────────────────────────────────────────
 *
 * Problems:      instanceId + SEP + problem.uniqueId + SEP + problem.status
 * Fetch failures: "fetch_fail" + SEP + instanceName
 *
 * Using U+001F (unit separator) consistent with the rest of qNag.
 * Status is included so a WARNING → CRITICAL transition is treated as a new fingerprint,
 * correctly combining with the severity-escalation rule.
 */
object AlertSoundController {

    private const val PREFS           = "qnag_alert_sound_state"
    private const val KEY_SEVERITY    = "worst_severity"
    private const val KEY_LAST_SOUND  = "last_sound_ms"
    private const val KEY_FINGERPRINTS = "prev_fingerprints"

    /** U+001F separator — consistent with the rest of qNag's fingerprinting. */
    private const val SEP = ""

    /**
     * Minimum milliseconds between sounds when severity escalates.
     * Prevents double-triggering when two back-to-back rapid polls both see the escalation.
     * Does NOT block real escalation — only adds a 5 s grace window.
     */
    private const val ESCALATION_COOLDOWN_MS = 5_000L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluate the current poll results and play a sound if the alert state is new/worse.
     *
     * @param allCurrentProblems All problems that passed notification filters this poll.
     * @param newProblems        Subset that are new/changed this poll (used for debug).
     * @param failedInstanceNames Instance names whose fetch failed this poll.
     * @param settings           Current notification settings.
     * @param debug              Log safe diagnostics when true.
     */
    fun evaluateAndPlay(
        context: Context,
        allCurrentProblems: List<ProblemToNotify>,
        newProblems: List<ProblemToNotify>,
        failedInstanceNames: List<String> = emptyList(),
        settings: NotificationSettings,
        debug: Boolean = false,
    ): Boolean {
        val currentSeverity = computeWorstSeverity(allCurrentProblems, failedInstanceNames.size)

        // Build fingerprint set: problems (with status) + fetch failures
        val problemFps  = allCurrentProblems.map { p ->
            p.instanceId + SEP + p.problem.uniqueId + SEP + p.problem.status
        }.toSet()
        val failureFps  = failedInstanceNames.map { "fetch_fail${SEP}$it" }.toSet()
        val currentFps  = problemFps + failureFps

        val prefs        = prefs(context)
        val prevSeverity = prefs.getInt(KEY_SEVERITY, 0)
        val lastSoundMs  = prefs.getLong(KEY_LAST_SOUND, 0L)
        val prevFps      = prefs.getString(KEY_FINGERPRINTS, null)
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

        val newFps             = currentFps - prevFps
        val hasNewFingerprints = newFps.isNotEmpty()
        // True severity escalation only if BOTH are > 0; OK→problems is handled separately
        val isEscalation       = currentSeverity > prevSeverity && prevSeverity > 0
        val isTransitionFromOk = prevSeverity == 0 && currentSeverity > 0

        val shouldPlay = if (currentSeverity == 0) false else shouldPlay(
            hasNewFingerprints = hasNewFingerprints,
            isEscalation       = isEscalation,
            isTransitionFromOk = isTransitionFromOk,
            lastSoundMs        = lastSoundMs,
            settings           = settings,
        )

        if (debug) android.util.Log.d("qNag",
            "[sound] severity=$currentSeverity prev=$prevSeverity " +
            "newFps=${newFps.size} escalation=$isEscalation fromOk=$isTransitionFromOk " +
            "shouldPlay=$shouldPlay mode=${settings.alertSoundMode} " +
            "cooldown=${settings.globalSoundCooldownSeconds}s")

        // In NOTIFICATION_CHANNEL_ONLY mode, skip in-app audio but still evaluate the
        // alert decision so callers (e.g. the foreground service) know whether to pulse
        // the wearable via the notification channel.
        if (settings.alertSoundMode != AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) {
            AlertSoundPlayer.playIfNeeded(context, shouldPlay, settings, debug)
        }

        // Persist new state (always, regardless of sound mode, so fingerprints stay current)
        prefs.edit()
            .putInt(KEY_SEVERITY, currentSeverity)
            .putString(KEY_FINGERPRINTS, currentFps.joinToString(","))
            .also { if (shouldPlay) it.putLong(KEY_LAST_SOUND, System.currentTimeMillis()) }
            .apply()

        return shouldPlay
    }

    /**
     * Reset sound state to "all-clear".
     *
     * Call ONLY when all problems and fetch failures have genuinely cleared, so the next
     * new alert always triggers a sound immediately (escaping any leftover cooldown).
     *
     * Do NOT call this on foreground-service restart or notification updates — that would
     * cause existing unchanged alerts to sound again after every service reload.
     */
    fun resetState(context: Context) {
        prefs(context).edit()
            .putInt(KEY_SEVERITY, 0)
            .putString(KEY_FINGERPRINTS, "")
            // Intentionally do NOT reset KEY_LAST_SOUND so the short escalation cooldown
            // still applies after an all-clear if the same problem immediately reappears.
            .apply()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun computeWorstSeverity(problems: List<ProblemToNotify>, failedCount: Int): Int {
        val hostDown = problems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_DOWN }
        val svcCrit  = problems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_CRITICAL }
        val hostUnr  = problems.count { it.problem is NagiosProblem.HostProblem && it.problem.status == NagiosStatus.HOST_UNREACHABLE }
        val svcWarn  = problems.count { it.problem is NagiosProblem.ServiceProblem && it.problem.status == NagiosStatus.SERVICE_WARNING }
        return when {
            failedCount          > 0 -> 6
            hostDown             > 0 -> 5
            svcCrit              > 0 -> 4
            hostUnr              > 0 -> 3
            svcWarn              > 0 -> 2
            problems.isNotEmpty()    -> 1
            else                     -> 0
        }
    }

    /**
     * Core sound decision — called only when [currentSeverity] > 0.
     *
     * Priority:
     *  1. Escalation / OK→problems: bypass full cooldown, short race-prevention cooldown only.
     *  2. New fingerprints at same severity: apply full global cooldown.
     *  3. No new fingerprints: only when repeatSameProblemSound, apply full cooldown.
     */
    private fun shouldPlay(
        hasNewFingerprints: Boolean,
        isEscalation: Boolean,
        isTransitionFromOk: Boolean,
        lastSoundMs: Long,
        settings: NotificationSettings,
    ): Boolean {
        val now = System.currentTimeMillis()

        // ── Rule 1: severity increase or OK→problems ──────────────────────────
        // This must bypass the full global cooldown so a critical arriving
        // 30 s after a warning is never suppressed.
        if (isEscalation || isTransitionFromOk) {
            return (now - lastSoundMs) >= ESCALATION_COOLDOWN_MS
        }

        // ── Rule 2: new problem fingerprints (same severity) ──────────────────
        // A brand-new problem that wasn't in the previous poll is genuinely new,
        // even if severity didn't change.  Sound after the full cooldown.
        if (hasNewFingerprints) {
            val cooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
            return settings.globalSoundCooldownSeconds <= 0 || (now - lastSoundMs) >= cooldownMs
        }

        // ── Rule 3: unchanged problem set ─────────────────────────────────────
        // repeatSameProblemSound = false (default): do not re-sound.
        // repeatSameProblemSound = true: re-sound after the full cooldown.
        if (!settings.repeatSameProblemSound) return false
        val cooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
        return settings.globalSoundCooldownSeconds <= 0 || (now - lastSoundMs) >= cooldownMs
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
