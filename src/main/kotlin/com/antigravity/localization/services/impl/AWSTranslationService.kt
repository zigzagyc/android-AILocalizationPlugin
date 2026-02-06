package com.antigravity.localization.services.impl

import com.antigravity.localization.services.TranslationService
import com.antigravity.localization.services.QuotaExceededException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.translate.TranslateClient
import software.amazon.awssdk.services.translate.model.TranslateTextRequest
import software.amazon.awssdk.services.translate.model.TranslateException

class AWSTranslationService : TranslationService {
    override val name = "AWS Translate"

    override suspend fun translate(text: String, targetLang: String, context: String?, apiKey: String): String = withContext(Dispatchers.IO) {
        // Expected format: ACCESS_KEY:SECRET_KEY:REGION
        val parts = apiKey.split(":")
        if (parts.size < 3) {
            throw RuntimeException("Invalid AWS Credentials format. Expected: ACCESS_KEY:SECRET_KEY:REGION")
        }

        val accessKey = parts[0]
        val secretKey = parts[1]
        val regionStr = parts[2]

        val region = try {
            Region.of(regionStr)
        } catch (e: Exception) {
            throw RuntimeException("Invalid AWS Region: $regionStr")
        }

        val credentials = AwsBasicCredentials.create(accessKey, secretKey)
        val client = TranslateClient.builder()
            .region(region)
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .build()

        try {
            val request = TranslateTextRequest.builder()
                .text(text)
                .sourceLanguageCode("auto")
                .targetLanguageCode(targetLang)
                .build()

            val response = client.translateText(request)
            response.translatedText()

        } catch (e: TranslateException) {
            // AWS ThrottlingException or LimitExceededException
            val errorCode = e.awsErrorDetails().errorCode()
            if (errorCode == "ThrottlingException" || errorCode == "LimitExceededException") {
                 throw QuotaExceededException("AWS Translate Limit Exceeded: ${e.message}")
            }
            if (errorCode == "UnrecognizedClientException" || errorCode == "InvalidClientTokenId") {
                 throw RuntimeException("AWS Auth Error: ${e.message}")
            }
            throw RuntimeException("AWS Translate Error: ${e.message}", e)
        } finally {
            client.close()
        }
    }
}
