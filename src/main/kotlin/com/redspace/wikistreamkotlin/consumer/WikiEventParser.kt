package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.domain.WikiEvent
import tools.jackson.databind.ObjectMapper


object WikiEventParser {
    fun parseEvent(sseLine: String, objectMapper: ObjectMapper): WikiEvent? {
        return try {
            // 1. Remove the "data: " prefix
            val jsonPayload = sseLine.removePrefix("data:").trim()
            // 2. Parse JSON into Data Class
            objectMapper.readValue(jsonPayload, WikiEvent::class.java)
        } catch (e: Exception) {
//            TBD error-handling
            println("Failed to parse event: ${e.message}")
            null
        }
    }
}