package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.service.StatsService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

class WikiEventParser {
    fun parseEvent(event: String): WikiEvent? {
        return null
    }
}