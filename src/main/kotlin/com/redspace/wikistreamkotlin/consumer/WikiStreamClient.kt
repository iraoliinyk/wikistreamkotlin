package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.domain.WikiEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value($$"${wiki.stream.url:}")
    private val streamUrl: String,
    @Value($$"${wiki.stream.user-agent:}")
    private val userAgent: String
) {

    fun streamEvents(): Flow<WikiEvent> {
        return webClient.get()
            .uri(streamUrl)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, userAgent)
            .retrieve()
            .bodyToFlux(String::class.java)
            .asFlow().mapNotNull {
                WikiEventParser.parseEvent(it, objectMapper)
            }
    }
}
