package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.config.WikiStreamProperties
import com.redspace.wikistreamkotlin.domain.WikiEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import tools.jackson.databind.ObjectMapper

@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val properties: WikiStreamProperties
) {

    fun streamEvents(): Flow<WikiEvent> {
        return webClient.get()
            .uri(properties.url)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, properties.userAgent)
            .retrieve()
            .bodyToFlux<String>()
            .asFlow().mapNotNull {
                WikiEventParser.parseEvent(it, objectMapper)
            }
    }
}
