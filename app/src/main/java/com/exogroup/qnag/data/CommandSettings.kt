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
    // When true, a foreground service is started from the app UI and keeps polling
    // in the foreground. The user must enable this explicitly; it is NOT auto-started.
    val keepMonitoringActive: Boolean = false,

    // ── Nagios date format for cmd.cgi start_time ─────────────────────────────
    // Nullable so Gson can safely set it to null for pre-existing stored settings;
    // use resolvedDateFormat for actual access — it always returns a non-null value.
    val nagiosDateFormat: NagiosDateFormat? = null,
) {
    /** Returns the configured date format, defaulting to US if not set. */
    val resolvedDateFormat: NagiosDateFormat
        get() = nagiosDateFormat ?: NagiosDateFormat.US
}
