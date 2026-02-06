package com.antigravity.localization.services.impl

import com.antigravity.localization.services.TranslationService
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import com.antigravity.localization.services.QuotaExceededException

class GoogleTranslationService : TranslationService {
    override val name = "Google Translate"
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        // Note: Google Translate API (v2) doesn't natively support "context" in the same way LLMs do.
        // We can't easily inject context without potentially confusing the NMT model.
        // We will ignore 'context' for standard Google Translate, or we could prepend it but that's risky.
        // For now, we ignore context.

        val queryParams = "key=$apiKey"
        val uri = URI.create("https://translation.googleapis.com/language/translate/v2?$queryParams")

        val requestBody = JsonObject().apply {
            addProperty("q", text)
            addProperty("target", targetLang)
            addProperty("format", "text") // or html
        }

        val request = HttpRequest.newBuilder()
            .uri(uri)
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
                val code = error.get("code").asInt
                
                // Check errors array for more details regarding usageLimits
                val errorsArray = error.getAsJsonArray("errors")
                var isQuotaError = (code == 403 || code == 429)
                
                if (errorsArray != null && errorsArray.size() > 0) {
                    val firstError = errorsArray.get(0).asJsonObject
                    val domain = firstError.get("domain")?.asString
                    if (domain == "usageLimits") {
                        isQuotaError = true
                    }
                }

                if (isQuotaError) {
                    throw QuotaExceededException("Google Translate Quota Exceeded: $message")
                }

                throw RuntimeException("Google Translate Error: $message")

            } catch (e: Exception) {
                if (e is QuotaExceededException) throw e
                throw RuntimeException("Google Translate failed (${response.statusCode()}): $errorBody")
            }
        }

        val jsonResponse = gson.fromJson(response.body(), JsonObject::class.java)
        val data = jsonResponse.getAsJsonObject("data")
        val translations = data.getAsJsonArray("translations")

        if (translations != null && translations.size() > 0) {
            return@withContext translations.get(0).asJsonObject.get("translatedText").asString
        }

        throw RuntimeException("Google Translate returned no translations: ${response.body()}")
    }
}
