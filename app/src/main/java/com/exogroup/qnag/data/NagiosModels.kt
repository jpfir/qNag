package com.exogroup.qnag.data

import java.util.UUID

data class NagiosInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    // New fields — backward-compat deserialization is handled in SecureInstanceStore
    val enabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
)

object NagiosStatus {
    // Service status bit flags from Nagios statusjson.cgi
    const val SERVICE_OK = 2
    const val SERVICE_WARNING = 4
    const val SERVICE_UNKNOWN = 8
    const val SERVICE_CRITICAL = 16

    const val HOST_UP = 2
    const val HOST_DOWN = 4
    const val HOST_UNREACHABLE = 8
}

data class NagiosStatusSummary(
    val instanceId: String,
    val hostTotal: Int?,
    val hostUp: Int?,
    val hostDown: Int,
    val hostUnreachable: Int,
    val serviceTotal: Int?,
    val serviceOk: Int?,
    val serviceCritical: Int,
    val serviceWarning: Int,
    val serviceUnknown: Int,
    val fetchedAt: Long,
)

sealed class NagiosProblem {
    abstract val hostName: String
    abstract val pluginOutput: String
    abstract val status: Int
    abstract val uniqueId: String

    // Extended metadata — parsed from statusjson.cgi where available; defaults used otherwise.
    abstract val acknowledged: Boolean
    abstract val notificationsEnabled: Boolean
    abstract val checksEnabled: Boolean
    abstract val scheduledDowntimeDepth: Int
    abstract val isFlapping: Boolean
    abstract val isSoftState: Boolean    // state_type == 0 / "SOFT"

    // Set by NagiosApi.fetchProblems() so the dashboard and notifications know the source.
    // Empty string in single-instance mode for backward compatibility.
    open val instanceId: String get() = ""
    open val instanceName: String get() = ""

    // ── Nagios check timing metadata ──────────────────────────────────────────
    // Parsed from statusjson.cgi where available; null when Nagios does not return the field.
    // All epoch timestamps are in milliseconds.
    open val lastCheck: Long? get() = null
    open val nextCheck: Long? get() = null
    open val lastStateChange: Long? get() = null
    open val lastHardStateChange: Long? get() = null
    open val currentAttempt: Int? get() = null
    open val maxAttempts: Int? get() = null
    /**
     * Last check result type — "active", "passive", "parent", "file", "other", or null.
     * This is the type of the most recent check result, not the configured check mode.
     * A passive-only service may show "active" if it was last checked actively.
     */
    open val checkType: String? get() = null
    /** Whether passive check results are accepted (null = not reported by this Nagios version). */
    open val passiveChecksEnabled: Boolean? get() = null
    /** Whether freshness checking is enabled for this object (null = not reported). */
    open val freshnessChecksEnabled: Boolean? get() = null
    /** Freshness threshold in seconds (null = not applicable or not reported). */
    open val freshnessThresholdSeconds: Int? get() = null
    /** Who acknowledged this problem, if available from Nagios statusjson. */
    open val acknowledgedBy: String? get() = null
    open val acknowledgementComment: String? get() = null
    /** Epoch milliseconds when the ACK was submitted, as reported by Nagios. Null = not available. */
    open val acknowledgementTime: Long? get() = null

    data class ServiceProblem(
        override val hostName: String,
        val serviceName: String,
        override val pluginOutput: String,
        override val status: Int,
        override val acknowledged: Boolean = false,
        override val notificationsEnabled: Boolean = true,
        override val checksEnabled: Boolean = true,
        override val scheduledDowntimeDepth: Int = 0,
        override val isFlapping: Boolean = false,
        override val isSoftState: Boolean = false,
        val hostStatus: Int? = null,
        val hostAcknowledged: Boolean = false,
        val hostScheduledDowntimeDepth: Int = 0,
        override val instanceId: String = "",
        override val instanceName: String = "",
        // Check timing
        override val lastCheck: Long? = null,
        override val nextCheck: Long? = null,
        override val lastStateChange: Long? = null,
        override val lastHardStateChange: Long? = null,
        override val currentAttempt: Int? = null,
        override val maxAttempts: Int? = null,
        override val checkType: String? = null,
        override val passiveChecksEnabled: Boolean? = null,
        override val freshnessChecksEnabled: Boolean? = null,
        override val freshnessThresholdSeconds: Int? = null,
        override val acknowledgedBy: String? = null,
        override val acknowledgementComment: String? = null,
        override val acknowledgementTime: Long? = null,
    ) : NagiosProblem() {
        override val uniqueId: String get() = "service|$hostName|$serviceName"
    }

    data class HostProblem(
        override val hostName: String,
        override val pluginOutput: String,
        override val status: Int,
        override val acknowledged: Boolean = false,
        override val notificationsEnabled: Boolean = true,
        override val checksEnabled: Boolean = true,
        override val scheduledDowntimeDepth: Int = 0,
        override val isFlapping: Boolean = false,
        override val isSoftState: Boolean = false,
        override val instanceId: String = "",
        override val instanceName: String = "",
        // Check timing
        override val lastCheck: Long? = null,
        override val nextCheck: Long? = null,
        override val lastStateChange: Long? = null,
        override val lastHardStateChange: Long? = null,
        override val currentAttempt: Int? = null,
        override val maxAttempts: Int? = null,
        override val checkType: String? = null,
        override val passiveChecksEnabled: Boolean? = null,
        override val freshnessChecksEnabled: Boolean? = null,
        override val freshnessThresholdSeconds: Int? = null,
        override val acknowledgedBy: String? = null,
        override val acknowledgementComment: String? = null,
        override val acknowledgementTime: Long? = null,
    ) : NagiosProblem() {
        override val uniqueId: String get() = "host|$hostName"
    }
}
