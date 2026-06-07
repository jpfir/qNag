// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Simple in-app event log stored in SharedPreferences.
 *
 * Keeps the last [MAX_EVENTS] entries as a JSON array (newest first).
 * Only problem identifiers, instance names, command kinds, and HTTP status codes
 * are stored — no passwords, cookies, Authorization headers, or credentialed URLs.
 *
 * Safe to share with developers for debugging without exposing secrets.
 */
object EventLog {

    private const val PREFS_NAME = "qnag_event_log"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 500

    private val gson = Gson()

    // ── Levels ────────────────────────────────────────────────────────────────

    const val INFO  = "INFO"
    const val WARN  = "WARN"
    const val ERROR = "ERROR"

    // ── Categories ────────────────────────────────────────────────────────────

    const val CAT_APP        = "app"
    const val CAT_POLLING    = "polling"
    const val CAT_COMMAND    = "command"
    const val CAT_WATCHDOG   = "watchdog"
    const val CAT_SOUND      = "sound"
    const val CAT_NOTIF      = "notification"

    // ── Entry model ───────────────────────────────────────────────────────────

    data class Entry(
        val timestamp: Long,
        val level: String,
        val category: String,
        val message: String,
    )

    // ── Write ─────────────────────────────────────────────────────────────────

    @Synchronized
    fun log(context: Context, level: String, category: String, message: String) {
        val safe = sanitize(message)
        val entry = Entry(System.currentTimeMillis(), level, category, safe)
        val prefs = prefs(context)
        val current = load(prefs).toMutableList()
        current.add(0, entry)               // newest first
        if (current.size > MAX_EVENTS) {
            current.subList(MAX_EVENTS, current.size).clear()
        }
        prefs.edit().putString(KEY_EVENTS, gson.toJson(current)).apply()
    }

    // Convenience helpers

    fun info(context: Context, category: String, message: String) =
        log(context, INFO, category, message)

    fun warn(context: Context, category: String, message: String) =
        log(context, WARN, category, message)

    fun error(context: Context, category: String, message: String) =
        log(context, ERROR, category, message)

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getEntries(context: Context): List<Entry> = load(prefs(context))

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_EVENTS).apply()
    }

    // ── Sharing ───────────────────────────────────────────────────────────────

    fun formatForSharing(entries: List<Entry>): String {
        val header = buildString {
            appendLine("qNag Event Log")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Entries: ${entries.size}")
            appendLine("---")
        }
        val body = entries.joinToString("\n") { e ->
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(e.timestamp))
            "[$ts] [${e.level}] [${e.category}] ${e.message}"
        }
        return header + body
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun load(prefs: android.content.SharedPreferences): List<Entry> {
        val json = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            gson.fromJson<List<Entry>>(json, object : TypeToken<List<Entry>>() {}.type)
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Strip sensitive content and truncate. Call before writing any message. */
    fun sanitize(message: String): String = message
        // Credentialed URLs: https?://user:pass@host
        .replace(Regex("https?://[^/\\s]*@[^/\\s]*"), "[redacted-url]")
        // HTTP auth headers
        .replace(Regex("(?i)Authorization:\\s*\\S[^\\n\\r]*"), "Authorization: [redacted]")
        // Cookie headers
        .replace(Regex("(?i)Cookie:\\s*\\S[^\\n\\r]*"), "Cookie: [redacted]")
        .replace(Regex("(?i)Set-Cookie:\\s*\\S[^\\n\\r]*"), "Set-Cookie: [redacted]")
        // Custom auth header
        .replace(Regex("(?i)X-Auth-Token:\\s*\\S[^\\n\\r]*"), "X-Auth-Token: [redacted]")
        // URL query-string / form-body secrets
        .replace(Regex("(?i)\\b(access_token|password|passwd|token)=[^&\\s\"'#]+"), "[redacted]")
        .take(500)
}
