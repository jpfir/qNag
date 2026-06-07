// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import android.content.Context

/**
 * Tracks first-seen timestamps for (instanceId, problem uniqueId, status) triples.
 *
 * Used by Tier 2+ notification delay when Nagios does not return last_state_change,
 * so the alert age is tracked locally from the first time qNag observed this problem.
 *
 * Key format: instanceId + U+001F + uniqueId + U+001F + status
 * Only identifiers are stored — no passwords, URLs, or credentials.
 */
object ProblemAgeStore {

    private const val PREFS_NAME  = "qnag_problem_ages"
    private const val MAX_AGE_MS  = 7L * 24 * 60 * 60 * 1_000  // prune entries older than 7 days
    private const val SEP         = ""

    fun key(instanceId: String, problem: NagiosProblem): String =
        "$instanceId$SEP${problem.uniqueId}$SEP${problem.status}"

    /**
     * Record the problem as first-seen now if no entry exists.
     * Returns the first-seen timestamp (existing or newly stored).
     */
    fun recordIfAbsent(context: Context, instanceId: String, problem: NagiosProblem): Long {
        val k = key(instanceId, problem)
        val prefs = prefs(context)
        val existing = prefs.getLong(k, -1L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        prefs.edit().putLong(k, now).apply()
        return now
    }

    /** Returns the stored first-seen timestamp, or null if not recorded. */
    fun getFirstSeen(context: Context, instanceId: String, problem: NagiosProblem): Long? {
        val v = prefs(context).getLong(key(instanceId, problem), -1L)
        return if (v > 0L) v else null
    }

    /**
     * Remove entries for problems no longer in the active set, and prune entries
     * older than [MAX_AGE_MS].  Call once per poll cycle to keep storage bounded.
     */
    fun pruneStale(context: Context, activeKeys: Set<String>) {
        val prefs = prefs(context)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        @Suppress("UNCHECKED_CAST")
        val all = prefs.all as Map<String, Any>
        val toRemove = all.entries
            .filter { (k, v) -> k !in activeKeys || (v as? Long ?: 0L) < cutoff }
            .map { it.key }
        if (toRemove.isNotEmpty()) {
            prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
