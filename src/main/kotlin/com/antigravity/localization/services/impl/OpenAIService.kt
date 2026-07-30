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
    
    var model: String = "gpt-4o"

    private fun getModelCandidates(): List<String> {
        return listOf(model, "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo").distinct()
    }

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

        var lastErrorMsg = ""

        for (candidateModel in getModelCandidates()) {
            val requestBody = JsonObject().apply {
                addProperty("model", candidateModel)
                add("messages", messages)
            }

            var attempts = 0
            val backoffs = listOf(2000L, 4000L, 8000L)
            var response: HttpResponse<String>? = null

            while (attempts <= backoffs.size) {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .build()

                response = client.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() == 429 && attempts < backoffs.size) {
                    Thread.sleep(backoffs[attempts])
                    attempts++
                    continue
                }
                break
            }

            if (response != null && response.statusCode() == 200) {
                model = candidateModel
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                val choices = jsonResponse.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    return@withContext choices.get(0).asJsonObject
                        .getAsJsonObject("message")
                        .get("content").asString
                        .trim()
                }
            } else if (response != null) {
                val errorBody = response.body()
                try {
                    val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                    val error = jsonResponse.getAsJsonObject("error")
                    val message = error.get("message").asString
                    val type = error.get("type")?.asString
                    val code = error.get("code")?.asString

                    if (response.statusCode() == 401 || type == "invalid_request_error" && message.contains("API key", ignoreCase = true)) {
                        throw RuntimeException("Invalid OpenAI API Key. Please get a valid API key at https://platform.openai.com/api-keys")
                    }

                    if (response.statusCode() == 429 || type == "insufficient_quota" || code == "insufficient_quota") {
                        throw QuotaExceededException("OpenAI Quota Exceeded: $message")
                    }

                    if (response.statusCode() == 404 || message.contains("not found", ignoreCase = true) || message.contains("does not exist", ignoreCase = true)) {
                        lastErrorMsg = message
                        continue
                    }

                    throw RuntimeException("OpenAI Error: $message")
                } catch (e: Exception) {
                    if (e is QuotaExceededException) throw e
                    lastErrorMsg = "(${response.statusCode()}): $errorBody"
                }
            }
        }

        throw RuntimeException("OpenAI translation failed: $lastErrorMsg")
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
            
            Given the usage context (e.g., button widths, layout constraints, maxLines, view type), is the translated string significantly too long and likely to get truncated or break the layout?
            
            Please provide TWO distinct suggestions if it might be too long or constrained:
            1. Text Shortening: Suggest a shorter phrasing or abbreviation in the target language.
            2. Layout Adaptability: Suggest concrete layout modifications (e.g., add app:autoSizeTextType="uniform", set android:ellipsize="end", set android:maxLines="2", or adjust layout constraints).
            
            Respond strictly in valid JSON format with the following keys, no markdown blocks:
            - isTooLong (boolean)
            - targetAbbreviationSuggestion (string, or null if not applicable)
            - originalAbbreviationMeaning (string, or null if original is not an abbreviation)
            - layoutAdaptabilitySuggestion (string, or null if not applicable)
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
            addProperty("model", model)
            add("messages", messages)
            val responseFormat = JsonObject()
            responseFormat.addProperty("type", "json_object")
            add("response_format", responseFormat)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
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
                    originalAbbreviationMeaning = if (resultJson.has("originalAbbreviationMeaning") && !resultJson.get("originalAbbreviationMeaning").isJsonNull) resultJson.get("originalAbbreviationMeaning").asString else null,
                    layoutAdaptabilitySuggestion = if (resultJson.has("layoutAdaptabilitySuggestion") && !resultJson.get("layoutAdaptabilitySuggestion").isJsonNull) resultJson.get("layoutAdaptabilitySuggestion").asString else null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return@withContext com.antigravity.localization.services.TranslationVerificationResult()
    }
}
