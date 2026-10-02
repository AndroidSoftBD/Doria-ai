package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Manages real-time speech input using Android's native SpeechRecognizer.
 * Supports multilingual voice recognition (Hindi, English, Hinglish, Marathi, etc.),
 * live partial speech updates, and audio amplitude tracking for the visual waveform.
 */
class SpeechInputManager(
    private val context: Context,
    private val onSpeechStarted: () -> Unit = {},
    private val onPartialResult: (text: String) -> Unit = {},
    private val onFinalResult: (text: String) -> Unit = {},
    private val onRmsChanged: (rmsdB: Float) -> Unit = {},
    private val onError: (errorCode: Int, errorMessage: String) -> Unit = { _, _ -> },
    private val onSpeechEnded: () -> Unit = {}
) {
    companion object {
        private const val TAG = "DoriaSpeechInput"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device!")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            Log.d(TAG, "SpeechRecognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Microphone ready for speech")
                isListening = true
                onSpeechStarted()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB typically ranges from -2 to 10
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.0f, 1.0f)
                onRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Buffer chunks if needed
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking")
                isListening = false
                onSpeechEnded()
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                    else -> "Unknown speech error ($error)"
                }
                Log.w(TAG, "Speech recognition error: $errorMsg ($error)")
                isListening = false
                onError(error, errorMsg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Speech recognition final result: \"$recognizedText\"")
                isListening = false
                if (recognizedText.isNotBlank()) {
                    onFinalResult(recognizedText)
                } else {
                    onSpeechEnded()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull() ?: ""
                if (partialText.isNotBlank()) {
                    Log.d(TAG, "Speech partial result: \"$partialText\"")
                    onPartialResult(partialText)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Starts listening with multilingual intent supporting Hindi, English, Hinglish, etc.
     */
    fun startListening(languagePreference: String? = null) {
        if (speechRecognizer == null) {
            initRecognizer()
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                // Multilingual support: Accept Indian English, Hindi, and device locale
                val lang = languagePreference ?: Locale.getDefault().toString()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
            }

            Log.d(TAG, "Starting speech recognition with language preference: ${languagePreference ?: "default"}")
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            onError(-1, e.message ?: "Failed to start listening")
        }
    }

    fun stopListening() {
        Log.d(TAG, "Stopping speech recognition")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
        isListening = false
    }

    fun cancel() {
        Log.d(TAG, "Cancelling speech recognition")
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling speech recognition", e)
        }
        isListening = false
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognition")
        cancel()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
    }
}
