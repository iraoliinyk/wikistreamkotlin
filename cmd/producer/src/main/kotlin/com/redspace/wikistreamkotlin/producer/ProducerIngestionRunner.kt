package com.redspace.wikistreamkotlin.producer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class ProducerIngestionRunner(
    private val streamClient: WikiStreamClient,
    private val parser: WikiEventParser,
    private val publisher: RedpandaPublisher,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        runBlocking {
            while (true) {
                try {
                    log.info("Connecting to Wikimedia SSE stream...")
                    streamClient.streamRawEvents().collect { raw ->
                        parser.parseEvent(raw)?.let { event -> publisher.publish(event) }
                    }
                    log.warn("SSE stream ended — reconnecting in 3 s")
                } catch (ex: Exception) {
                    log.warn("SSE stream error ({}), reconnecting in 3 s", ex.message)
                }
                delay(3_000)
            }
        }
    }
}
