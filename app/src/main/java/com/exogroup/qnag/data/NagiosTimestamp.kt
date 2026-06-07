// SPDX-License-Identifier: GPL-3.0-or-later
package com.exogroup.qnag.data

/**
 * Convert a raw JSON value from Nagios statusjson to epoch milliseconds.
 *
 * Nagios returns timestamps as epoch seconds (10-digit) or occasionally epoch millis (13-digit).
 * Handles Int, Long, Double, and numeric String gracefully.
 * Returns null for missing, zero, or unparseable values.
 *
 * Extracted from NagiosApi so it can be unit-tested without a network layer.
 */
internal fun parseEpochMs(raw: Any?): Long? {
    val num: Long = when (raw) {
        null      -> return null
        is Int    -> raw.toLong()
        is Long   -> raw
        is Double -> raw.toLong()
        is String -> raw.toLongOrNull() ?: return null
        else      -> return null
    }
    if (num <= 0) return null
    // 10-digit = epoch seconds; values below ~year 2286 in seconds are treated as seconds
    return if (num < 10_000_000_000L) num * 1000L else num
}
