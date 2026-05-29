package com.redspace.wikistreamkotlin.producer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ProducerIngestionRunner(
    private val streamClient: WikiStreamClient,
    private val parser: WikiEventParser,
    private val publisher: RedpandaPublisher,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            streamClient.streamRawEvents().collect { raw ->
                parser.parseEvent(raw)?.let { publisher.publish(it) }
            }
        }
    }
}