package com.redspace.wikistreamkotlin.consumer.config

import mu.KotlinLogging
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Graceful shutdown: stops all Kafka listener containers so that
 * in-progress suspend funs finish and commit their offsets before the JVM exits.
 *
 * No need to query a batch state table — Kafka redelivers any uncommitted offset automatically.
 *
 * Implementation note: we use the asynchronous `stop(Runnable)` variant on each container and
 * await every callback via a `CountDownLatch`. This is the only API on
 * `MessageListenerContainer` that reliably signals when in-flight listener
 * invocations (including coroutine `suspend` funs) have completed.
 */
@Component
class GracefulShutdownHandler(
    private val kafkaRegistry: KafkaListenerEndpointRegistry,
) : SmartLifecycle {

    @Volatile private var running = false

    override fun start() {
        kafkaRegistry.start()
        running = true
        logger.info { "Kafka listeners started" }
    }

    override fun stop() = stop {}

    override fun stop(callback: Runnable) {
        logger.info { "Stopping Kafka listeners for graceful shutdown…" }
        try {
            val containers = kafkaRegistry.listenerContainers.toList()
            val latch = CountDownLatch(containers.size)

            containers.forEach { container ->
                container.stop { latch.countDown() }
            }

            val drained = latch.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (drained) {
                logger.info { "All ${containers.size} Kafka listener containers drained and stopped" }
            } else {
                logger.warn {
                    "Graceful shutdown timed out after ${SHUTDOWN_TIMEOUT_SECONDS}s; " +
                        "${latch.count}/${containers.size} containers did not drain in time"
                }
            }
        } catch (ex: Exception) {
            logger.error(ex) { "Error during graceful shutdown" }
        } finally {
            running = false
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running
    override fun getPhase(): Int = Int.MAX_VALUE - 1000

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 60L
    }
}