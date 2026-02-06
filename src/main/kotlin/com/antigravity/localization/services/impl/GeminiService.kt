package com.antigravity.localization.services.impl

import com.antigravity.localization.services.TranslationService
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.antigravity.localization.services.QuotaExceededException

class GeminiService : TranslationService {
    override val name = "Gemini"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "Translate the following Android XML string value to $targetLang. " +
                (if (!context.isNullOrBlank()) "Context/Rules: $context. " else "") +
                "Do not include any explanations, just the translated string. Value: $text"

        val parts = JsonArray()
        parts.add(JsonObject().apply {
            addProperty("text", prompt)
        })

        val contents = JsonArray()
        contents.add(JsonObject().apply {
            add("parts", parts)
        })

        val requestBody = JsonObject().apply {
            add("contents", contents)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            val errorBody = response.body()
            try {
                val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                val error = jsonResponse.getAsJsonObject("error")
                val message = error.get("message").asString
                val status = error.get("status")?.asString
                val code = error.get("code")?.asInt

                if (response.statusCode() == 429 || status == "RESOURCE_EXHAUSTED" || code == 429) {
                    throw QuotaExceededException("Gemini Quota Exceeded: $message")
                }
                
                throw RuntimeException("Gemini Error: $message")
                
            } catch (e: Exception) {
                if (e is QuotaExceededException) throw e
                throw RuntimeException("Gemini translation failed (${response.statusCode()}): $errorBody")
            }
        }

        val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
        
        try {
            val candidates = jsonResponse.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val candidate = candidates.get(0).asJsonObject
                val content = candidate.getAsJsonObject("content")
                val partsResponse = content.getAsJsonArray("parts")
                if (partsResponse != null && partsResponse.size() > 0) {
                    return@withContext partsResponse.get(0).asJsonObject.get("text").asString.trim()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse Gemini response: ${response.body()}", e)
        }
        
        throw RuntimeException("Gemini returned unexpected response structure: ${response.body()}")
    }
}
