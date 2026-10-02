package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.actions.DeviceActionManager
import com.example.audio.AudioPlaybackQueue
import com.example.audio.SpeechInputManager
import com.example.gemini.GeminiLiveClient
import com.example.model.AssistantStatus
import com.example.model.DoriaUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DoriaViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DoriaViewModel"
    }

    private val _uiState = MutableStateFlow(DoriaUiState())
    val uiState: StateFlow<DoriaUiState> = _uiState.asStateFlow()

    private val deviceActionManager = DeviceActionManager(application.applicationContext)

    val audioPlaybackQueue = AudioPlaybackQueue(
        coroutineScope = viewModelScope,
        onPlaybackStateChanged = { isPlaying ->
            _uiState.update { current ->
                current.copy(
                    isSpeaking = isPlaying,
                    status = if (isPlaying) {
                        AssistantStatus.SPEAKING
                    } else if (current.isMicActive) {
                        AssistantStatus.LISTENING
                    } else {
                        AssistantStatus.IDLE
                    }
                )
            }
        },
        onAmplitudeChanged = { amp ->
            if (_uiState.value.isSpeaking) {
                _uiState.update { it.copy(audioAmplitude = amp) }
            }
        }
    )

    private val geminiClient = GeminiLiveClient(
        coroutineScope = viewModelScope,
        audioPlaybackQueue = audioPlaybackQueue,
        deviceActionManager = deviceActionManager,
        onSubtitleReceived = { userText, assistantText ->
            _uiState.update { current ->
                current.copy(
                    userTranscription = userText ?: current.userTranscription,
                    assistantResponse = assistantText ?: current.assistantResponse,
                    status = if (current.isSpeaking) AssistantStatus.SPEAKING else current.status
                )
            }
        },
        onError = { error ->
            Log.e(TAG, "Gemini error: $error")
            _uiState.update {
                it.copy(
                    errorMessage = error,
                    status = AssistantStatus.ERROR
                )
            }
        }
    )

    private val speechInputManager = SpeechInputManager(
        context = application.applicationContext,
        onSpeechStarted = {
            _uiState.update {
                it.copy(
                    isMicActive = true,
                    status = AssistantStatus.LISTENING,
                    errorMessage = null
                )
            }
        },
        onPartialResult = { partial ->
            _uiState.update { it.copy(userTranscription = partial) }
        },
        onFinalResult = { finalResult ->
            _uiState.update {
                it.copy(
                    userTranscription = finalResult,
                    isMicActive = false,
                    status = AssistantStatus.CONNECTING
                )
            }
            // Send user speech to Gemini Live
            geminiClient.sendUserSpeech(finalResult)
        },
        onRmsChanged = { rmsNorm ->
            if (_uiState.value.isMicActive) {
                _uiState.update { it.copy(audioAmplitude = rmsNorm) }
            }
        },
        onError = { code, msg ->
            Log.w(TAG, "Speech recognition error: $msg ($code)")
            _uiState.update {
                it.copy(
                    isMicActive = false,
                    audioAmplitude = 0f,
                    status = if (it.isSpeaking) AssistantStatus.SPEAKING else AssistantStatus.IDLE
                )
            }
        },
        onSpeechEnded = {
            _uiState.update {
                it.copy(
                    isMicActive = false,
                    audioAmplitude = 0f
                )
            }
        }
    )

    /**
     * Toggles speech recognition. If Doria is speaking, interrupts Doria first.
     */
    fun toggleMicrophone() {
        if (_uiState.value.isSpeaking) {
            // Natural interruption: user speaks while Doria is speaking
            audioPlaybackQueue.stopAndClear()
            _uiState.update {
                it.copy(
                    isSpeaking = false,
                    audioAmplitude = 0f
                )
            }
        }

        if (_uiState.value.isMicActive) {
            speechInputManager.stopListening()
            _uiState.update { it.copy(isMicActive = false, status = AssistantStatus.IDLE) }
        } else {
            speechInputManager.startListening()
            _uiState.update {
                it.copy(
                    isMicActive = true,
                    status = AssistantStatus.LISTENING,
                    errorMessage = null
                )
            }
        }
    }

    /**
     * Triggers sending predefined quick speech or user action.
     */
    fun sendQuery(text: String) {
        if (_uiState.value.isSpeaking) {
            audioPlaybackQueue.stopAndClear()
        }
        speechInputManager.stopListening()
        _uiState.update {
            it.copy(
                userTranscription = text,
                status = AssistantStatus.CONNECTING,
                isMicActive = false
            )
        }
        geminiClient.sendUserSpeech(text)
    }

    /**
     * Speaker Diagnostic Test:
     * Generates a 440Hz tone directly through the AudioTrack to test speaker output.
     */
    fun runSpeakerTest() {
        Log.d(TAG, "Triggering Speaker Diagnostic Test...")
        _uiState.update { it.copy(isSpeakerTesting = true) }
        audioPlaybackQueue.playTestTone(440f, 1200) {
            _uiState.update { it.copy(isSpeakerTesting = false) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, status = AssistantStatus.IDLE) }
    }

    override fun onCleared() {
        super.onCleared()
        speechInputManager.release()
        audioPlaybackQueue.release()
    }
}
