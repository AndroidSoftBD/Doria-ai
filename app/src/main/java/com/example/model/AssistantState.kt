package com.example.model

enum class AssistantStatus {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

data class DoriaUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val userTranscription: String = "",
    val assistantResponse: String = "Hello! I'm Doria. Tap the mic or say something to talk!",
    val isMicActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioAmplitude: Float = 0f,
    val detectedLanguage: String = "Auto (English / Hindi / Hinglish)",
    val errorMessage: String? = null,
    val lastActionFeedback: String? = null,
    val isSpeakerTesting: Boolean = false
)
