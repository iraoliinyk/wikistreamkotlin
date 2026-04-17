package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.config.WikiStreamProperties
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.exception.WikiStreamConfigurationError
import com.redspace.wikistreamkotlin.exception.WikiStreamConnectionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.reactive.asFlow
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import java.net.URI

@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val wikiEventParser: WikiEventParser,
    private val properties: WikiStreamProperties
) {

    init {
        validateProperties()
    }

    fun streamEvents(): Flow<WikiEvent> {
        return webClient.get()
            .uri(properties.url)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, properties.userAgent)
            .retrieve()
            .bodyToFlux<String>()
            .asFlow()
            .mapNotNull {
                wikiEventParser.parseEvent(it)
            }
            .catch { exception ->
                throw WikiStreamConnectionError(
                    message = "Failed to consume Wikimedia recent-change stream",
                    cause = exception
                )
            }
    }

    private fun validateProperties() {
        try {
            require(properties.url.isNotBlank()) { "wiki.stream.url must not be blank" }
            require(properties.userAgent.isNotBlank()) { "wiki.stream.user-agent must not be blank" }
            URI(properties.url)
        } catch (exception: Exception) {
            throw WikiStreamConfigurationError(
                message = "Invalid Wikimedia stream configuration",
                cause = exception
            )
        }
    }
}
