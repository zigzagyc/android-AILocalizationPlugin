package com.antigravity.localization.services

data class TranslationVerificationResult(
    val isTooLong: Boolean = false,
    val targetAbbreviationSuggestion: String? = null,
    val originalAbbreviationMeaning: String? = null
)
