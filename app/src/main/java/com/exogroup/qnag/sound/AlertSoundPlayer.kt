package com.exogroup.qnag.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NotificationSettings
import com.exogroup.qnag.notifications.ProblemToNotify

/**
 * In-app alert sound engine for qNag reliability mode.
 *
 * Plays a configurable sound (default: system alarm) when alert state worsens —
 * independently of Android notification-channel sound settings, which the user
 * can mute/disable.  This provides more reliable on-call alerting.
 *
 * Thread-safe: may be called from the foreground-service polling coroutine.
 *
 * Sound decision logic:
 *  - Plays when new/worse problems arrive (delegated to the caller via [shouldPlay]).
 *  - Does NOT play if the same unchanged problems persist every poll.
 *  - Many new problems in one poll produce ONE sound event.
 *  - Respects globalSoundCooldownSeconds.
 *
 * Vibrate-mode behaviour:
 *  When [NotificationSettings.playSoundInVibrateMode] = true and
 *  [NotificationSettings.useAlarmAudioStream] = true, the sound uses USAGE_ALARM
 *  which bypasses ringer/vibrate mode on most Android devices.
 */
object AlertSoundPlayer {

    @Volatile private var currentRingtone: android.media.Ringtone? = null

    /**
     * Play the alert sound if [shouldPlay] is true and settings allow it.
     *
     * @param shouldPlay  Determined by the caller (e.g. new/worse problems appeared).
     */
    fun playIfNeeded(context: Context, shouldPlay: Boolean, settings: NotificationSettings) {
        if (!shouldPlay) return
        if (settings.alertSoundMode == AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) return

        // Check ringer mode when vibrate-bypass is disabled
        if (!settings.playSoundInVibrateMode || !settings.useAlarmAudioStream) {
            val am = context.getSystemService(AudioManager::class.java)
            if (am?.ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
                am?.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return  // respect user's ringer mode when bypass is off
            }
        }

        stop()  // cancel any currently playing alert before starting a new one

        val uri: Uri = settings.inAppSoundUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        try {
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val usage = if (settings.useAlarmAudioStream)
                    AudioAttributes.USAGE_ALARM
                else
                    AudioAttributes.USAGE_NOTIFICATION_EVENT

                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }

            currentRingtone = ringtone
            ringtone.play()
        } catch (_: Exception) {
            // Never crash the polling loop on audio errors
        }
    }

    /** Stop any currently playing alert sound. */
    fun stop() {
        try {
            currentRingtone?.stop()
        } catch (_: Exception) { }
        currentRingtone = null
    }

    /** Returns true if an alert sound is currently playing. */
    fun isPlaying(): Boolean = currentRingtone?.isPlaying == true
}

/** Convenience: compute whether sound should play given new-problems list and settings. */
fun shouldPlayAlertSound(
    hasNewProblems: Boolean,
    currentWorstSeverity: Int,
    prevWorstSeverity: Int,
    lastSoundMs: Long,
    settings: NotificationSettings,
): Boolean {
    if (currentWorstSeverity == 0) return false
    val now = System.currentTimeMillis()
    val cooldownMs = settings.globalSoundCooldownSeconds.toLong() * 1000L
    if (settings.globalSoundCooldownSeconds > 0 && (now - lastSoundMs) < cooldownMs) return false
    return currentWorstSeverity > prevWorstSeverity ||
           (hasNewProblems && prevWorstSeverity == 0) ||
           (hasNewProblems && settings.repeatSameProblemSound)
}
