package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.WikiStreamConsumer.Companion.logger
import com.redspace.wikistreamkotlin.domain.WikiEvent
import tools.jackson.databind.ObjectMapper

object WikiEventParser {
    fun parseEvent(sseLine: String, objectMapper: ObjectMapper): WikiEvent? {
        return try {
            objectMapper.readValue(sseLine, WikiEvent::class.java)
        } catch (exception: Exception) {
//            tbd error-handling
            logger.debug("Skipping malformed SSE payload", exception)
            null
        }
    }
}