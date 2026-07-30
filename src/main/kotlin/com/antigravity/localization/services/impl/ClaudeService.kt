package com.antigravity.localization.services.impl

import com.antigravity.localization.services.TranslationService
import com.antigravity.localization.services.TranslationVerificationResult
import com.antigravity.localization.services.QuotaExceededException
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ClaudeService : TranslationService {
    override val name = "Anthropic Claude"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()
    var model: String = "claude-3-7-sonnet-20250219"

    private fun getModelCandidates(): List<String> {
        return listOf(model, "claude-5-sonnet", "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229").distinct()
    }

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "Translate the following Android XML string value to $targetLang. " +
                (if (!context.isNullOrBlank()) "Context/Rules: $context. " else "") +
                "Do not include any explanations, just the translated string. Value: $text"

        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
        })

        var lastErrorMsg = ""

        for (candidateModel in getModelCandidates()) {
            val requestBody = JsonObject().apply {
                addProperty("model", candidateModel)
                addProperty("max_tokens", 1024)
                addProperty("system", "You are a helpful assistant that translates Android string resources accurately and concisely.")
                add("messages", messages)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                model = candidateModel
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                val contentArray = jsonResponse.getAsJsonArray("content")
                if (contentArray != null && contentArray.size() > 0) {
                    val firstContent = contentArray.get(0).asJsonObject
                    if (firstContent.has("text")) {
                        return@withContext firstContent.get("text").asString.trim()
                    }
                }
            } else {
                val errorBody = response.body()
                try {
                    val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                    val error = jsonResponse.getAsJsonObject("error")
                    val message = error.get("message").asString
                    val errorType = error.get("type")?.asString

                    if (response.statusCode() == 429 || errorType == "rate_limit_error") {
                        throw QuotaExceededException("Claude Quota Exceeded: $message")
                    }

                    if (response.statusCode() == 404 || errorType == "not_found_error" || message.contains("not found", ignoreCase = true)) {
                        lastErrorMsg = message
                        continue
                    }

                    throw RuntimeException("Claude Error: $message")
                } catch (e: Exception) {
                    if (e is QuotaExceededException) throw e
                    lastErrorMsg = "(${response.statusCode()}): $errorBody"
                }
            }
        }

        throw RuntimeException("Claude translation failed: $lastErrorMsg")
    }

    override suspend fun verifyTranslationContext(
        original: String,
        translated: String,
        contextList: List<String>,
        targetLang: String,
        apiKey: String
    ): TranslationVerificationResult = withContext(Dispatchers.IO) {
        if (contextList.isEmpty()) return@withContext TranslationVerificationResult()

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
            addProperty("role", "user")
            addProperty("content", prompt)
        })

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 1024)
            addProperty("system", "You are an expert Android UI reviewer and translator. Output valid raw JSON only.")
            add("messages", messages)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            try {
                val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                val contentArray = jsonResponse.getAsJsonArray("content")
                if (contentArray != null && contentArray.size() > 0) {
                    val text = contentArray.get(0).asJsonObject.get("text").asString.trim()
                    val cleanedText = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val resultJson = gson.fromJson(cleanedText, JsonObject::class.java)
                    return@withContext TranslationVerificationResult(
                        isTooLong = resultJson.get("isTooLong")?.asBoolean ?: false,
                        targetAbbreviationSuggestion = if (resultJson.has("targetAbbreviationSuggestion") && !resultJson.get("targetAbbreviationSuggestion").isJsonNull) resultJson.get("targetAbbreviationSuggestion").asString else null,
                        originalAbbreviationMeaning = if (resultJson.has("originalAbbreviationMeaning") && !resultJson.get("originalAbbreviationMeaning").isJsonNull) resultJson.get("originalAbbreviationMeaning").asString else null,
                        layoutAdaptabilitySuggestion = if (resultJson.has("layoutAdaptabilitySuggestion") && !resultJson.get("layoutAdaptabilitySuggestion").isJsonNull) resultJson.get("layoutAdaptabilitySuggestion").asString else null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return@withContext TranslationVerificationResult()
    }
}
