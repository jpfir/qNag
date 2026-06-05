package com.exogroup.qnag.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Short-lived SharedPreferences cache of recently-ACKed problem keys.
 *
 * Purpose: prevent the background worker and foreground service from re-notifying
 * problems that were just ACKed from the UI before Nagios has confirmed the ACK.
 *
 * Keys:   instanceId + U+001F + problem.uniqueId
 * Value:  epoch ms when the ACK was submitted
 * TTL:    ACK_SUPPRESS_TTL_MS (5 minutes, matches local-ACK overlay TTL)
 *
 * Only problem identifiers are stored here — no passwords, URLs, or credentials.
 */
object AckSuppressCache {

    private const val PREFS_NAME = "qnag_polling_state"  // shared with fingerprint store
    private const val KEY = "ack_suppress"
    private const val ACK_SUPPRESS_TTL_MS = 5 * 60 * 1_000L

    private val gson = Gson()

    fun suppressKey(instanceId: String, problem: NagiosProblem): String =
        "$instanceId${problem.uniqueId}"

    /** Write all [keys] to the suppress cache with the current timestamp. */
    fun recordAcked(context: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        val prefs = prefs(context)
        val existing = load(prefs)
        val now = System.currentTimeMillis()
        val updated = existing + keys.associateWith { now }
        prefs.edit().putString(KEY, gson.toJson(updated)).apply()
    }

    /** Returns true if this problem was recently ACKed from the UI and the TTL has not expired. */
    fun isSuppressed(context: Context, instanceId: String, problem: NagiosProblem): Boolean {
        val key = suppressKey(instanceId, problem)
        val ts = load(prefs(context))[key] ?: return false
        return (System.currentTimeMillis() - ts) < ACK_SUPPRESS_TTL_MS
    }

    /**
     * Evict expired entries.  Call once per poll cycle to keep the store small.
     * No-op if nothing has expired.
     */
    fun evictExpired(context: Context) {
        val prefs = prefs(context)
        val existing = load(prefs)
        if (existing.isEmpty()) return
        val cutoff = System.currentTimeMillis() - ACK_SUPPRESS_TTL_MS
        val pruned = existing.filter { (_, ts) -> ts > cutoff }
        if (pruned.size < existing.size) {
            prefs.edit().putString(KEY, gson.toJson(pruned)).apply()
        }
    }

    private fun load(prefs: android.content.SharedPreferences): Map<String, Long> {
        val json = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
                ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
