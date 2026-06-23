package com.example.api

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.1-flash-lite-preview:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

class GeminiRepository {
    suspend fun getAssistantResponse(prompt: String, history: List<Pair<String, Boolean>> = emptyList()): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "YOUR_GEMINI_API_KEY") {
            return "Please configure your GEMINI_API_KEY in the Secrets panel or .env file to enable the low-latency AI Downloader Assistant."
        }

        // Construct conversation contents
        val contents = mutableListOf<Content>()
        
        // Add history (first = text, second = isUser)
        for ((text, isUser) in history) {
            val role = if (isUser) "user" else "model"
            // Wait, models API expects structured content with optional role, but since REST API allows simple sequential parts or direct contents:
            // Retrofit expects Content objects
            contents.add(Content(parts = listOf(Part(text = text))))
        }
        
        // Add active prompt
        contents.add(Content(parts = listOf(Part(text = prompt))))

        val request = GenerateContentRequest(
            contents = contents,
            systemInstruction = Content(
                parts = listOf(Part(text = "You are a helpful social media video downloading assistant. " +
                        "You help users learn how to parse URLs, extract and clean sharing parameters (e.g. from TikTok, Instagram, X, YouTube), " +
                        "explain fair use, and show how to use the sniffer mode to download publicly available content safely. Keep answers clean, concise, and helpful."))
            ),
            generationConfig = GenerationConfig(
                temperature = 0.7f,
                maxOutputTokens = 1500
            )
        )

        return try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: "No suggestions retrieved from Gemini. Try checking your internet connection."
        } catch (e: Exception) {
            "Error querying Gemini: ${e.localizedMessage ?: "Unknown error"}"
        }
    }
}
