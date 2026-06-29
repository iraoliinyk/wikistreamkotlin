package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ProducerStreamError
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import com.redspace.wikistreamkotlin.producer.metrics.ProducerMetricsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.seconds

@Component
class ProducerIngestionRunner(
    private val streamClient: WikiStreamClient,
    private val parser: WikiEventParser,
    private val publisher: RedpandaPublisher,
    private val dlqPublisher: DlqPublisher,
    private val errorLogger: ErrorLogger,
    private val producerMetricsService: ProducerMetricsService
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Drives the Wikimedia SSE → Redpanda pipeline forever.
     *
     * Crash-resilience rules (WebClientResponseException: 200 OK, PrematureCloseException
     * crash that used to kill the Spring context after 2–5 minutes)
     */
    override fun run(vararg args: String) {
        runBlocking {
            val initialBackoff = 1.seconds
            val maxBackoff = 30.seconds
            var backoff = initialBackoff

            while (isActive) {
                try {
                    log.info("Connecting to Wikimedia SSE stream...")
                    streamClient.streamRawEvents().collect { raw ->
                        // First successful record proves the new connection works → reset backoff
                        backoff = initialBackoff
                        producerMetricsService.incrementEventsConsumedFromStream()
                        try {
                            parser.parseEvent(raw)?.let { event ->
                                publisher.publish(event)
                                producerMetricsService.incrementEventsPersistedToRedpanda()
                            }
                        } catch (ex: Exception) {
                            // Legitimate DLQ use: a single record could not be parsed/published.
                            errorLogger.log(
                                error = ProducerStreamError(
                                    message = "Failed to parse or publish event",
                                    cause = ex,
                                ),
                                level = ErrorLogLevel.ERROR,
                                context = mapOf(
                                    "rawEvent" to raw.take(200) // Log snippet of raw event
                                )
                            )
                            dlqPublisher.send("wikimedia-sse-stream", null, raw, ex)
                            producerMetricsService.incrementEventsFailedToPersist()
                        }
                    }
                    // collect() returned normally — stream completed (server closed cleanly).
                    log.info(
                        "SSE stream ended gracefully; reconnecting in {} ms",
                        backoff.inWholeMilliseconds,
                    )
                } catch (ex: CancellationException) {
                    // Propagate cancellation so Spring can shut us down cleanly.
                    throw ex
                } catch (ex: Exception) {
                    // Transport-level failure (PrematureCloseException, ReadTimeoutException,
                    // 5xx, etc.). Log as WARN, DO NOT send to DLQ, DO NOT rethrow.
                    errorLogger.log(
                        error = ProducerStreamError(
                            message = "SSE stream connection failed; will reconnect",
                            cause = ex,
                        ),
                        level = ErrorLogLevel.WARN,
                        context = mapOf(
                            "backoffMs" to backoff.inWholeMilliseconds,
                        ),
                    )
                    producerMetricsService.incrementSseReconnects()
                }

                delay(backoff)
                // Exponential backoff capped at maxBackoff. Reset to initialBackoff happens
                // inside collect() the moment a new connection delivers its first record.
                backoff = (backoff * 2).coerceAtMost(maxBackoff)
            }
        }
    }
}
