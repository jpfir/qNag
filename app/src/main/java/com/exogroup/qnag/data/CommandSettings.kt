package com.exogroup.qnag.data

data class CommandSettings(
    // ── ACK defaults ──────────────────────────────────────────────────────────
    val defaultAckMessage: String = "Not critical or being worked on",
    val ackAuthor: String = "qNag",
    val ackSticky: Boolean = true,
    val ackNotify: Boolean = false,
    val ackPersistent: Boolean = false,

    // ── Background notification behaviour ────────────────────────────────────
    val notifyOnFetchFailure: Boolean = true,
    val notifyOnlyNewProblems: Boolean = true,

    // ── UX hints ─────────────────────────────────────────────────────────────
    val showBatteryOptimizationHint: Boolean = true,

    // ── Foreground monitoring service ────────────────────────────────────────
    val keepMonitoringActive: Boolean = false,
    // Interval for the foreground service polling — independent of WorkManager's 15-min minimum.
    val foregroundPollingIntervalSeconds: Int = 60,

    // ── Nagios date format for cmd.cgi start_time ─────────────────────────────
    // Nullable so Gson can safely set it to null for pre-existing stored settings.
    // resolvedDateFormat always returns a non-null value.
    val nagiosDateFormat: NagiosDateFormat? = null,

    // ── ACK cascade ───────────────────────────────────────────────────────────
    // When true, ACKing a host also ACKs all current unacknowledged service problems
    // on the same host (same instance).  Useful when a host is down and many services
    // are alerting because of it.
    val ackServicesOnHostAck: Boolean = true,

    // ── Stale monitoring alert ────────────────────────────────────────────────
    // If no successful poll completes within this window, post a "monitoring stale" notification.
    val monitoringStaleThresholdMinutes: Int = 5,
    val staleMonitoringAlertEnabled: Boolean = true,

    // ── Diagnostics ───────────────────────────────────────────────────────────
    // When true, logs safe command submission info (field names, HTTP status, sanitized
    // response snippet) to help diagnose recheck/ACK failures.
    // NEVER logs passwords, Authorization headers, or cookie values.
    val debugCommandSubmission: Boolean = false,
) {
    /**
     * Returns the configured date format.
     * Defaults to ISO8601 ("yyyy-MM-dd HH:mm:ss") to match qNagstamon behavior —
     * this is the format used by Nagios installations with date_format=iso8601 in nagios.cfg,
     * which is the most common default.  If your Nagios uses a different format, change this
     * setting to match the `date_format` value in nagios.cfg.
     */
    val resolvedDateFormat: NagiosDateFormat
        get() = nagiosDateFormat ?: NagiosDateFormat.ISO8601
}
