// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import android.content.Context

/**
 * Tracks when a problem was first observed as acknowledged by qNag.
 *
 * Used for the "notify ACKed alerts again after X minutes" feature.
 * When [NagiosProblem.acknowledgementTime] is present in the Nagios response, that
 * is used as the ACK timestamp; otherwise this store provides a local fallback.
 *
 * Key format: instanceId + U+001F + problem.uniqueId  (no status — ACK persists across status changes)
 * Only identifiers are stored — no passwords, URLs, or credentials.
 */
object AckAgeStore {

    private const val PREFS_NAME = "qnag_ack_ages"
    private const val SEP        = ""

    fun key(instanceId: String, problem: NagiosProblem): String =
        "$instanceId$SEP${problem.uniqueId}"

    /**
     * Record the problem as first-seen-acknowledged now if no entry exists.
     * If [NagiosProblem.acknowledgementTime] is non-null, that timestamp is used
     * as the canonical ACK time (not overwritten by local store).
     */
    fun recordIfAbsent(context: Context, instanceId: String, problem: NagiosProblem): Long {
        val k = key(instanceId, problem)
        val prefs = prefs(context)
        val existing = prefs.getLong(k, -1L)
        if (existing > 0L) return existing
        val ackTime = problem.acknowledgementTime ?: System.currentTimeMillis()
        prefs.edit().putLong(k, ackTime).apply()
        return ackTime
    }

    /**
     * Returns the ACK first-seen timestamp:
     *  1. [NagiosProblem.acknowledgementTime] if reported by Nagios.
     *  2. Locally stored first-seen-ACKed time.
     *  3. null if neither is available.
     */
    fun getFirstSeen(context: Context, instanceId: String, problem: NagiosProblem): Long? {
        problem.acknowledgementTime?.let { return it }
        val v = prefs(context).getLong(key(instanceId, problem), -1L)
        return if (v > 0L) v else null
    }

    /** Remove the ACK entry — call when the problem is no longer acknowledged. */
    fun remove(context: Context, instanceId: String, problem: NagiosProblem) {
        prefs(context).edit().remove(key(instanceId, problem)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
