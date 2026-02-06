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
}
