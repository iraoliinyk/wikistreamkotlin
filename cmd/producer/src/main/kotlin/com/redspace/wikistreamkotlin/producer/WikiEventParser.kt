package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.springframework.stereotype.Component
import com.fasterxml.jackson.databind.ObjectMapper

@Component
class WikiEventParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseEvent(raw: String): WikiEvent? =
        runCatching {
            objectMapper.readValue(raw, WikiEvent::class.java)
        }.getOrNull()
}