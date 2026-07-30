package com.antigravity.localization.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object ModelFetcher {

    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    suspend fun fetchModelsForService(service: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        return@withContext when (service) {
            "OpenAI (ChatGPT)" -> fetchOpenAIModels(apiKey)
            "Gemini" -> fetchGeminiModels(apiKey)
            "Grok (xAI)" -> fetchGrokModels(apiKey)
            else -> emptyList()
        }
    }

    private fun fetchOpenAIModels(apiKey: String): List<String> {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/models"))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val data = json.getAsJsonArray("data") ?: return emptyList()
                val models = mutableListOf<String>()
                for (i in 0 until data.size()) {
                    val id = data.get(i).asJsonObject.get("id").asString
                    val isNonChatModel = id.contains("embedding") || id.contains("whisper") || id.contains("tts") || id.contains("dall-e") || id.contains("babbage") || id.contains("davinci") || id.contains("moderation")
                    if (!isNonChatModel && (id.startsWith("gpt") || id.startsWith("o") || id.startsWith("chatgpt"))) {
                        models.add(id)
                    }
                }
                return models.sorted()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
    }

    private fun fetchGeminiModels(apiKey: String): List<String> {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val modelsArray = json.getAsJsonArray("models") ?: return emptyList()
                val models = mutableListOf<String>()
                for (i in 0 until modelsArray.size()) {
                    val obj = modelsArray.get(i).asJsonObject
                    val name = obj.get("name").asString.removePrefix("models/")
                    val methods = obj.getAsJsonArray("supportedGenerationMethods")
                    val supportsGenerate = methods != null && methods.toString().contains("generateContent")
                    if (supportsGenerate && name.contains("gemini")) {
                        models.add(name)
                    }
                }
                if (models.isNotEmpty()) return models.sorted()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return listOf("gemini-3.5-flash", "gemini-3.5-pro", "gemini-3.0-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash-latest", "gemini-1.5-flash", "gemini-2.5-pro")
    }

    private fun fetchGrokModels(apiKey: String): List<String> {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.x.ai/v1/models"))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val json = gson.fromJson(response.body(), JsonObject::class.java)
                val data = json.getAsJsonArray("data") ?: return emptyList()
                val models = mutableListOf<String>()
                for (i in 0 until data.size()) {
                    val id = data.get(i).asJsonObject.get("id").asString
                    models.add(id)
                }
                if (models.isNotEmpty()) return models.sorted()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return listOf("grok-beta", "grok-2", "grok-2-mini", "grok-vision-beta")
    }
}
