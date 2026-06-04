package com.exogroup.qnag.data

data class NotificationSettings(
    val notificationsEnabled: Boolean = false,

    // Which problem types trigger a notification
    val notifyOnCriticalServices: Boolean = true,
    val notifyOnWarningServices: Boolean = false,
    val notifyOnUnknownServices: Boolean = true,
    val notifyOnDownHosts: Boolean = true,
    val notifyOnUnreachableHosts: Boolean = true,

    // Behavioural guards
    val notifyOnlyUnacknowledged: Boolean = true,
    val notifyOnlyHardState: Boolean = true,
    val respectDowntime: Boolean = true,

    // Polling interval — minimum 15 minutes (WorkManager hard floor)
    val refreshIntervalMinutes: Int = 15,
)
