package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sin

/**
 * High-performance, persistent Android AudioTrack playback queue.
 * Decodes raw PCM/WAV chunks from Gemini Live audio output and streams them seamlessly to the speaker.
 * Includes complete logging, queue management, interruption handling, and speaker diagnostic test.
 */
class AudioPlaybackQueue(
    private val coroutineScope: CoroutineScope,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onAmplitudeChanged: (amplitude: Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "DoriaAudio"
        private const val DEFAULT_SAMPLE_RATE = 24000 // Gemini default audio sample rate
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = DEFAULT_SAMPLE_RATE
    private val audioChunkQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var isInterrupted = false

    init {
        Log.d(TAG, "AudioPlaybackQueue initialized")
        initAudioTrack(DEFAULT_SAMPLE_RATE)
    }

    @Synchronized
    private fun initAudioTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate) {
            return
        }

        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing old AudioTrack", e)
        }

        currentSampleRate = sampleRate
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 4).coerceAtLeast(8192)

        Log.d(TAG, "Initializing AudioTrack: sampleRate=$sampleRate, minBufferSize=$minBufferSize, bufferSize=$bufferSize")

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.d(TAG, "AudioTrack initialized successfully. State: ${audioTrack?.state}")
    }

    /**
     * Enqueue base64 audio received from Gemini Live API.
     */
    fun enqueueBase64Chunk(base64Data: String, mimeType: String? = null) {
        Log.d(TAG, "Base64 decoding started. Length: ${base64Data.length}, MIME: $mimeType")
        try {
            val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
            Log.d(TAG, "Base64 decoding successful. Decoded bytes: ${rawBytes.size}")

            val (sampleRate, pcmBytes) = parsePcmData(rawBytes, mimeType)
            Log.d(TAG, "PCM decoding successful. SampleRate: $sampleRate, PCM bytes: ${pcmBytes.size}")

            if (sampleRate != currentSampleRate) {
                initAudioTrack(sampleRate)
            }

            audioChunkQueue.add(pcmBytes)
            isInterrupted = false

            if (!isPlaying) {
                startPlaybackLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio decoding error", e)
        }
    }

    /**
     * Inspects data to strip WAV header if present, or extract sample rate from MIME type.
     */
    private fun parsePcmData(bytes: ByteArray, mimeType: String?): Pair<Int, ByteArray> {
        var sampleRate = DEFAULT_SAMPLE_RATE

        // Parse sample rate from MIME type if present (e.g., "audio/pcm;rate=24000")
        if (mimeType != null && mimeType.contains("rate=")) {
            val match = Regex("rate=(\\d+)").find(mimeType)
            match?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                sampleRate = it
                Log.d(TAG, "Extracted sample rate from MIME: $sampleRate")
            }
        }

        // Check if data has a WAV header ("RIFF")
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            Log.d(TAG, "WAV header detected in audio data. Parsing header...")
            try {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val wavSampleRate = bb.getInt(24)
                if (wavSampleRate in 8000..48000) {
                    sampleRate = wavSampleRate
                }
                // Skip 44-byte WAV header
                val pcmOnly = bytes.copyOfRange(44, bytes.size)
                return Pair(sampleRate, pcmOnly)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse WAV header, using raw bytes", e)
            }
        }

        return Pair(sampleRate, bytes)
    }

    private fun startPlaybackLoop() {
        if (isPlaying) return
        isPlaying = true
        onPlaybackStateChanged(true)
        Log.d(TAG, "Audio playback started")

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val track = audioTrack ?: return@launch
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }

                while (isActive && !isInterrupted) {
                    val chunk = audioChunkQueue.poll()
                    if (chunk != null && chunk.isNotEmpty()) {
                        calculateAndEmitAmplitude(chunk)

                        var bytesWritten = 0
                        while (bytesWritten < chunk.size && isActive && !isInterrupted) {
                            val count = track.write(
                                chunk,
                                bytesWritten,
                                chunk.size - bytesWritten,
                                AudioTrack.WRITE_BLOCKING
                            )
                            if (count < 0) {
                                Log.e(TAG, "AudioTrack write error: $count")
                                break
                            }
                            bytesWritten += count
                        }
                    } else {
                        // Queue empty, wait slightly before ending to catch rapid successive chunks
                        var waitedMs = 0
                        var gotNewChunk = false
                        while (waitedMs < 200 && isActive && !isInterrupted) {
                            kotlinx.coroutines.delay(20)
                            waitedMs += 20
                            if (audioChunkQueue.isNotEmpty()) {
                                gotNewChunk = true
                                break
                            }
                        }
                        if (!gotNewChunk) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error in playback loop", e)
            } finally {
                isPlaying = false
                onAmplitudeChanged(0f)
                onPlaybackStateChanged(false)
                Log.d(TAG, "Audio playback ended")
            }
        }
    }

    private fun calculateAndEmitAmplitude(pcmBytes: ByteArray) {
        if (pcmBytes.size < 2) return
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var maxAmp = 0
        while (shortBuffer.hasRemaining()) {
            val sample = Math.abs(shortBuffer.get().toInt())
            if (sample > maxAmp) {
                maxAmp = sample
            }
        }
        val normalized = (maxAmp / 32768.0f).coerceIn(0.0f, 1.0f)
        onAmplitudeChanged(normalized)
    }

    /**
     * Instantly interrupts and stops ongoing playback, clearing the queue.
     */
    fun stopAndClear() {
        Log.d(TAG, "Audio playback interrupted - stopping and clearing queue")
        isInterrupted = true
        audioChunkQueue.clear()
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.pause()
                    it.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }

        isPlaying = false
        onAmplitudeChanged(0f)
        onPlaybackStateChanged(false)
    }

    /**
     * Diagnostic Speaker Test:
     * Generates a 440Hz sine wave tone for specified duration and plays through the AudioTrack.
     */
    fun playTestTone(frequency: Float = 440f, durationMs: Int = 1000, onComplete: () -> Unit = {}) {
        Log.d(TAG, "Running Speaker Diagnostic Test (440Hz tone, duration=${durationMs}ms)...")
        stopAndClear()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 24000
                initAudioTrack(sampleRate)
                val track = audioTrack ?: return@launch

                val numSamples = (durationMs * sampleRate) / 1000
                val pcmData = ByteArray(numSamples * 2)

                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i * frequency / sampleRate
                    // Apply fade in and fade out envelope to prevent clicking
                    val envelope = when {
                        i < 500 -> i / 500.0
                        i > numSamples - 500 -> (numSamples - i) / 500.0
                        else -> 1.0
                    }
                    val sample = (sin(angle) * 0.5 * envelope * 32767.0).toInt().toShort()
                    pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
                    pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                }

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }

                isPlaying = true
                onPlaybackStateChanged(true)
                onAmplitudeChanged(0.6f)

                track.write(pcmData, 0, pcmData.size)

                // Wait for audio track to flush
                kotlinx.coroutines.delay(durationMs.toLong() + 100)

                Log.d(TAG, "Speaker Diagnostic Test completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Speaker Diagnostic Test failed", e)
            } finally {
                isPlaying = false
                onAmplitudeChanged(0f)
                onPlaybackStateChanged(false)
                launch(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    fun release() {
        Log.d(TAG, "Releasing AudioPlaybackQueue")
        stopAndClear()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
    }
}
