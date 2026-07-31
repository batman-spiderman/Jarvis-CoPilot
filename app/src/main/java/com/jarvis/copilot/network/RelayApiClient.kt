package com.jarvis.copilot.network

import com.jarvis.copilot.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MultipartBody
import okhttp3.RequestBody

data class ChatRequest(val query: String)
data class ChatResponse(val text: String)

data class VoiceSampleUploadResponse(val id: String, val status: String)
data class VoiceSampleCountResponse(val count: Int, val untrained: Int)
data class MarkTrainedResponse(val updated: Int)
data class ClearVoiceSamplesResponse(val deleted: Int)

data class PhotoUploadResponse(val key: String, val status: String)
data class PhotoListResponse(val filenames: List<String>)
data class DeletePhotoResponse(val deleted: String)

/**
 * Everything here talks only to the relay server — the client never holds
 * a Backblaze B2 key or a Firebase key. Photos, voice audio, and voice
 * transcripts are all stored in B2 by the server (see relay-server/main.py).
 */
interface RelayApi {
    @POST("chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse

    @Multipart
    @POST("voice-samples")
    suspend fun uploadVoiceSample(
        @Part audio: MultipartBody.Part,
        @Part("transcript") transcript: RequestBody,
        @Part("deviceTag") deviceTag: RequestBody,
        @Part("timestamp") timestamp: RequestBody
    ): VoiceSampleUploadResponse

    @GET("voice-samples/count")
    suspend fun voiceSampleCount(): VoiceSampleCountResponse

    @POST("voice-samples/mark-trained")
    suspend fun markVoiceSamplesTrained(@Body sampleIds: List<String>): MarkTrainedResponse

    @DELETE("voice-samples")
    suspend fun clearVoiceSamples(): ClearVoiceSamplesResponse

    @Multipart
    @POST("photos")
    suspend fun uploadPhoto(
        @Part photo: MultipartBody.Part,
        @Part("deviceTag") deviceTag: RequestBody,
        @Part("filename") filename: RequestBody
    ): PhotoUploadResponse

    @GET("photos")
    suspend fun listPhotos(@Query("deviceTag") deviceTag: String): PhotoListResponse

    @DELETE("photos/{filename}")
    suspend fun deletePhoto(
        @Path("filename") filename: String,
        @Query("deviceTag") deviceTag: String
    ): DeletePhotoResponse
}

object RelayApiClient {

    // Shared secret + base URL come from BuildConfig — see app/build.gradle.kts
    // CREDENTIAL PLACEHOLDER: fill RELAY_BASE_URL and APP_SHARED_SECRET there.
    private val authInterceptor = Interceptor { chain: Interceptor.Chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.APP_SHARED_SECRET}")
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .build()

    val api: RelayApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.RELAY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RelayApi::class.java)
    }
}
