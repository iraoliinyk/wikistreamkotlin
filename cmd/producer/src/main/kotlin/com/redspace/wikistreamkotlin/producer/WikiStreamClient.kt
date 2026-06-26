package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.producer.config.WikiStreamProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.netty.http.client.PrematureCloseException
import reactor.util.retry.Retry
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val properties: WikiStreamProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Last SSE event id we successfully observed. Wikimedia EventStreams honors the
    // standard `Last-Event-ID` header on reconnect, so persisting it across retries
    // eliminates the gap that would otherwise appear after every reconnect.
    private val lastEventId = AtomicReference<String?>(null)

    /**
     * Returns a cold Flow of raw SSE payload strings.
     *
     * Resilience model (defense in depth — outer runner ALSO retries):
     *  - We use `bodyToFlux(ServerSentEvent<String>)` so framing is handled by Spring
     *    (no risk of splitting an event across chunk boundaries).
     *  - On every successful frame we remember its `id` for resume.
     *  - On any transient transport failure (premature close, IO error, 5xx) we
     *    re-subscribe via `retryWhen` with exponential backoff + jitter, capped at 30s
     *    and effectively unlimited retries. Each re-subscription opens a brand new
     *    Netty connection and re-sends the `Last-Event-ID` header.
     */
    fun streamRawEvents(): Flow<String> =
        Flux.defer { buildRequest() }
            .doOnNext { sse -> sse.id()?.let { lastEventId.set(it) } }
            .mapNotNull { it.data() }
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(30))
                    .jitter(0.5)
                    .filter { ex -> ex.isTransient() }
                    .doBeforeRetry { sig ->
                        log.warn(
                            "Wikimedia SSE reconnect #{} (lastEventId={}) due to {}: {}",
                            sig.totalRetries() + 1,
                            lastEventId.get(),
                            sig.failure()::class.simpleName,
                            sig.failure().message,
                        )
                    },
            )
            .asFlow()

    /**
     * Builds a fresh GET request each subscription so headers (including the dynamic
     * `Last-Event-ID`) are recomputed on every reconnect attempt.
     */
    private fun buildRequest(): Flux<ServerSentEvent<String>> {
        val spec = webClient
            .get()
            .uri(properties.url)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, properties.userAgent)

        lastEventId.get()?.let { spec.header("Last-Event-ID", it) }

        return spec
            .retrieve()
            .bodyToFlux(SSE_STRING_TYPE)
    }

    /**
     * Classifies an exception as transient/retryable
     */
    private fun Throwable.isTransient(): Boolean = when (this) {
        is PrematureCloseException -> true
        is IOException -> true
        is WebClientResponseException ->
            // 5xx + 408 Request Timeout + 429 Too Many Requests are worth retrying.
            statusCode.is5xxServerError ||
                statusCode.value() == 408 ||
                statusCode.value() == 429
        else -> {
            cause?.let { it !== this && it.isTransient() } == true
        }
    }

    private companion object {
        private val SSE_STRING_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}