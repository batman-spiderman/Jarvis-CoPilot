package com.jarvis.copilot.voice

import android.content.Context
import com.jarvis.copilot.JarvisApplication
import com.jarvis.copilot.network.RelayApiClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Voice samples are pooled across the user's devices for fine-tuning — same
 * person's voice regardless of which phone captured it — but this does NOT
 * require a shared login. deviceTag is just a label for logging/debugging,
 * not an auth boundary. Architecture §11/§15.
 *
 * Audio uploads immediately on capture; no permanent local copy is kept on
 * success. A small local fallback queue retries failed uploads only.
 */
object VoiceTrainingRepository {

    private val fallbackQueue = mutableListOf<Pair<ByteArray, String>>()

    suspend fun onVoiceCaptured(context: Context, audioBytes: ByteArray, transcript: String) {
        val deviceTag = (context.applicationContext as JarvisApplication).deviceTag()
        try {
            val audioPart = MultipartBody.Part.createFormData(
                "audio", "sample_${System.currentTimeMillis()}.wav",
                audioBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
            )
            RelayApiClient.api.uploadVoiceSample(
                audio = audioPart,
                transcript = transcript.toRequestBody("text/plain".toMediaTypeOrNull()),
                deviceTag = deviceTag.toRequestBody("text/plain".toMediaTypeOrNull()),
                timestamp = System.currentTimeMillis().toString().toRequestBody("text/plain".toMediaTypeOrNull())
            )
        } catch (e: Exception) {
            // Upload failed — queue for retry, no local audio file written to disk
            fallbackQueue.add(audioBytes to transcript)
        }
    }

    suspend fun retryFallbackQueue(context: Context) {
        val toRetry = fallbackQueue.toList()
        fallbackQueue.clear()
        toRetry.forEach { (audio, transcript) -> onVoiceCaptured(context, audio, transcript) }
    }

    /** Sample count/untrained count, read from the relay server's in-memory index. */
    suspend fun sampleCounts(): Pair<Int, Int> {
        val response = RelayApiClient.api.voiceSampleCount()
        return response.count to response.untrained
    }

    /** Deletes every stored sample — both audio and transcript — from B2 via the relay. */
    suspend fun clearAllSamples(): Int = RelayApiClient.api.clearVoiceSamples().deleted
}
