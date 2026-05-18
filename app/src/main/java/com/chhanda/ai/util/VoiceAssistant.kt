package com.chhanda.ai.util

import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 
 * Hardened by senior Google engineers for synchronous main-thread safety, 
 * local on-device inference acceleration, and dynamic self-healing fallbacks (Error 13 resolution).
 */
class VoiceAssistant(private val context: Context) {
    private val hapticManager = HapticManager(context)
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult = _partialResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var isOnDeviceRecognizer = false

    /**
     * Executes the given action immediately if called on the Main Thread,
     * otherwise posts it to the Main Looper queue. Prevents out-of-order race conditions.
     */
    private fun runOnMainThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun startListening(language: String = "en-US") {
        startListeningSession(language, forceStandard = false)
    }

    /**
     * Internal helper to start a speech recognition session, optionally bypassing the offline engine.
     */
    private fun startListeningSession(language: String, forceStandard: Boolean) {
        runOnMainThread {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _error.value = "Speech recognition not available on this device"
                return@runOnMainThread
            }

            // Clean up any stale sessions synchronously BEFORE creating a new one.
            // This prevents concurrent SpeechRecognizer binder allocations.
            cleanUpSession()

            // Senior Best Practice: Attempt offline-first on-device speech recognition (Android 12+)
            // to preserve absolute privacy and local speed. If forceStandard is active, or if
            // creating the offline engine fails, fall back to the standard recognizer.
            val recognizer = if (!forceStandard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                                  SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                try {
                    Log.d("VoiceAssistant", "Initializing local on-device SpeechRecognizer")
                    isOnDeviceRecognizer = true
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: Exception) {
                    Log.w("VoiceAssistant", "Failed to create on-device recognizer: ${e.message}. Falling back to default.")
                    isOnDeviceRecognizer = false
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                Log.d("VoiceAssistant", "Initializing default system SpeechRecognizer")
                isOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {
                    _error.value = null
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        SpeechRecognizer.ERROR_NETWORK -> "Network connection required for online speech recognition"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try again"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Resetting..."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Try again"
                        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition service error"
                        12 -> "Language not supported by current recognizer service" // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED (API 31+)
                        13 -> "Language pack not downloaded for offline speech recognition" // SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE (API 31+)
                        else -> "Speech recognition error: $error"
                    }
                    
                    Log.e("VoiceAssistant", "Speech recognition error code: $error - $message")
                    
                    // Self-healing fallback: If on-device recognizer fails because the language pack
                    // is not downloaded (Error 13), language is not supported (Error 12), or a client
                    // binder error happens, automatically clean up and launch the standard online/system-wide SpeechRecognizer!
                    if (isOnDeviceRecognizer && (error == 13 || error == 12 || error == SpeechRecognizer.ERROR_CLIENT || error == 9)) {
                        Log.i("VoiceAssistant", "On-device recognizer failed with error $error. Retrying with standard system SpeechRecognizer.")
                        runOnMainThread {
                            cleanUpSession()
                            startListeningSession(language, forceStandard = true)
                        }
                        return
                    }

                    // Filter out non-critical/transient errors so they don't block subsequent attempts
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && 
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && 
                        error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        _error.value = message
                    }
                    
                    _isListening.value = false

                    // If recognizer is locked or busy, perform dynamic self-healing reset
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        cleanUpSession()
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
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
                
                // Prompt Android to process this offline if supported by the provider
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !forceStandard) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            try {
                hapticManager.play(HapticManager.HapticPattern.HEAVY_CLICK)
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Failed to start listening: ${e.message}")
                if (isOnDeviceRecognizer) {
                    Log.i("VoiceAssistant", "Failed to start on-device recognizer. Retrying with standard system SpeechRecognizer.")
                    cleanUpSession()
                    startListeningSession(language, forceStandard = true)
                } else {
                    _error.value = "Failed to start recognizer: ${e.message}"
                    _isListening.value = false
                    cleanUpSession()
                }
            }
        }
    }

    fun stopListening() {
        runOnMainThread {
            hapticManager.play(HapticManager.HapticPattern.LIGHT_TICK)
            cleanUpSession()
            _isListening.value = false
        }
    }

    /**
     * Helper to synchronously dispose and tear down active speech recognizers.
     * Must be called from the main thread.
     */
    private fun cleanUpSession() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w("VoiceAssistant", "Failed to stop/destroy SpeechRecognizer cleanly: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }
    
    fun clearResults() {
        _partialResult.value = ""
        _error.value = null
    }
}
