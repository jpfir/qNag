// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import android.content.Context

// U+001F unit separator — cannot appear in UUIDs or Nagios host/service names
private const val SEP = ""

// ── Notification decision model ───────────────────────────────────────────────

/**
 * Rich result from [evaluateNotificationDecision] explaining whether and why a problem
 * should trigger a notification/sound in the current poll cycle.
 */
data class NotificationDecision(
    val shouldNotify: Boolean,
    val reason: NotificationDecisionReason,
    /** How long the alert has existed, in ms (from lastStateChange or ProblemAgeStore). */
    val alertAgeMs: Long? = null,
    /** Remaining ms until the delay threshold is satisfied (when reason = TIER2_WAITING). */
    val notifyAfterMs: Long? = null,
    /** How long the problem has been ACKed, in ms (when ACK suppressed/re-notify). */
    val ackAgeMs: Long? = null,
)

enum class NotificationDecisionReason {
    ALLOWED,
    STATE_DISABLED,
    SOFT_STATE_SUPPRESSED,
    DOWNTIME_SUPPRESSED,
    ACKED_SUPPRESSED,
    ACKED_RENOTIFY_ELIGIBLE,
    TIER2_WAITING,
    TIER2_ELIGIBLE,
}

// ── Main evaluation function ──────────────────────────────────────────────────

/**
 * Evaluate whether [problem] should produce a notification or sound given [settings].
 *
 * Decision order:
 *  1. State enabled check (CRITICAL/WARNING/etc. toggles).
 *  2. Soft-state suppression.
 *  3. Downtime suppression.
 *  4. ACK handling — suppress or allow re-notification after [NotificationSettings.notifyAckedAfterMinutes].
 *  5. Tier 2+ delay — suppress until alert age reaches the configured threshold.
 *  6. Allow.
 *
 * @param context Required for [ProblemAgeStore] / [AckAgeStore] lookups. Pass null to skip
 *   age-store checks (backward-compat path used by [shouldNotify] wrapper).
 */
fun evaluateNotificationDecision(
    instanceId: String,
    problem: NagiosProblem,
    settings: NotificationSettings,
    now: Long = System.currentTimeMillis(),
    context: Context? = null,
): NotificationDecision {

    // ── 1. State enabled ──────────────────────────────────────────────────────
    val statusAllowed = when (problem) {
        is NagiosProblem.ServiceProblem -> when (problem.status) {
            NagiosStatus.SERVICE_CRITICAL -> settings.notifyOnCriticalServices
            NagiosStatus.SERVICE_WARNING  -> settings.notifyOnWarningServices
            NagiosStatus.SERVICE_UNKNOWN  -> settings.notifyOnUnknownServices
            else -> false
        }
        is NagiosProblem.HostProblem -> when (problem.status) {
            NagiosStatus.HOST_DOWN        -> settings.notifyOnDownHosts
            NagiosStatus.HOST_UNREACHABLE -> settings.notifyOnUnreachableHosts
            else -> false
        }
    }
    if (!statusAllowed) return NotificationDecision(false, NotificationDecisionReason.STATE_DISABLED)

    // ── 2. Soft-state ─────────────────────────────────────────────────────────
    if (settings.notifyOnlyHardState && problem.isSoftState)
        return NotificationDecision(false, NotificationDecisionReason.SOFT_STATE_SUPPRESSED)

    // ── 3. Downtime ───────────────────────────────────────────────────────────
    if (settings.respectDowntime && problem.scheduledDowntimeDepth > 0)
        return NotificationDecision(false, NotificationDecisionReason.DOWNTIME_SUPPRESSED)
    if (settings.respectDowntime &&
        problem is NagiosProblem.ServiceProblem &&
        problem.hostScheduledDowntimeDepth > 0
    ) return NotificationDecision(false, NotificationDecisionReason.DOWNTIME_SUPPRESSED)

    // ── 4. ACK handling ───────────────────────────────────────────────────────
    if (problem.acknowledged) {
        if (settings.notifyAckedAfterEnabled && context != null) {
            AckAgeStore.recordIfAbsent(context, instanceId, problem)
            val ackFirstSeen = AckAgeStore.getFirstSeen(context, instanceId, problem)
            if (ackFirstSeen != null) {
                val ackAgeMs = now - ackFirstSeen
                val thresholdMs = settings.notifyAckedAfterMinutes * 60_000L
                if (ackAgeMs >= thresholdMs) {
                    return NotificationDecision(
                        shouldNotify = true,
                        reason = NotificationDecisionReason.ACKED_RENOTIFY_ELIGIBLE,
                        ackAgeMs = ackAgeMs,
                    )
                }
                return NotificationDecision(
                    shouldNotify = false,
                    reason = NotificationDecisionReason.ACKED_SUPPRESSED,
                    ackAgeMs = ackAgeMs,
                    notifyAfterMs = thresholdMs - ackAgeMs,
                )
            }
        }
        // No re-notify enabled, or no context — apply the basic acknowledged guard
        if (settings.notifyOnlyUnacknowledged)
            return NotificationDecision(false, NotificationDecisionReason.ACKED_SUPPRESSED)
        // notifyOnlyUnacknowledged=false: ACKed problems can still notify (fall through)
    }

    // ── 5. Tier 2+ delay ─────────────────────────────────────────────────────
    if (settings.tier2PlusEnabled) {
        val alertAgeMs = resolveAlertAgeMs(instanceId, problem, now, context)
        val requiredMs  = tier2DelayMs(problem, settings)
        if (alertAgeMs != null && alertAgeMs < requiredMs) {
            return NotificationDecision(
                shouldNotify  = false,
                reason        = NotificationDecisionReason.TIER2_WAITING,
                alertAgeMs    = alertAgeMs,
                notifyAfterMs = requiredMs - alertAgeMs,
            )
        }
        return NotificationDecision(
            shouldNotify = true,
            reason       = NotificationDecisionReason.TIER2_ELIGIBLE,
            alertAgeMs   = alertAgeMs,
        )
    }

    // ── 6. Allow ──────────────────────────────────────────────────────────────
    return NotificationDecision(true, NotificationDecisionReason.ALLOWED)
}

// ── Tier 2+ helpers ───────────────────────────────────────────────────────────

/**
 * Required alert duration in ms before Tier 2+ allows a notification.
 * Uses per-state delays when [NotificationSettings.tier2PlusUsePerStateDelays] is true.
 */
fun tier2DelayMs(problem: NagiosProblem, settings: NotificationSettings): Long {
    if (!settings.tier2PlusUsePerStateDelays) return settings.tier2PlusDelayMinutes * 60_000L
    return when {
        problem is NagiosProblem.HostProblem && problem.status == NagiosStatus.HOST_DOWN ->
            settings.tier2HostDownDelayMinutes * 60_000L
        problem is NagiosProblem.HostProblem ->
            settings.tier2HostUnreachableDelayMinutes * 60_000L
        problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_CRITICAL ->
            settings.tier2ServiceCriticalDelayMinutes * 60_000L
        problem is NagiosProblem.ServiceProblem && problem.status == NagiosStatus.SERVICE_WARNING ->
            settings.tier2ServiceWarningDelayMinutes * 60_000L
        else -> settings.tier2ServiceUnknownDelayMinutes * 60_000L
    }
}

/**
 * Compute alert age in ms using:
 *  1. [NagiosProblem.lastStateChange] if available from Nagios.
 *  2. [ProblemAgeStore] local first-seen timestamp.
 *  3. null if neither is available — callers treat null as "unknown age = allow notification".
 */
fun resolveAlertAgeMs(
    instanceId: String,
    problem: NagiosProblem,
    now: Long,
    context: Context?,
): Long? {
    problem.lastStateChange?.let { return now - it }
    if (context != null) {
        ProblemAgeStore.getFirstSeen(context, instanceId, problem)?.let { return now - it }
    }
    return null
}

// ── Backward-compat wrapper ───────────────────────────────────────────────────

/**
 * Returns true if [problem] should trigger a notification given [settings].
 *
 * This wrapper calls [evaluateNotificationDecision] without context so age stores
 * are not queried.  Tier 2+ delay falls back to [NagiosProblem.lastStateChange] only.
 * Existing callers that do not have a [Context] can continue using this function.
 */
fun shouldNotify(problem: NagiosProblem, settings: NotificationSettings): Boolean =
    evaluateNotificationDecision("", problem, settings, System.currentTimeMillis(), null).shouldNotify

// ── Fingerprint / ID helpers ──────────────────────────────────────────────────

/** Short status string used in notification titles. */
fun notificationStatusLabel(problem: NagiosProblem): String = when (problem) {
    is NagiosProblem.ServiceProblem -> when (problem.status) {
        NagiosStatus.SERVICE_CRITICAL -> "CRITICAL"
        NagiosStatus.SERVICE_WARNING  -> "WARNING"
        NagiosStatus.SERVICE_UNKNOWN  -> "UNKNOWN"
        else -> "STATUS ${problem.status}"
    }
    is NagiosProblem.HostProblem -> when (problem.status) {
        NagiosStatus.HOST_DOWN        -> "DOWN"
        NagiosStatus.HOST_UNREACHABLE -> "UNREACHABLE"
        else -> "STATUS ${problem.status}"
    }
}

/**
 * Stable fingerprint: instanceId + U+001F + uniqueId + U+001F + status.
 * Status is included so WARNING→CRITICAL is a new fingerprint, triggering the
 * severity-escalation sound rule.
 */
fun problemFingerprint(instanceId: String, problem: NagiosProblem): String =
    "$instanceId$SEP${problem.uniqueId}$SEP${problem.status}"

/** Prefix for all fingerprints belonging to one instance. Use with startsWith(). */
fun instanceFingerprintPrefix(instanceId: String): String = "$instanceId$SEP"

/** Stable Android notification ID for a problem (no status — same slot regardless of state change). */
fun notificationId(instanceId: String, problem: NagiosProblem): Int =
    ("$instanceId$SEP${problem.uniqueId}").hashCode().let { if (it < 0) it.inv() else it }

/** Notification ID for a per-instance fetch-failure alert. */
fun fetchFailureNotificationId(instanceId: String): Int =
    ("fetch_fail$SEP$instanceId").hashCode().let { if (it < 0) it.inv() else it }
