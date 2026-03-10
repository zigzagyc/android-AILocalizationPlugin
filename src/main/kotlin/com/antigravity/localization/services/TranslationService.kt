package com.antigravity.localization.services

/**
 * Interface for translation services.
 */
interface TranslationService {
    val name: String
    
    /**
     * Translates the given text to the target language.
     *
     * @param text The text to translate.
     * @param targetLang The ISO 639-1 code of the target language.
     * @param context Optional context or glossary instructions provided by the user.
     * @param apiKey The API key for the service.
     * @return The translated text.
     */
    suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String
    
    /**
     * Verifies if a translated string is appropriate given its usage context.
     */
    suspend fun verifyTranslationContext(
        original: String,
        translated: String,
        contextList: List<String>,
        targetLang: String,
        apiKey: String
    ): TranslationVerificationResult {
        // Default implementation to avoid breaking all existing services immediately.
        // Can be overridden by AI services.
        return TranslationVerificationResult(isTooLong = false)
    }
}
