package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.exception.AppErrorLogLevel
import com.redspace.wikistreamkotlin.exception.AppErrorLogger
import com.redspace.wikistreamkotlin.exception.WikiEventParsingError
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class WikiEventParser(
    private val objectMapper: ObjectMapper,
    private val appErrorLogger: AppErrorLogger,
) {
    @Suppress("MagicNumber")
    fun parseEvent(sseLine: String): WikiEvent? =
        try {
            objectMapper.readValue(sseLine, WikiEvent::class.java)
        } catch (exception: Exception) {
            val maxLines = 200
            appErrorLogger.log(
                WikiEventParsingError(
                    message = "Failed to parse Wikimedia event payload",
                    cause = exception,
                ),
                level = AppErrorLogLevel.WARN,
                context = mapOf("payloadPreview" to sseLine.take(maxLines)),
            )
            null
        }
}
