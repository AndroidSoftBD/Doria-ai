package com.example.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.actions.ActionResult
import com.example.actions.DeviceActionManager
import com.example.audio.AudioPlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val role: String,
    val text: String,
    val isUser: Boolean,
    val toolFeedback: String? = null
)

/**
 * Handles communication with Gemini API with real-time audio output,
 * function calling for Android device actions, and multi-language support.
 */
class GeminiLiveClient(
    private val coroutineScope: CoroutineScope,
    private val audioPlaybackQueue: AudioPlaybackQueue,
    private val deviceActionManager: DeviceActionManager,
    private val onAssistantStateChange: (isSpeaking: Boolean) -> Unit = {},
    private val onSubtitleReceived: (userText: String?, assistantText: String?) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DoriaGemini"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        // Priority models as specified in gemini-api skill:
        private const val AUDIO_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val FLASH_MODEL = "gemini-2.5-flash"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val conversationHistory = mutableListOf<JSONObject>()

    private val systemInstructionText = """
        You are Doria, a young, confident, witty, playful, and emotionally responsive virtual assistant.
        Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate.
        Use light sarcasm and witty responses. Never sound robotic.
        Adapt your tone to the user's emotions and conversation.
        Automatically understand and respond in the language the user is speaking (e.g. Hindi, English, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, etc.).
        Keep responses natural, engaging, and concise enough for real-time voice conversation (1 to 3 conversational sentences).
        You can execute safe supported device actions through available tools:
        - openWhatsApp(): when the user asks to open WhatsApp ("WhatsApp kholo", "open WhatsApp", "WhatsApp open karo")
        - openApp(appName): when the user asks to open YouTube, Instagram, Spotify, Chrome, Camera, Maps, Settings, Calculator, Calendar
        - openUrl(url): when the user asks to open a website
        - makeCall(phoneNumber): when the user provides a phone number to call
        - callContact(contactName): when the user asks to call Mom, Dad, Rahul, or any contact by name ("Mom ko call karo", "Call Rahul")
        Never claim that an action was completed unless the tool actually executed it.
        Avoid explicit or inappropriate content while maintaining your charm, confidence, and personality.
    """.trimIndent()

    init {
        Log.i(TAG, "Gemini session initialized. API key configured: ${BuildConfig.GEMINI_API_KEY.isNotBlank()}")
    }

    /**
     * Sends user speech text to Gemini and handles audio-to-audio/TTS and tool execution.
     */
    fun sendUserSpeech(userSpeech: String) {
        if (userSpeech.isBlank()) return

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is missing or not configured in Secrets")
            onError("Gemini API Key missing. Please configure GEMINI_API_KEY in the Secrets panel.")
            return
        }

        onSubtitleReceived(userSpeech, "Thinking...")

        coroutineScope.launch(Dispatchers.IO) {
            try {
                processUserTurn(userSpeech, apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "Error in processUserTurn", e)
                withContext(Dispatchers.Main) {
                    onError("Network or API error: ${e.message}")
                }
            }
        }
    }

    private suspend fun processUserTurn(userSpeech: String, apiKey: String) {
        // Record user turn in conversation history
        val userTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userSpeech) })
            })
        }
        conversationHistory.add(userTurn)

        Log.d(TAG, "User speech sent to Gemini: \"$userSpeech\"")

        // 1. First attempt with audio-enabled model
        var responseJson = requestGemini(AUDIO_MODEL, apiKey, requestAudio = true)

        // If audio model fails or returns error, fallback to FLASH_MODEL
        if (responseJson == null || responseJson.has("error")) {
            val errorMsg = responseJson?.optJSONObject("error")?.optString("message") ?: "Audio model unavailable"
            Log.w(TAG, "Primary audio model failed: $errorMsg. Retrying with flash model...")
            responseJson = requestGemini(FLASH_MODEL, apiKey, requestAudio = true)
        }

        if (responseJson == null || responseJson.has("error")) {
            val errorMsg = responseJson?.optJSONObject("error")?.optString("message") ?: "Gemini API request failed"
            Log.e(TAG, "Gemini API error: $errorMsg")
            withContext(Dispatchers.Main) {
                onError("Doria could not connect: $errorMsg")
            }
            return
        }

        Log.d(TAG, "Full response structure received from Gemini")
        handleGeminiResponse(responseJson, apiKey)
    }

    private suspend fun handleGeminiResponse(responseJson: JSONObject, apiKey: String) {
        val candidates = responseJson.optJSONArray("candidates")
        val candidate = candidates?.optJSONObject(0)
        val content = candidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        if (parts == null || parts.length() == 0) {
            Log.w(TAG, "No parts in Gemini response candidate")
            withContext(Dispatchers.Main) {
                onError("No response from Doria")
            }
            return
        }

        var assistantText = ""
        var hasAudio = false
        var pendingFunctionCall: JSONObject? = null

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            // Text part
            if (part.has("text")) {
                assistantText += part.getString("text") + " "
            }

            // Audio part (inlineData)
            if (part.has("inlineData")) {
                val inlineData = part.getJSONObject("inlineData")
                val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                val base64Audio = inlineData.optString("data", "")
                if (base64Audio.isNotEmpty()) {
                    hasAudio = true
                    Log.d(TAG, "Response contains audio! MIME: $mimeType, length: ${base64Audio.length}")
                    audioPlaybackQueue.enqueueBase64Chunk(base64Audio, mimeType)
                }
            }

            // Function Call
            if (part.has("functionCall")) {
                pendingFunctionCall = part.getJSONObject("functionCall")
            }
        }

        assistantText = assistantText.trim()
        Log.d(TAG, "Gemini assistantText: \"$assistantText\", hasAudio: $hasAudio, hasToolCall: ${pendingFunctionCall != null}")

        // Save assistant content in history
        conversationHistory.add(content)

        withContext(Dispatchers.Main) {
            onSubtitleReceived(null, assistantText)
        }

        // Handle Tool Calling if requested
        if (pendingFunctionCall != null) {
            handleFunctionCall(pendingFunctionCall, apiKey)
            return
        }

        // If response had no inline audio but has text, generate speech audio via TTS model so voice is guaranteed audible!
        if (!hasAudio && assistantText.isNotBlank()) {
            Log.i(TAG, "No inline audio returned in first pass. Generating speech audio via TTS model...")
            generateSpeechAudio(assistantText, apiKey)
        }
    }

    private suspend fun handleFunctionCall(functionCall: JSONObject, apiKey: String) {
        val functionName = functionCall.optString("name", "")
        val args = functionCall.optJSONObject("args") ?: JSONObject()

        Log.i(TAG, "Executing tool call: $functionName with args: $args")

        val actionResult: ActionResult = when (functionName) {
            "openWhatsApp" -> deviceActionManager.openWhatsApp()
            "openApp" -> {
                val appName = args.optString("appName", "")
                deviceActionManager.openApp(appName)
            }
            "openUrl" -> {
                val url = args.optString("url", "")
                deviceActionManager.openUrl(url)
            }
            "makeCall" -> {
                val phoneNumber = args.optString("phoneNumber", "")
                deviceActionManager.makeCall(phoneNumber)
            }
            "callContact" -> {
                val contactName = args.optString("contactName", "")
                deviceActionManager.callContact(contactName)
            }
            else -> ActionResult(false, functionName, "Unknown tool: $functionName")
        }

        Log.i(TAG, "Tool execution result: success=${actionResult.success}, message=${actionResult.message}")

        withContext(Dispatchers.Main) {
            onSubtitleReceived(null, actionResult.message)
        }

        // Send tool result back to Gemini so Doria responds naturally to the user about what she just did
        val functionResponsePart = JSONObject().apply {
            put("functionResponse", JSONObject().apply {
                put("name", functionName)
                put("response", JSONObject().apply {
                    put("success", actionResult.success)
                    put("message", actionResult.message)
                    if (actionResult.contacts != null) {
                        val contactArray = JSONArray()
                        actionResult.contacts.forEach { c ->
                            contactArray.put(JSONObject().apply {
                                put("name", c.name)
                                put("phoneNumber", c.phoneNumber)
                            })
                        }
                        put("contacts", contactArray)
                    }
                })
            })
        }

        val toolResponseTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(functionResponsePart) })
        }
        conversationHistory.add(toolResponseTurn)

        // Request final spoken response from Doria with audio
        val followUpResponse = requestGemini(AUDIO_MODEL, apiKey, requestAudio = true)
            ?: requestGemini(FLASH_MODEL, apiKey, requestAudio = true)

        if (followUpResponse != null) {
            handleGeminiResponse(followUpResponse, apiKey)
        }
    }

    /**
     * Synthesizes audio using Gemini Speech (TTS) when audio modality wasn't bundled in the main response.
     */
    private suspend fun generateSpeechAudio(textToSpeak: String, apiKey: String) {
        try {
            val url = "$BASE_URL/$TTS_MODEL:generateContent?key=$apiKey"
            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", textToSpeak) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede") // Energetic, young, expressive female voice
                            })
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string()

            if (response.isSuccessful && !bodyString.isNullOrBlank()) {
                val json = JSONObject(bodyString)
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")

                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            val mime = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                            val audioBase64 = inlineData.optString("data", "")
                            if (audioBase64.isNotEmpty()) {
                                Log.d(TAG, "TTS audio generated successfully. Length: ${audioBase64.length}")
                                audioPlaybackQueue.enqueueBase64Chunk(audioBase64, mime)
                                return
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "TTS generation response code: ${response.code}, body: $bodyString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speech generation failed", e)
        }
    }

    private fun requestGemini(model: String, apiKey: String, requestAudio: Boolean): JSONObject? {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"

        val requestBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemInstructionText) })
                })
            })

            // Keep recent turns to prevent context window explosion while maintaining conversational flow
            val turnsToSend = if (conversationHistory.size > 12) {
                conversationHistory.takeLast(12)
            } else {
                conversationHistory
            }
            put("contents", JSONArray(turnsToSend))

            // Tools (Function Calling)
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("functionDeclarations", buildToolDeclarations())
                })
            })

            // Generation config
            val genConfig = JSONObject().apply {
                put("temperature", 0.7)
                if (requestAudio) {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                        put("TEXT")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                }
            }
            put("generationConfig", genConfig)
        }

        return try {
            val req = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(req).execute()
            val respString = resp.body?.string() ?: return null
            JSONObject(respString)
        } catch (e: Exception) {
            Log.e(TAG, "HTTP call to $model failed", e)
            null
        }
    }

    private fun buildToolDeclarations(): JSONArray {
        val declarations = JSONArray()

        // 1. openWhatsApp
        declarations.put(JSONObject().apply {
            put("name", "openWhatsApp")
            put("description", "Open WhatsApp messenger application")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // 2. openApp
        declarations.put(JSONObject().apply {
            put("name", "openApp")
            put("description", "Open a specified installed app like YouTube, Instagram, Spotify, Chrome, Camera, Maps, Settings, Calculator, Calendar")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the app to open, e.g. YouTube, Instagram, Camera, Spotify")
                    })
                })
                put("required", JSONArray().apply { put("appName") })
            })
        })

        // 3. openUrl
        declarations.put(JSONObject().apply {
            put("name", "openUrl")
            put("description", "Open a web URL in browser")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The web URL to open, e.g. https://www.google.com")
                    })
                })
                put("required", JSONArray().apply { put("url") })
            })
        })

        // 4. makeCall
        declarations.put(JSONObject().apply {
            put("name", "makeCall")
            put("description", "Dial or start a phone call to a given phone number")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The telephone number to call")
                    })
                })
                put("required", JSONArray().apply { put("phoneNumber") })
            })
        })

        // 5. callContact
        declarations.put(JSONObject().apply {
            put("name", "callContact")
            put("description", "Search contacts on the user device and call/dial a contact by name (e.g. Mom, Dad, Rahul)")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("contactName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The contact name to look up and call, e.g. Mom, Rahul, Priya")
                    })
                })
                put("required", JSONArray().apply { put("contactName") })
            })
        })

        return declarations
    }

    fun clearHistory() {
        conversationHistory.clear()
        Log.d(TAG, "Conversation history cleared")
    }
}
