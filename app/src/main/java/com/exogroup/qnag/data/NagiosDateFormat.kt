package com.exogroup.qnag.data

/**
 * Date format used when submitting start_time to Nagios cmd.cgi.
 *
 * The correct value depends on the `date_format` setting in nagios.cfg:
 *   us             → MM-dd-yyyy HH:mm:ss   (default on most Nagios XI installs)
 *   euro           → dd-MM-yyyy HH:mm:ss
 *   iso8601        → yyyy-MM-dd HH:mm:ss
 *   strict-iso8601 → yyyy-MM-dd'T'HH:mm:ss
 *
 * TODO: If recheck commands are silently ignored, check nagios.cfg date_format and
 *   change this setting to match.
 */
enum class NagiosDateFormat(val pattern: String) {
    US("MM-dd-yyyy HH:mm:ss"),
    EURO("dd-MM-yyyy HH:mm:ss"),
    ISO8601("yyyy-MM-dd HH:mm:ss"),
    STRICT_ISO8601("yyyy-MM-dd'T'HH:mm:ss"),
}
