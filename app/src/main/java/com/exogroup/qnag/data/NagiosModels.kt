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

    // TODO: Verify exact host status values against your Nagios statusjson.cgi response.
    const val HOST_UP = 2
    const val HOST_DOWN = 4
    const val HOST_UNREACHABLE = 8
}

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
        // TODO: Nagios statusjson may not include host_status in service details — check your version.
        val hostStatus: Int? = null,
        val hostAcknowledged: Boolean = false,
        val hostScheduledDowntimeDepth: Int = 0,
        override val instanceId: String = "",
        override val instanceName: String = "",
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
    ) : NagiosProblem() {
        override val uniqueId: String get() = "host|$hostName"
    }
}
