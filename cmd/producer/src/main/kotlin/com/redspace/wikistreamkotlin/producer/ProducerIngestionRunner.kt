package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ProducerStreamError
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import com.redspace.wikistreamkotlin.producer.metrics.ProducerMetricsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

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

    override fun run(vararg args: String) {
        runBlocking {
            val backoffMs = 1000
            try {
                log.info("Connecting to Wikimedia SSE stream...")
                streamClient.streamRawEvents().collect { raw ->
                    producerMetricsService.incrementEventsConsumedFromStream()
                    try {
                        parser.parseEvent(raw)?.let { event ->
                            publisher.publish(event)
                            producerMetricsService.incrementEventsPersistedToRedpanda()
                        }
                    } catch (ex: Exception) {
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
                log.info("SSE stream ended gracefully")
            } catch (ex: Exception) {
                errorLogger.log(
                    error = ProducerStreamError(
                        message = "SSE stream connection failed",
                        cause = ex,
                    ),
                    level = ErrorLogLevel.ERROR,
                )
                dlqPublisher.send("wikimedia-sse-stream", null, ex.localizedMessage, ex)
                producerMetricsService.incrementEventsFailedToPersist()
                throw ex
            }
            delay(backoffMs.milliseconds)
        }
    }
}
