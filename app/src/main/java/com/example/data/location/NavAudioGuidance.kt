package com.example.data.location

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Pure announcement-suppression state (Phase B, audit F1-D3 / backlog B-02).
 *
 * A maneuver is announced **at most once**. Because a new route restarts the
 * maneuver indices at 0, the history must be reset together with the route —
 * otherwise the new route's first maneuvers are suppressed as "already
 * announced" and the rider hears nothing.
 *
 * Kept free of Android dependencies so the contract is unit-testable.
 */
class AnnouncementHistory {

    /** Last maneuver index announced; -1 means "nothing announced yet". */
    var lastSpokenManeuverIndex: Int = -1
        private set

    /** True when [maneuverIndex] has not been announced yet. */
    fun shouldAnnounce(maneuverIndex: Int): Boolean = maneuverIndex > lastSpokenManeuverIndex

    /** Records that [maneuverIndex] has been announced. */
    fun markSpoken(maneuverIndex: Int) {
        if (maneuverIndex > lastSpokenManeuverIndex) {
            lastSpokenManeuverIndex = maneuverIndex
        }
    }

    /** Clears the history — call whenever a new route is installed. */
    fun reset() {
        lastSpokenManeuverIndex = -1
    }
}

class NavAudioGuidance(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Transient audio focus request with "may duck" semantics: helmet intercom / Bluetooth
     * music (Spotify, YouTube Music) ducks smoothly while a TTS prompt plays and resumes
     * immediately after speaking. USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + SPEECH content
     * type also tells the audio router to prefer the voice channel over media routing.
     *
     * AudioFocusRequest/abandonAudioFocusRequest require API 26; on API 24/25 the legacy
     * AudioManager focus calls with a matching listener and stream type are used instead.
     */
    private val duckingFocusRequest: AudioFocusRequest? =
        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
        } else {
            null
        }

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /** No-op focus listener (informational focus changes are not acted on). */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /** Announcement suppression state (pure, unit-testable — see [AnnouncementHistory]). */
    private val announcementHistory = AnnouncementHistory()

    /**
     * Read-only view of the announcement history (regression tests, audit B-02).
     * -1 means "nothing announced yet".
     */
    val announcedHistory: Int get() = announcementHistory.lastSpokenManeuverIndex

    companion object {
        /**
         * Duplicate-suppression rule: a maneuver is announced **at most once**.
         *
         * A route change must reset the history (see [resetAnnounceHistory]),
         * because the new route's maneuver indices restart at 0 and would
         * otherwise be suppressed as "already announced" (audit F1-D3).
         */
        @JvmStatic
        fun shouldAnnounceManeuver(maneuverIndex: Int, lastSpokenManeuverIndex: Int): Boolean =
            maneuverIndex > lastSpokenManeuverIndex
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("NavAudioGuidance", "Language US is not supported or missing data for TTS")
            } else {
                // Abandon ducking focus as soon as each utterance finishes or errors, so
                // background music restores volume immediately after speaking.
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Focus already requested before speak(); nothing to do.
                    }

                    override fun onDone(utteranceId: String?) {
                        abandonDuckFocus()
                    }

                    override fun onError(utteranceId: String?) {
                        abandonDuckFocus()
                    }
                })
                isTtsReady = true
                Log.d("NavAudioGuidance", "TextToSpeech initialized successfully")
            }
        } else {
            Log.e("NavAudioGuidance", "Initialization of TextToSpeech failed with status: $status")
        }
    }

    private fun requestDuckFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = duckingFocusRequest ?: return
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w("NavAudioGuidance", "Audio focus request failed: ${e.message}")
        }
    }

    private fun abandonDuckFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                duckingFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
            Log.w("NavAudioGuidance", "Audio focus abandon failed: ${e.message}")
        }
    }

    fun speakManeuver(instruction: String, maneuverIndex: Int, distanceMeters: Double) {
        if (_isMuted.value || !isTtsReady) return
        if (!announcementHistory.shouldAnnounce(maneuverIndex)) return

        val textToSpeak = when {
            distanceMeters > 500 -> "In ${(distanceMeters / 100).toInt() * 100} meters, $instruction"
            distanceMeters > 100 -> "In ${distanceMeters.toInt()} meters, $instruction"
            else -> instruction
        }

        // Duck helmet music for the duration of the prompt; the UtteranceProgressListener
        // registered in onInit() abandons focus in onDone()/onError().
        requestDuckFocus()

        try {
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "maneuver_$maneuverIndex")
            announcementHistory.markSpoken(maneuverIndex)
            Log.d("NavAudioGuidance", "Spoke maneuver [$maneuverIndex]: $textToSpeak")
        } catch (e: Exception) {
            abandonDuckFocus()
            Log.w("NavAudioGuidance", "Error speaking TTS maneuver: ${e.message}")
        }
    }

    fun toggleMute(): Boolean {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            tts?.stop()
            abandonDuckFocus()
        }
        return _isMuted.value
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            tts?.stop()
            abandonDuckFocus()
        }
    }

    fun resetAnnounceHistory() {
        announcementHistory.reset()
    }

    fun shutdown() {
        try {
            abandonDuckFocus()
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (e: Exception) {
            Log.w("NavAudioGuidance", "Error shutting down TTS: ${e.message}")
        }
    }
}
