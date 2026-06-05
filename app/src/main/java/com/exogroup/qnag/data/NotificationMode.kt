package com.exogroup.qnag.data

/**
 * Controls how qNag posts Android notifications during a poll cycle.
 *
 * SUMMARY_ONLY (default):
 *   A single persistent notification is updated in place every poll cycle.
 *   Title: "qNag: 3 critical, 8 warning" or "qNag: all green".
 *   Body:  per-instance compact counts.
 *   Sound: plays once when severity increases or new problems appear, then respects cooldown.
 *   This is the recommended mode — it avoids flooding the notification shade.
 *
 * GROUPED_DETAILS:
 *   Like SUMMARY_ONLY but intends to add grouped child notifications per state.
 *   Currently falls back to SUMMARY_ONLY behaviour (TODO).
 *
 * PER_PROBLEM:
 *   Legacy / noisy mode.  One Android notification per problem, per state channel.
 *   Equivalent to the old notifyBatch() behaviour.  Use if you want the full detail
 *   but accept a noisy notification shade.
 */
enum class NotificationMode {
    SUMMARY_ONLY,
    GROUPED_DETAILS,
    PER_PROBLEM,
}
