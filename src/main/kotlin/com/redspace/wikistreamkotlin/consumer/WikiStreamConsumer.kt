package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.service.StatsService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

@Component
class WikiStreamConsumer(
    private val statsService: StatsService,
    private val wikiStreamClient: WikiStreamClient
) {

    companion object : KLogging() {
        private const val RETRY_DELAY_MS = 3_000L
    }

    private val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consumerJob: Job? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (consumerJob?.isActive == true) {
            return
        }

        consumerJob = consumerScope.launch {
            while (true) {
                runConsumerLoop()
                delay(RETRY_DELAY_MS.milliseconds)
            }
        }
    }

    private suspend fun runConsumerLoop() {
        wikiStreamClient.streamEvents()
            .catch { error ->
                logger.warn(error) { "Wikipedia stream disconnected; reconnecting" }
            }
            .collect { event ->
                statsService.record(event)
            }
    }

    @PreDestroy
    fun stop() {
        consumerScope.cancel()
    }
}