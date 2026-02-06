package com.antigravity.localization.services

/**
 * Exception thrown when a translation service quota has been exceeded.
 */
class QuotaExceededException(message: String) : RuntimeException(message)
