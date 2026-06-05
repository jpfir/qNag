package com.exogroup.qnag.data

/**
 * Controls how qNag plays alert sounds for new/worse monitoring states.
 *
 * NOTIFICATION_CHANNEL_ONLY:
 *   Relies entirely on Android notification-channel sound.  The channel sound/importance
 *   is user-controlled and cannot be changed by the app after first creation.  Not reliable
 *   for on-call use if the user or OS has muted the channel.
 *
 * IN_APP_SOUND (default):
 *   qNag plays the selected sound via its own MediaPlayer/Ringtone engine when alert state
 *   worsens.  This is independent of notification-channel importance.  With
 *   [NotificationSettings.useAlarmAudioStream] = true and
 *   [NotificationSettings.playSoundInVibrateMode] = true, the sound plays even in vibrate mode.
 *
 * IN_APP_SOUND_WITH_DND_HELP:
 *   Like IN_APP_SOUND but also guides the user through granting Do-Not-Disturb override access
 *   and setting the alert channel to bypass DND.
 */
enum class AlertSoundMode {
    NOTIFICATION_CHANNEL_ONLY,
    IN_APP_SOUND,
    IN_APP_SOUND_WITH_DND_HELP,
}
