package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import com.redspace.wikistreamkotlin.producer.config.WikiStreamProperties
import com.redspace.wikistreamkotlin.producer.exception.ProducerErrorLogger
import com.redspace.wikistreamkotlin.producer.metrics.ProducerMetricsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProducerIngestionRunnerTest {
    private val publisher = RecordingPublisher(expectedPublishes = 1)
    private val errorLogger = ProducerErrorLogger()
    private val metrics = Mockito.mock(ProducerMetricsService::class.java)
    private val dlqPublisher = Mockito.mock(DlqPublisher::class.java)

    @Test
    fun `publishes parsed events from stream`() {
        val parser = RecordingParser(mapOf("valid" to wikiEvent(id = 1L)))
        val streamClient = SequencedWikiStreamClient(listOf(flow { emit("valid") }, emptyFlow()))
        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = publisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = metrics
        )

        runRunnerInBackground(runner).use {
            assertTrue(publisher.awaitPublishes())
            assertEquals(listOf(1L), publisher.published.mapNotNull { it.id })
        }
    }

    @Test
    fun `ignores events that parser returns null for`() {
        val parser = RecordingParser(emptyMap())
        val streamClient = SequencedWikiStreamClient(listOf(flow { emit("ignored") }, emptyFlow()))
        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = publisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = metrics
        )
        runRunnerInBackground(runner).use {
            Thread.sleep(200)
            assertTrue(publisher.published.isEmpty())
            assertEquals(listOf("ignored"), parser.parsedRawValues)
        }
    }

    @Test
    fun `sends corrupted events to DLQ when parser throws exception`() {
        val corruptedRaw = """{"invalid": "json structure}"""
        val parseException = RuntimeException("Failed to parse JSON")

        val parser = ThrowingParser(parseException)
        val streamClient = SequencedWikiStreamClient(
            listOf(
                flow { emit(corruptedRaw) },
                emptyFlow()
            )
        )

        val dlqPublisher = RecordingDlqPublisher()

        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = publisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = metrics
        )

        runRunnerInBackground(runner).use {
            // Wait for the error to be processed
            assertTrue(dlqPublisher.awaitSend(timeoutMillis = 1000))

            // Validate DLQ was called with correct parameters
            assertEquals(1, dlqPublisher.sentRecords.size)

            val sentRecord = dlqPublisher.sentRecords.first()
            assertEquals("wikimedia-sse-stream", sentRecord.sourceName)
            assertEquals(null, sentRecord.key)
            assertEquals(corruptedRaw, sentRecord.value)
            assertEquals(parseException, sentRecord.exception)
        }

        // Verify metrics were updated
        Mockito.verify(metrics).incrementEventsFailedToPersist()

        // Verify publisher was never called
        assertTrue(publisher.published.isEmpty())
    }

    @Test
    fun `sends events to DLQ when publisher throws exception`() {
        val event = wikiEvent(id = 99L)
        val publishException = RuntimeException("Kafka connection failed")

        val parser = RecordingParser(mapOf("valid" to event))
        val throwingPublisher = ThrowingPublisher(publishException)
        val streamClient = SequencedWikiStreamClient(
            listOf(
                flow { emit("valid") },
                emptyFlow()
            )
        )

        val dlqPublisher = RecordingDlqPublisher()

        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = throwingPublisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = metrics
        )

        runRunnerInBackground(runner).use {
            assertTrue(dlqPublisher.awaitSend(timeoutMillis = 1000))

            assertEquals(1, dlqPublisher.sentRecords.size)
            val sentRecord = dlqPublisher.sentRecords.first()
            assertEquals("wikimedia-sse-stream", sentRecord.sourceName)
            assertEquals("valid", sentRecord.value)
            assertEquals(publishException, sentRecord.exception)
        }

        Mockito.verify(metrics).incrementEventsFailedToPersist()
    }


    private fun wikiEvent(id: Long): WikiEvent =
        WikiEvent(
            schema = "mediawiki/recentchange/1.0.0",
            meta = null,
            id = id,
            type = "edit",
            namespace = 0,
            title = "Main Page",
            titleUrl = null,
            comment = null,
            timestamp = 1L,
            user = "tester",
            bot = false,
            notifyUrl = null,
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = null,
        )

    private fun runRunnerInBackground(runner: ProducerIngestionRunner): AutoCloseable {
        val thread =
            Thread({ runner.run() }, "producer-ingestion-runner-test").apply {
                isDaemon = true
                start()
            }

        return AutoCloseable {
            thread.interrupt()
            thread.join(200)
        }
    }

    private class SequencedWikiStreamClient(
        private val flows: List<Flow<String>>,
    ) : WikiStreamClient(
        webClient = Mockito.mock(WebClient::class.java),
        properties = WikiStreamProperties(
            url = "https://example.test",
            userAgent = "test-agent"
        ),
    ) {
        val invocationCount = AtomicInteger(0)

        override fun streamRawEvents(): Flow<String> {
            val index = invocationCount.getAndIncrement()
            return flows.getOrElse(index) { emptyFlow() }
        }
    }

    private class RecordingParser(
        private val mappings: Map<String, WikiEvent?>,
    ) : WikiEventParser(
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
    ) {
        val parsedRawValues = CopyOnWriteArrayList<String>()

        override fun parseEvent(raw: String): WikiEvent? {
            parsedRawValues += raw
            return mappings[raw]
        }
    }

    private class RecordingPublisher(
        expectedPublishes: Int,
    ) : RedpandaPublisher(
        kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, WikiEvent>,
    ) {
        val published = CopyOnWriteArrayList<WikiEvent>()
        private val latch = CountDownLatch(expectedPublishes)

        override fun publish(event: WikiEvent) {
            published += event
            latch.countDown()
        }

        fun awaitPublishes(timeoutMillis: Long = 1500): Boolean = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private class ThrowingParser(
        private val exceptionToThrow: Exception
    ) : WikiEventParser(
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
    ) {
        override fun parseEvent(raw: String): WikiEvent? {
            throw exceptionToThrow
        }
    }

    private class ThrowingPublisher(
        private val exceptionToThrow: Exception
    ) : RedpandaPublisher(
        kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, WikiEvent>,
    ) {
        override fun publish(event: WikiEvent) {
            throw exceptionToThrow
        }
    }

    private class RecordingDlqPublisher : DlqPublisher(
        dlqKafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>,
        errorLogger = Mockito.mock(com.redspace.wikistreamkotlin.core.exception.ErrorLogger::class.java),
    ) {
        data class SentRecord(
            val sourceName: String,
            val key: String?,
            val value: String,
            val exception: Exception
        )

        val sentRecords = CopyOnWriteArrayList<SentRecord>()
        private val latch = CountDownLatch(1)

        override fun send(sourceName: String, key: String?, value: String, exception: Exception) {
            sentRecords += SentRecord(sourceName, key, value, exception)
            latch.countDown()
        }

        fun awaitSend(timeoutMillis: Long = 1500): Boolean =
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
