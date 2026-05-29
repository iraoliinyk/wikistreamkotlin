package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.producer.config.WikiStreamProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux

@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val properties: WikiStreamProperties,
) {
    fun streamRawEvents(): Flow<String> =
        webClient
            .get()
            .uri(properties.url)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, properties.userAgent)
            .retrieve()
            .bodyToFlux<String>()
            .asFlow()
}