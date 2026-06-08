package com.exogroup.qnag.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Normalises a user-supplied Nagios instance URL to a canonical CGI base, regardless of whether
 * the user (or an imported qNagstamon config) supplied the site root, /nagios, /nagios/cgi-bin,
 * or /cgi-bin.
 *
 * Rules applied to the path segments (query and fragment are always stripped):
 *  - Path contains a "cgi-bin" segment → keep everything up to and including it.
 *  - Path contains a "nagios" segment but no "cgi-bin" → keep up through "nagios", append "/cgi-bin".
 *  - Path is a bare host root (no recognisable prefix) → append "/nagios/cgi-bin".
 *
 * Accepted input examples:
 *   https://nagios.example.com              → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/             → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/nagios       → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/nagios/      → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/nagios/cgi-bin   → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/nagios/cgi-bin/  → https://nagios.example.com/nagios/cgi-bin
 *   https://nagios.example.com/cgi-bin          → https://nagios.example.com/cgi-bin
 *   https://nagios.example.com/cgi-bin/         → https://nagios.example.com/cgi-bin
 *
 * Throws [IllegalArgumentException] (from OkHttp) for malformed URLs; callers can let this
 * propagate as a connection failure — the user will see an HTTP error rather than a crash.
 */
object NagiosUrl {

    fun cgiBase(configuredUrl: String): String {
        val parsed = configuredUrl.trim().toHttpUrl()

        // pathSegments always has ≥1 element; trailing slashes produce trailing empty strings.
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }

        val newPath = run {
            val cgiIdx = segments.indexOfFirst { it.equals("cgi-bin", ignoreCase = true) }
            if (cgiIdx >= 0) {
                "/" + segments.take(cgiIdx + 1).joinToString("/")
            } else {
                val nagiosIdx = segments.indexOfFirst { it.equals("nagios", ignoreCase = true) }
                if (nagiosIdx >= 0) {
                    "/" + segments.take(nagiosIdx + 1).joinToString("/") + "/cgi-bin"
                } else {
                    val prefix = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
                    "$prefix/nagios/cgi-bin"
                }
            }
        }

        return parsed.newBuilder()
            .encodedPath(newPath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    fun cgi(configuredUrl: String, script: String): String =
        "${cgiBase(configuredUrl)}/${script.trimStart('/')}"
}
