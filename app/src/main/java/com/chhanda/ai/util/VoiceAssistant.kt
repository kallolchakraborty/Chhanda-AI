package com.chhanda.ai.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.chhanda.ai.util.HapticManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * VoiceAssistant: Manages the on-device Speech-to-Text (STT) lifecycle.
 * Provides real-time feedback on listening state and transcription results.
 */
class VoiceAssistant(private val context: Context) {
    private val hapticManager = HapticManager(context)
    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult = _partialResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun startListening(language: String = "en-US") {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _error.value = "Speech recognition not available on this device"
                return@post
            }

            stopListening() // Reset any previous session on main thread

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        else -> "Speech recognition error: $error"
                    }
                    _error.value = message
                    _isListening.value = false
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        _partialResult.value = matches[0]
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        _partialResult.value = matches[0]
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Force on-device if possible (API 31+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            try {
                hapticManager.play(HapticManager.HapticPattern.HEAVY_CLICK)
                recognizer.startListening(intent)
            } catch (e: Exception) {
                _error.value = "Failed to start recognizer: ${e.message}"
                _isListening.value = false
            }
        }
    }

    fun stopListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                hapticManager.play(HapticManager.HapticPattern.LIGHT_TICK)
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w("VoiceAssistant", "Failed to stop/destroy SpeechRecognizer cleanly: ${e.message}")
            } finally {
                speechRecognizer = null
                _isListening.value = false
            }
        }
    }
    
    fun clearResults() {
        _partialResult.value = ""
        _error.value = null
    }
}
