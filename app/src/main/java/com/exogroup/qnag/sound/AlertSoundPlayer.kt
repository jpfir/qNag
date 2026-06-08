package com.exogroup.qnag.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.exogroup.qnag.R
import com.exogroup.qnag.data.AlertSoundMode
import com.exogroup.qnag.data.NotificationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app alert sound engine.
 *
 * Plays alert sounds independently of Android notification-channel settings,
 * which can be muted by the user or OS.  Sounds always stop automatically.
 *
 * Sound selection priority:
 *  1. settings.inAppSoundUri — user-selected ringtone, played via Ringtone API
 *  2. Embedded qNag default: R.raw.qnag_alert — short bundled sound, played via MediaPlayer
 *  3. System default alarm (TYPE_ALARM) — last-resort fallback
 *  4. System default ringtone (TYPE_RINGTONE) — final fallback
 *
 * If the custom URI fails, falls back to the embedded default.
 * All playback is capped at settings.maxAlertSoundSeconds (clamped 1..60).
 *
 * Thread-safe: may be called from service or WorkManager coroutines.
 */
object AlertSoundPlayer {

    @Volatile private var currentRingtone: android.media.Ringtone? = null
    @Volatile private var currentMediaPlayer: MediaPlayer? = null
    @Volatile private var timeoutJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Public API ────────────────────────────────────────────────────────────

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

        if (!settings.playSoundInVibrateMode || !settings.useAlarmAudioStream) {
            val am = context.getSystemService(AudioManager::class.java)
            val mode = am?.ringerMode
            if (mode == AudioManager.RINGER_MODE_VIBRATE || mode == AudioManager.RINGER_MODE_SILENT) {
                if (debug) android.util.Log.d("qNag", "[sound] skipping — ringer mode=$mode, bypass disabled")
                return
            }
        }

        stop()

        val usage = if (settings.useAlarmAudioStream) AudioAttributes.USAGE_ALARM
                    else AudioAttributes.USAGE_NOTIFICATION_EVENT
        val maxMs = settings.maxAlertSoundSeconds.coerceIn(1, 60) * 1000L

        if (debug) android.util.Log.d("qNag",
            "[sound] playing — customUri=${settings.inAppSoundUri != null} " +
            "vibrateBypass=${settings.playSoundInVibrateMode} usage=$usage maxMs=$maxMs")

        // 1. Try custom URI first (Ringtone)
        val customPlayed = settings.inAppSoundUri?.let { uriStr ->
            tryPlayRingtone(context, Uri.parse(uriStr), usage, maxMs, debug, label = "custom")
        } ?: false

        if (customPlayed) return

        if (settings.inAppSoundUri != null && debug)
            android.util.Log.w("qNag", "[sound] custom URI failed, falling back to embedded")

        // 2. Embedded qNag default (MediaPlayer, non-looping, max-duration enforced)
        val embeddedPlayed = playEmbedded(context, usage, maxMs, debug)
        if (embeddedPlayed) return

        // 3. System default alarm
        val defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (defaultAlarm != null && tryPlayRingtone(context, defaultAlarm, usage, maxMs, debug, "default-alarm")) return

        // 4. System ringtone last resort
        val defaultRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (defaultRingtone != null) tryPlayRingtone(context, defaultRingtone, usage, maxMs, debug, "default-ringtone")
        else if (debug) android.util.Log.w("qNag", "[sound] no fallback URI available")
    }

    /** Stop any currently playing alert sound and cancel the auto-stop timeout. */
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        try { currentRingtone?.stop() } catch (_: Exception) { }
        currentRingtone = null
        releaseMediaPlayer()
    }

    /** Returns true if an alert sound is currently playing. */
    fun isPlaying(): Boolean =
        currentRingtone?.isPlaying == true || currentMediaPlayer?.isPlaying == true

    // ── Ringtone playback ─────────────────────────────────────────────────────

    private fun tryPlayRingtone(
        context: Context,
        uri: Uri,
        usage: Int,
        maxMs: Long,
        debug: Boolean,
        label: String,
    ): Boolean {
        return try {
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: run {
                if (debug) android.util.Log.w("qNag", "[sound] getRingtone null for $label")
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
            scheduleStop(maxMs)
            if (debug) android.util.Log.d("qNag", "[sound] playing ($label) uri=$uri")
            true
        } catch (e: Exception) {
            if (debug) android.util.Log.w("qNag", "[sound] play failed ($label): ${e.javaClass.simpleName}")
            false
        }
    }

    // ── MediaPlayer playback (embedded qnag_alert) ────────────────────────────

    private fun playEmbedded(context: Context, usage: Int, maxMs: Long, debug: Boolean): Boolean {
        return try {
            val mp = MediaPlayer.create(context, R.raw.qnag_alert) ?: run {
                if (debug) android.util.Log.w("qNag", "[sound] MediaPlayer.create returned null for embedded")
                return false
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = false
            mp.setOnCompletionListener { releaseMediaPlayer() }
            currentMediaPlayer = mp
            mp.start()
            scheduleStop(maxMs)
            if (debug) android.util.Log.d("qNag", "[sound] playing embedded qnag_alert (maxMs=$maxMs)")
            true
        } catch (e: Exception) {
            if (debug) android.util.Log.w("qNag", "[sound] embedded play failed: ${e.javaClass.simpleName}")
            releaseMediaPlayer()
            false
        }
    }

    private fun releaseMediaPlayer() {
        try { currentMediaPlayer?.stop() } catch (_: Exception) { }
        try { currentMediaPlayer?.release() } catch (_: Exception) { }
        currentMediaPlayer = null
    }

    // ── Shared timeout ────────────────────────────────────────────────────────

    private fun scheduleStop(maxMs: Long) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(maxMs)
            stop()
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
