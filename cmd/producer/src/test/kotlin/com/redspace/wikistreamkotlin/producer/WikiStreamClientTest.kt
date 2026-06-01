package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.producer.config.WikiStreamProperties
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class WikiStreamClientTest {
    @Test
    fun `builds request with text event stream accept header`() {
        val exchange = RecordingExchangeFunction()
        val client = wikiStreamClient(exchange)

        runBlocking { client.streamRawEvents().collect() }

        assertEquals(listOf(MediaType.TEXT_EVENT_STREAM_VALUE), exchange.lastRequest!!.headers()[HttpHeaders.ACCEPT])
    }

    @Test
    fun `sets configured user agent header`() {
        val exchange = RecordingExchangeFunction()
        val client = wikiStreamClient(exchange)

        runBlocking { client.streamRawEvents().collect() }

        assertEquals("wikistream-producer/test", exchange.lastRequest!!.headers()[HttpHeaders.USER_AGENT]?.single())
    }

    @Test
    fun `targets configured URL`() {
        val exchange = RecordingExchangeFunction()
        val client = wikiStreamClient(exchange)

        runBlocking { client.streamRawEvents().collect() }

        assertEquals("https://stream.example.test/recentchange", exchange.lastRequest!!.url().toString())
    }

    private fun wikiStreamClient(exchange: RecordingExchangeFunction): WikiStreamClient {
        val webClient = WebClient.builder().exchangeFunction(exchange).build()
        val properties =
            WikiStreamProperties(
                url = "https://stream.example.test/recentchange",
                userAgent = "wikistream-producer/test",
            )
        return WikiStreamClient(webClient, properties)
    }

    private class RecordingExchangeFunction : ExchangeFunction {
        var lastRequest: ClientRequest? = null

        override fun exchange(request: ClientRequest): Mono<ClientResponse> {
            lastRequest = request
            return Mono.just(
                ClientResponse
                    .create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body("event-1")
                    .build(),
            )
        }
    }
}
