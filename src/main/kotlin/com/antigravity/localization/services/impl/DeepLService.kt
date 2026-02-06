package com.antigravity.localization.services.impl

import com.antigravity.localization.services.TranslationService
import com.antigravity.localization.services.QuotaExceededException
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DeepLService : TranslationService {
    override val name = "DeepL"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        // Determine endpoint based on API key type
        val isFree = apiKey.endsWith(":fx")
        val baseUrl = if (isFree) "https://api-free.deepl.com/v2" else "https://api.deepl.com/v2"
        
        // DeepL expects context as 'context' parameter, but it's only supported for some languages/tiers.
        // We will try to include it if provided.
        
        val requestBody = JsonObject().apply {
            addProperty("text", arrayListOf(text).toString()) // DeepL expects text array if using JSON, but let's stick to simple form if possible, or correct JSON structure
            // Actually DeepL JSON API expects: { "text": ["Hello"], "target_lang": "DE" }
        }
        
        // Let's use the JSON interface properly
        // We need to pass the text as an array
        val textArray = com.google.gson.JsonArray()
        textArray.add(text)
        
        val jsonBody = JsonObject().apply {
            add("text", textArray)
            addProperty("target_lang", targetLang.uppercase())
             if (!context.isNullOrBlank()) {
                 addProperty("context", context)
             }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/translate"))
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(jsonBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            val errorBody = response.body()
             try {
                // formatted like: {"message": "..."}
                val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                val message = jsonResponse.get("message")?.asString ?: "Unknown error"
                
                if (response.statusCode() == 456) {
                    throw QuotaExceededException("DeepL Quota Exceeded: $message")
                }
                if (response.statusCode() == 403) {
                     throw RuntimeException("DeepL Authorization Failed: $message (Check your API Key)")
                }

                throw RuntimeException("DeepL Error (${response.statusCode()}): $message")

            } catch (e: Exception) {
                if (e is QuotaExceededException) throw e
                throw RuntimeException("DeepL translation failed (${response.statusCode()}): $errorBody")
            }
        }

        val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
        val translations = jsonResponse.getAsJsonArray("translations")
        if (translations != null && translations.size() > 0) {
            return@withContext translations.get(0).asJsonObject.get("text").asString
        }

        throw RuntimeException("DeepL returned no translations: ${response.body()}")
    }
}
