package com.example.data.location

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class NavAudioGuidance(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private var lastSpokenManeuverIndex = -1

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("NavAudioGuidance", "Language US is not supported or missing data for TTS")
            } else {
                isTtsReady = true
                Log.d("NavAudioGuidance", "TextToSpeech initialized successfully")
            }
        } else {
            Log.e("NavAudioGuidance", "Initialization of TextToSpeech failed with status: $status")
        }
    }

    fun speakManeuver(instruction: String, maneuverIndex: Int, distanceMeters: Double) {
        if (_isMuted.value || !isTtsReady) return
        if (maneuverIndex <= lastSpokenManeuverIndex) return

        val textToSpeak = when {
            distanceMeters > 500 -> "In ${(distanceMeters / 100).toInt() * 100} meters, $instruction"
            distanceMeters > 100 -> "In ${distanceMeters.toInt()} meters, $instruction"
            else -> instruction
        }

        try {
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "maneuver_$maneuverIndex")
            lastSpokenManeuverIndex = maneuverIndex
            Log.d("NavAudioGuidance", "Spoke maneuver [$maneuverIndex]: $textToSpeak")
        } catch (e: Exception) {
            Log.w("NavAudioGuidance", "Error speaking TTS maneuver: ${e.message}")
        }
    }

    fun toggleMute(): Boolean {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            tts?.stop()
        }
        return _isMuted.value
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            tts?.stop()
        }
    }

    fun resetAnnounceHistory() {
        lastSpokenManeuverIndex = -1
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (e: Exception) {
            Log.w("NavAudioGuidance", "Error shutting down TTS: ${e.message}")
        }
    }
}
