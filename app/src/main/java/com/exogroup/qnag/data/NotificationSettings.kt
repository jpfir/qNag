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

    // Polling interval — minimum 15 minutes (WorkManager hard floor for background polling)
    val refreshIntervalMinutes: Int = 15,

    // ── Anti-flood / sound cooldown ───────────────────────────────────────────
    // Minimum seconds between any notification sounds across all channels.
    // 0 = no global cooldown.
    val globalSoundCooldownSeconds: Int = 300,
    // Minimum seconds between sounds for the same state/channel (e.g. CRITICAL).
    // 0 = no per-state cooldown.
    val perStateSoundCooldownSeconds: Int = 300,
    // When false (default), the same problem fingerprint never gets another sound
    // notification unless its status changes.  Set true to re-sound on every poll.
    val repeatSameProblemSound: Boolean = false,
)
