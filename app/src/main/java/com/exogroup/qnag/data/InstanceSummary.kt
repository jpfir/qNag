package com.exogroup.qnag.data

/**
 * Per-instance status snapshot used by the dashboard summary panel.
 *
 * Built from the raw problem list after each fetch; one entry per enabled instance.
 * Instances that failed their last fetch are included with [fetchError] set and the
 * last-known counts preserved so the summary panel stays informative.
 */
data class InstanceSummary(
    val instanceId: String,
    val instanceName: String,
    val enabled: Boolean,
    val notificationsEnabled: Boolean,
    /** Epoch ms of the last successful fetch; null if never fetched or last fetch failed. */
    val lastUpdated: Long?,
    /** Non-null when the most recent fetch attempt failed. Sanitized (no credentials). */
    val fetchError: String?,
    val hostDown: Int,
    val hostUnreachable: Int,
    val hostAcked: Int,
    val serviceCritical: Int,
    val serviceWarning: Int,
    val serviceUnknown: Int,
    val serviceAcked: Int,
    val totalProblems: Int,
)
