package com.redspace.wikistreamkotlin.producer.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig {
    /**
     * Reactor-Netty backed [WebClient] tuned for the long-lived Wikimedia SSE stream.
     *
     *  Actual reconnect/backoff logic lives in
     * `com.redspace.wikistreamkotlin.producer.WikiStreamClient.streamRawEvents`; this
     * bean only ensures that broken streams are *detected* in a timely manner.
     */
    @Bean
    fun webClient(): WebClient {
        val httpClient = HttpClient.create()
            // Fail fast when the upstream server stops sending bytes
            // (the default is "wait forever", which used to hide stalls)
            .responseTimeout(Duration.ofSeconds(60))
            // cap how long DNS+TCP+TLS handshake can take
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .doOnConnected { conn ->
                // guarantee a `ReadTimeoutException` is raised if no bytes
                // arrive for 60s — surfaces zombie sockets to the retry layer
                conn.addHandlerLast(ReadTimeoutHandler(60))
                // symmetric write-side guard
                conn.addHandlerLast(WriteTimeoutHandler(30))
            }
            //keep the underlying TCP connection alive while streaming
            .keepAlive(true)
            // request gzip; SSE payloads compress very well
            .compress(true)

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            // buffer headroom for the largest SSE frames
            .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
            .build()
    }
}