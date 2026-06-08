package com.exogroup.qnag.data

data class NotificationSettings(
    val notificationsEnabled: Boolean = true,

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

    // ── Notification display mode ──────────────────────────────────────────────
    // SUMMARY_ONLY (default): one compact summary notification updated in place.
    // PER_PROBLEM: one notification per problem (legacy / noisy).
    val notificationMode: NotificationMode = NotificationMode.SUMMARY_ONLY,

    // ── In-app alert sound engine ─────────────────────────────────────────────
    // Governs how qNag produces sound when alert state worsens.
    val alertSoundMode: AlertSoundMode = AlertSoundMode.IN_APP_SOUND,
    // When true, uses USAGE_ALARM AudioAttributes so sound plays even in vibrate mode.
    val playSoundInVibrateMode: Boolean = true,
    // When true, uses the ALARM audio stream which bypasses ringer mode.
    val useAlarmAudioStream: Boolean = true,
    // URI of the alert ringtone/alarm.  null = system default alarm sound.
    // Persisted as a string; the app must hold a persistable URI permission.
    val inAppSoundUri: String? = null,
    // When true, show DND-access setup guidance in health section.
    val helpBypassDnd: Boolean = false,

    // ── Tier 2+ notification delay ────────────────────────────────────────────
    // Keeps alerts visible in the dashboard immediately but delays Android alerting/sound
    // until the problem has lasted at least the configured time.
    // Useful to avoid spurious alerts from short transient problems.
    val tier2PlusEnabled: Boolean = false,
    // Global delay applied to all states (when tier2PlusUsePerStateDelays = false).
    val tier2PlusDelayMinutes: Int = 5,
    // When true, each problem state has its own delay threshold.
    val tier2PlusUsePerStateDelays: Boolean = false,
    val tier2HostDownDelayMinutes: Int = 5,
    val tier2HostUnreachableDelayMinutes: Int = 5,
    val tier2ServiceCriticalDelayMinutes: Int = 5,
    val tier2ServiceWarningDelayMinutes: Int = 15,
    val tier2ServiceUnknownDelayMinutes: Int = 10,

    // ── ACKed alert re-notification ───────────────────────────────────────────
    // ACKed alerts are normally quiet.  Enable to notify again when an ACKed alert
    // has remained active for too long — indicating the ACK may be stale.
    val notifyAckedAfterEnabled: Boolean = false,
    val notifyAckedAfterMinutes: Int = 120,

    // ── Alert sound duration cap ──────────────────────────────────────────────
    // qNag stops alert sounds automatically after this many seconds.
    // Prevents looping/long alarm tones from ringing indefinitely.
    // Clamped to 1..60 at play time; stored as-is.
    val maxAlertSoundSeconds: Int = 10,
)
