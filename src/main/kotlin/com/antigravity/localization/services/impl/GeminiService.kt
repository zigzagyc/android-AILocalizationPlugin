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
    var model: String = "gemini-3.5-flash"

    private fun getModelCandidates(): List<String> {
        return listOf(model, "gemini-3.5-flash", "gemini-3.5-pro", "gemini-3.0-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash-latest", "gemini-1.5-flash", "gemini-2.5-pro").distinct()
    }

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

        val candidatesToTry = getModelCandidates()
        var lastErrorMsg = ""

        for (candidateModel in candidatesToTry) {
            var attempts = 0
            val backoffs = listOf(2000L, 4000L, 8000L)
            var response: HttpResponse<String>? = null

            while (attempts <= backoffs.size) {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"))
                    .header("Content-Type", "application/json")
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
                model = candidateModel // Remember working model
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
            } else if (response != null) {
                val errorBody = response.body()
                try {
                    val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                    val error = jsonResponse.getAsJsonObject("error")
                    val message = error.get("message").asString
                    val status = error.get("status")?.asString
                    val code = error.get("code")?.asInt

                    if (response.statusCode() == 400 || response.statusCode() == 401 || message.contains("API key not valid", ignoreCase = true) || message.contains("API_KEY_INVALID", ignoreCase = true)) {
                        throw RuntimeException("Invalid Gemini API Key. Please get a valid API key at https://aistudio.google.com/app/apikey")
                    }

                    if (response.statusCode() == 429 || status == "RESOURCE_EXHAUSTED" || code == 429) {
                        throw QuotaExceededException("Gemini Quota Exceeded: $message")
                    }

                    // If 404 or model not found, try next candidate model
                    if (response.statusCode() == 404 || message.contains("not found", ignoreCase = true)) {
                        lastErrorMsg = message
                        continue
                    }

                    throw RuntimeException("Gemini Error: $message")
                } catch (e: Exception) {
                    if (e is QuotaExceededException) throw e
                    lastErrorMsg = "(${response.statusCode()}): $errorBody"
                }
            }
        }

        throw RuntimeException("Gemini translation failed: $lastErrorMsg")
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

        val parts = JsonArray()
        parts.add(JsonObject().apply {
            addProperty("text", prompt)
        })

        val contents = JsonArray()
        contents.add(JsonObject().apply {
            addProperty("role", "user")
            add("parts", parts)
        })

        val requestBody = JsonObject().apply {
            add("contents", contents)
            val generationConfig = JsonObject()
            generationConfig.addProperty("response_mime_type", "application/json")
            add("generationConfig", generationConfig)
            val systemInstruction = JsonObject().apply {
                val sysParts = JsonArray()
                sysParts.add(JsonObject().apply {
                    addProperty("text", "You are an expert Android UI reviewer and translator.")
                })
                add("parts", sysParts)
            }
            add("systemInstruction", systemInstruction)
        }

        for (candidateModel in getModelCandidates()) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                try {
                    val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
                    val candidates = jsonResponse.getAsJsonArray("candidates")
                    if (candidates != null && candidates.size() > 0) {
                        val candidate = candidates.get(0).asJsonObject
                        val content = candidate.getAsJsonObject("content")
                        val partsResponse = content.getAsJsonArray("parts")
                        if (partsResponse != null && partsResponse.size() > 0) {
                            val text = partsResponse.get(0).asJsonObject.get("text").asString.trim()
                            val resultJson = gson.fromJson(text, JsonObject::class.java)
                            return@withContext com.antigravity.localization.services.TranslationVerificationResult(
                                isTooLong = resultJson.get("isTooLong")?.asBoolean ?: false,
                                targetAbbreviationSuggestion = if (resultJson.has("targetAbbreviationSuggestion") && !resultJson.get("targetAbbreviationSuggestion").isJsonNull) resultJson.get("targetAbbreviationSuggestion").asString else null,
                                originalAbbreviationMeaning = if (resultJson.has("originalAbbreviationMeaning") && !resultJson.get("originalAbbreviationMeaning").isJsonNull) resultJson.get("originalAbbreviationMeaning").asString else null,
                                layoutAdaptabilitySuggestion = if (resultJson.has("layoutAdaptabilitySuggestion") && !resultJson.get("layoutAdaptabilitySuggestion").isJsonNull) resultJson.get("layoutAdaptabilitySuggestion").asString else null
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return@withContext com.antigravity.localization.services.TranslationVerificationResult()
    }
}
