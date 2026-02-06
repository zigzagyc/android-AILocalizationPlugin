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

class OpenAIService : TranslationService {
    override val name = "OpenAI (ChatGPT)"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()
    
    // Default model, can be changed
    var model = "gpt-4o"

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "Translate the following Android XML string value to $targetLang. " +
                (if (!context.isNullOrBlank()) "Context/Rules: $context. " else "") +
                "Do not include any explanations, just the translated string. Value: $text"

        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", "You are a helpful assistant that translates Android string resources.")
        })
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
        })

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            val errorBody = response.body()
            try {
                val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                val error = jsonResponse.getAsJsonObject("error")
                val message = error.get("message").asString
                val type = error.get("type")?.asString
                val code = error.get("code")?.asString

                if (response.statusCode() == 429 || type == "insufficient_quota" || code == "insufficient_quota") {
                    throw QuotaExceededException("OpenAI Quota Exceeded: $message")
                }

                throw RuntimeException("OpenAI Error: $message")
            } catch (e: Exception) {
                if (e is QuotaExceededException) throw e
                // Fallback if JSON parsing fails
                throw RuntimeException("OpenAI translation failed (${response.statusCode()}): $errorBody")
            }
        }

        val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
        val choices = jsonResponse.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw RuntimeException("OpenAI returned no choices: ${response.body()}")
        }
        
        choices.get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
            .trim()
    }
}
