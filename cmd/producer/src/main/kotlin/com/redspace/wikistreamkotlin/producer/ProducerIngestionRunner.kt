package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ProducerStreamError
import com.redspace.wikistreamkotlin.producer.metrics.ProducerMetricsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@Component
class ProducerIngestionRunner(
    private val streamClient: WikiStreamClient,
    private val parser: WikiEventParser,
    private val publisher: RedpandaPublisher,
    private val errorLogger: ErrorLogger,
    private val producerMetricsService: ProducerMetricsService
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        runBlocking {
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 5
            while (consecutiveFailures < maxConsecutiveFailures) {
                try {
                    log.info("Connecting to Wikimedia SSE stream...")
                    consecutiveFailures = 0
                    streamClient.streamRawEvents().collect { raw ->
                        producerMetricsService.incrementEventsConsumedFromStream()
                        parser.parseEvent(raw)?.let { event ->
                            publisher.publish(event)
                            producerMetricsService.incrementEventsPersistedToRedpanda()
                        }
                    }
                    log.info("SSE stream ended gracefully")
                } catch (ex: Exception) {
                    consecutiveFailures++
                    val backoffMs = calculateBackoff(consecutiveFailures)

                    errorLogger.log(
                        error = ProducerStreamError(
                            message = "SSE stream connection failed (attempt $consecutiveFailures/$maxConsecutiveFailures)",
                            cause = ex,
                        ),
                        level = ErrorLogLevel.WARN,
                        context = mapOf(
                            "backoffMs" to backoffMs,
                            "exceptionType" to ex::class.simpleName,
                        ),
                    )

                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        errorLogger.log(
                            error = ProducerStreamError(
                                message = "Max consecutive failures ($maxConsecutiveFailures) reached. Stopping ingestion.",
                                cause = ex,
                            ),
                            level = ErrorLogLevel.ERROR,
                        )
                        producerMetricsService.incrementEventsFailedToPersist()
                        throw ex
                    }

                    delay(backoffMs.milliseconds)
                }
            }
        }
    }

    private fun calculateBackoff(attemptNumber: Int): Long {
        val backoffSeconds = min(1L shl (attemptNumber - 1), 16L)
        return backoffSeconds * 1000
    }
}
