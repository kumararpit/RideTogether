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
import java.util.concurrent.ConcurrentHashMap

/**
 * Records TTS latency for a single utterance: the elapsed time from the speak()
 * call to the onStart callback (voice onset) and to the onDone callback
 * (utterance complete). Mirrors Google Maps' VoiceGuidanceClearcutLogger.
 */
data class TtsLatencyRecord(
    val maneuverIndex: Int,
    val requestTimeMs: Long,
    val onsetTimeMs: Long = 0L,
    val doneTimeMs: Long = 0L,
    val spoken: Boolean = false
) {
    /** Voice-onset latency in ms (0 if not yet spoken). */
    val onsetLatencyMs: Long
        get() = if (spoken) onsetTimeMs - requestTimeMs else 0L
    /** Full utterance latency in ms (0 if not yet done). */
    val utteranceLatencyMs: Long
        get() = if (spoken && doneTimeMs > 0L) doneTimeMs - requestTimeMs else 0L
}

/**
 * Rolling window of TTS latency records — tracks the most recent N utterances
 * so the product team can detect voice guidance regressions (slow TTS onset,
 * dropped utterances).
 */
class TtsLatencyTracker(private val maxRecords: Int = 50) {

    private val records = java.util.concurrent.CopyOnWriteArrayList<TtsLatencyRecord>()

    /** Call when speak() is issued for a maneuver. */
    fun recordRequest(maneuverIndex: Int, timeMs: Long) {
        records.add(TtsLatencyRecord(maneuverIndex = maneuverIndex, requestTimeMs = timeMs))
        while (records.size > maxRecords) records.removeAt(0)
    }

    /** Call when TTS onStart fires for an utterance. */
    fun recordOnset(utteranceId: String?, timeMs: Long) {
        val index = utteranceId?.let { extractManeuverIndex(it) } ?: -1
        val rec = records.asSequence().firstOrNull { it.maneuverIndex == index && !it.spoken }
        if (rec != null) {
            val pos = records.indexOf(rec)
            records[pos] = rec.copy(onsetTimeMs = timeMs, spoken = true)
        }
    }

    /** Call when TTS onDone fires for an utterance. */
    fun recordDone(utteranceId: String?, timeMs: Long) {
        val index = utteranceId?.let { extractManeuverIndex(it) } ?: -1
        val rec = records.asSequence().firstOrNull { it.maneuverIndex == index && it.spoken && recDoneTime(it) == 0L }
        if (rec != null) {
            val pos = records.indexOf(rec)
            records[pos] = rec.copy(doneTimeMs = timeMs)
        }
    }

    private fun recDoneTime(rec: TtsLatencyRecord): Long = rec.doneTimeMs

    private fun extractManeuverIndex(utteranceId: String?): Int {
        val suffix = utteranceId?.removePrefix("maneuver_") ?: return -1
        return suffix.toIntOrNull() ?: -1
    }

    /** Average voice-onset latency across spoken utterances (ms). */
    val averageOnsetLatencyMs: Double
        get() {
            val spoken = records.filter { it.spoken && it.onsetLatencyMs > 0L }
            return if (spoken.isEmpty()) 0.0 else spoken.map { it.onsetLatencyMs.toDouble() }.average()
        }

    /** Average full-utterance latency across completed utterances (ms). */
    val averageUtteranceLatencyMs: Double
        get() {
            val done = records.filter { it.spoken && it.utteranceLatencyMs > 0L }
            return if (done.isEmpty()) 0.0 else done.map { it.utteranceLatencyMs.toDouble() }.average()
        }

    /** Count of utterances requested but never spoken (dropped). */
    val droppedCount: Int
        get() = records.count { !it.spoken }

    /** Total number of recorded requests. */
    val totalRequests: Int get() = records.size

    fun clear() { records.clear() }
}

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

    /** TTS latency tracker (VoiceGuidanceClearcutLogger equivalent). */
    private val ttsLatencyTracker = TtsLatencyTracker()

    /** Read-only view of TTS latency stats (for diagnostics / telemetry). */
    val ttsLatencyStats: TtsLatencyTracker get() = ttsLatencyTracker

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
                        ttsLatencyTracker.recordOnset(utteranceId, System.currentTimeMillis())
                        // Focus already requested before speak(); nothing to do.
                    }

                    override fun onDone(utteranceId: String?) {
                        ttsLatencyTracker.recordDone(utteranceId, System.currentTimeMillis())
                        abandonDuckFocus()
                    }

                    override fun onError(utteranceId: String?) {
                        ttsLatencyTracker.recordDone(utteranceId, System.currentTimeMillis())
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

        val utteranceId = "maneuver_$maneuverIndex"
        val requestTimeMs = System.currentTimeMillis()
        ttsLatencyTracker.recordRequest(maneuverIndex, requestTimeMs)

        // Duck helmet music for the duration of the prompt; the UtteranceProgressListener
        // registered in onInit() abandons focus in onDone()/onError().
        requestDuckFocus()

        try {
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
        ttsLatencyTracker.clear()
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
