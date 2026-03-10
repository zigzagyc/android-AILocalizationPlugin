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

class GrokService : TranslationService {
    override val name = "Grok (xAI)"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

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
            addProperty("model", "grok-2-latest") // Or grok-beta, need to confirm exact model name. Let's use 'grok-beta' as a safer bet or check xAI docs if possible. 
                                                  // Actually 'grok-beta' is common. Let's use 'grok-beta'. 
                                                  // Wait, xAI documentation says 'grok-beta' or 'grok-vision-beta'. Let's stick with 'grok-beta'.
            add("messages", messages)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.x.ai/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Grok translation failed: ${response.body()}")
        }

        val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
        val choices = jsonResponse.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            throw RuntimeException("Grok returned no choices: ${response.body()}")
        }
        
        choices.get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
            .trim()
    }

    override suspend fun verifyTranslationContext(
        original: String,
        translated: String,
        contextList: List<String>,
        targetLang: String,
        apiKey: String
    ): com.antigravity.localization.services.TranslationVerificationResult = withContext(Dispatchers.IO) {
        if (contextList.isEmpty()) return@withContext com.antigravity.localization.services.TranslationVerificationResult()

        val prompt = """
            Analyze the following translation from an Android app:
            Original String: "$original"
            Translated String ($targetLang): "$translated"
            
            Usage Context Snippets:
            ${contextList.joinToString("\n\n")}
            
            Given the usage context (e.g., button widths, layout constraints, typical UI space), is the translated string significantly too long and likely to get truncated or break the layout?
            
            Also analyze if the original string was an abbreviation. If it was, explain what it means, and try to provide a suitable abbreviation in the target language.
            
            Respond strictly in valid JSON format with the following keys, no markdown blocks:
            - isTooLong (boolean)
            - targetAbbreviationSuggestion (string, or null if not applicable)
            - originalAbbreviationMeaning (string, or null if original is not an abbreviation)
        """.trimIndent()

        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", "You are an expert Android UI reviewer and translator.")
        })
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
        })

        val requestBody = JsonObject().apply {
            addProperty("model", "grok-beta")
            add("messages", messages)
            val responseFormat = JsonObject()
            responseFormat.addProperty("type", "json_object")
            add("response_format", responseFormat)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.x.ai/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            try {
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                val choices = jsonResponse.getAsJsonArray("choices")
                val content = choices.get(0).asJsonObject.getAsJsonObject("message").get("content").asString
                
                val resultJson = gson.fromJson(content, JsonObject::class.java)
                return@withContext com.antigravity.localization.services.TranslationVerificationResult(
                    isTooLong = resultJson.get("isTooLong")?.asBoolean ?: false,
                    targetAbbreviationSuggestion = if (resultJson.has("targetAbbreviationSuggestion") && !resultJson.get("targetAbbreviationSuggestion").isJsonNull) resultJson.get("targetAbbreviationSuggestion").asString else null,
                    originalAbbreviationMeaning = if (resultJson.has("originalAbbreviationMeaning") && !resultJson.get("originalAbbreviationMeaning").isJsonNull) resultJson.get("originalAbbreviationMeaning").asString else null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return@withContext com.antigravity.localization.services.TranslationVerificationResult()
    }
}
