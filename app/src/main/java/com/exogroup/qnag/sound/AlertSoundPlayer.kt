package com.exogroup.qnag.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NotificationSettings

/**
 * In-app alert sound engine.
 *
 * Plays the user-selected ringtone (or default alarm as fallback) independently
 * of Android notification-channel settings, which can be muted by the user or OS.
 *
 * Thread-safe: may be called from service or WorkManager coroutines.
 *
 * Sound selection priority:
 *  1. settings.inAppSoundUri if present and valid
 *  2. System default alarm (TYPE_ALARM)
 *  3. System default ringtone (TYPE_RINGTONE) — last resort
 *
 * If the selected URI fails to play, falls back to default alarm and logs a warning.
 * Never crashes the polling loop.
 */
object AlertSoundPlayer {

    @Volatile private var currentRingtone: android.media.Ringtone? = null

    /**
     * Play alert sound if [shouldPlay] is true and settings allow it.
     * Stops any currently playing sound before starting.
     */
    fun playIfNeeded(
        context: Context,
        shouldPlay: Boolean,
        settings: NotificationSettings,
        debug: Boolean = false,
    ) {
        if (!shouldPlay) return
        if (settings.alertSoundMode == AlertSoundMode.NOTIFICATION_CHANNEL_ONLY) {
            if (debug) android.util.Log.d("qNag", "[sound] mode=CHANNEL_ONLY, skipping in-app sound")
            return
        }

        // Ringer-mode check (only when vibrate bypass is disabled)
        if (!settings.playSoundInVibrateMode || !settings.useAlarmAudioStream) {
            val am = context.getSystemService(AudioManager::class.java)
            val mode = am?.ringerMode
            if (mode == AudioManager.RINGER_MODE_VIBRATE || mode == AudioManager.RINGER_MODE_SILENT) {
                if (debug) android.util.Log.d("qNag", "[sound] skipping — ringer mode=$mode, bypass disabled")
                return
            }
        }

        stop()  // prevent overlapping sounds

        val usage = if (settings.useAlarmAudioStream) AudioAttributes.USAGE_ALARM
                    else AudioAttributes.USAGE_NOTIFICATION_EVENT

        if (debug) android.util.Log.d("qNag",
            "[sound] playing — customUri=${settings.inAppSoundUri != null} " +
            "vibrateBypass=${settings.playSoundInVibrateMode} usage=$usage")

        // Try custom URI first, fall back to default alarm
        val played = settings.inAppSoundUri?.let { uriStr ->
            tryPlay(context, Uri.parse(uriStr), usage, debug, label = "custom")
        } ?: false

        if (!played) {
            if (settings.inAppSoundUri != null && debug)
                android.util.Log.w("qNag", "[sound] custom URI failed, falling back to default alarm")
            val defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (defaultAlarm != null) tryPlay(context, defaultAlarm, usage, debug, label = "default-alarm")
            else if (debug) android.util.Log.w("qNag", "[sound] no fallback URI available")
        }
    }

    /** Stop any currently playing alert sound. */
    fun stop() {
        try { currentRingtone?.stop() } catch (_: Exception) { }
        currentRingtone = null
    }

    /** Returns true if an alert sound is currently playing. */
    fun isPlaying(): Boolean = currentRingtone?.isPlaying == true

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun tryPlay(context: Context, uri: Uri, usage: Int, debug: Boolean, label: String): Boolean {
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: run {
                if (debug) android.util.Log.w("qNag", "[sound] getRingtone returned null for $label")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            currentRingtone = ringtone
            ringtone.play()
            if (debug) android.util.Log.d("qNag", "[sound] started playing ($label) uri=$uri")
            true
        } catch (e: Exception) {
            if (debug) android.util.Log.w("qNag", "[sound] play failed ($label): ${e.javaClass.simpleName}")
            false
        }
    }
}

// ── Legacy helper kept for code that calls it directly ───────────────────────
// New code should use AlertSoundController.evaluateAndPlay() instead.

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
