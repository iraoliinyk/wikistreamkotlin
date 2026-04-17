package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.exception.AppError
import com.redspace.wikistreamkotlin.exception.AppErrorLogger
import com.redspace.wikistreamkotlin.exception.UnexpectedAppError
import com.redspace.wikistreamkotlin.service.StatsService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

@Component
class WikiStreamConsumer(
    private val statsService: StatsService,
    private val wikiStreamClient: WikiStreamClient,
    private val appErrorLogger: AppErrorLogger
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
                try {
                    runConsumerLoop()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (error: AppError) {
                    appErrorLogger.log(error, context = mapOf("component" to "WikiStreamConsumer"))
                } catch (exception: Exception) {
                    appErrorLogger.log(
                        UnexpectedAppError(
                            message = "Unexpected error while consuming Wikimedia stream",
                            cause = exception
                        ),
                        context = mapOf("component" to "WikiStreamConsumer")
                    )
                }
                delay(RETRY_DELAY_MS.milliseconds)
            }
        }
    }

    private suspend fun runConsumerLoop() {
        wikiStreamClient.streamEvents()
            .collect { event ->
                statsService.record(event)
            }
    }


    @PreDestroy
    fun stop() {
        consumerScope.cancel()
    }
}