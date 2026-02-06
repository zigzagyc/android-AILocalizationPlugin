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

class MicrosoftTranslationService : TranslationService {
    override val name = "Microsoft Translator"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        // Microsoft Translator also doesn't support context directly in the robust way LLMs do.
        // We will ignore context for now.
        
        // Note: apiKey here might need to be "Key:Region" if region is required, or passed separately.
        // For simplicity, we assume the user might append region to key like "KEY:REGION" or we just use global.
        // Let's try to detect if a region is needed. 
        // Actually, typically the header 'Ocp-Apim-Subscription-Region' is needed if not global.
        // We will assume global for now, or maybe parse the key if it contains a separator. 
        // Let's implement basic global.

        var actualKey = apiKey
        var region: String? = null
        
        if (apiKey.contains(":")) {
            val parts = apiKey.split(":", limit = 2)
            actualKey = parts[0]
            region = parts[1]
        }

        val uri = URI.create("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=$targetLang")

        val requestBody = JsonArray()
        requestBody.add(JsonObject().apply {
            addProperty("Text", text)
        })

        val requestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .header("Ocp-Apim-Subscription-Key", actualKey)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))

        if (region != null) {
            requestBuilder.header("Ocp-Apim-Subscription-Region", region)
        }


        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            val errorBody = response.body()
            try {
                val jsonResponse = gson.fromJson(errorBody, JsonObject::class.java)
                val error = jsonResponse.getAsJsonObject("error")
                val message = error.get("message").asString
                val code = error.get("code")?.asInt

                if (response.statusCode() == 429) { // Microsoft Translator uses 429 for quota
                     throw QuotaExceededException("Microsoft Translator Quota Exceeded: $message")
                }
                
                throw RuntimeException("Microsoft Translator Error: $message")

            } catch (e: Exception) {
                if (e is QuotaExceededException) throw e
                throw RuntimeException("Microsoft Translator failed (${response.statusCode()}): $errorBody")
            }
        }

        val jsonResponse = gson.fromJson(response.body(), JsonArray::class.java)
        if (jsonResponse != null && jsonResponse.size() > 0) {
            val item = jsonResponse.get(0).asJsonObject
            val translations = item.getAsJsonArray("translations")
            if (translations != null && translations.size() > 0) {
                return@withContext translations.get(0).asJsonObject.get("text").asString
            }
        }

        throw RuntimeException("Microsoft Translator returned no translations: ${response.body()}")
    }
}
