package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.service.StatsService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

@Component
class WikiStreamConsumer(
    val statsService: StatsService,
    val webClient: WebClient,
    val objectMapper: ObjectMapper
) {

    companion object {
        const val WIKI_URL = "https://stream.wikimedia.org/v2/stream/recentchange"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        webClient.get()
            .uri(WIKI_URL)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, "wiki-stream-consumer/1.0 (https://github.com/you/wiki-stream)")
            .retrieve()
            .bodyToFlux(String::class.java)
            .mapNotNull { WikiEventParser.parseEvent(it, objectMapper) }
            .subscribe { statsService.record(it) }
    }
}