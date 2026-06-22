package com.redspace.wikistreamkotlin.producer

import com.fasterxml.jackson.databind.ObjectMapper
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import com.redspace.wikistreamkotlin.producer.config.WikiStreamProperties
import com.redspace.wikistreamkotlin.producer.exception.ProducerErrorLogger
import com.redspace.wikistreamkotlin.producer.metrics.ProducerMetricsService
import com.redspace.wikistreamkotlin.producer.serializer.ProtoWikiEventSerializer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for ProducerIngestionRunner that verifies corrupted/failed events
 * are properly sent to the Dead Letter Queue (DLQ) topic.
 *
 * Uses Testcontainers to provision a real Kafka instance and validates:
 * - Parser exceptions are caught and sent to DLQ
 * - Publisher exceptions are caught and sent to DLQ
 * - DLQ records contain proper headers and payload
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
    ],
)
@Testcontainers
class ProducerIngestionRunnerIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestConfig {
        @Bean
        fun errorLogger(): ErrorLogger = ProducerErrorLogger()

        @Bean
        fun producerMetricsService(): ProducerMetricsService =
            Mockito.mock(ProducerMetricsService::class.java)

        @Bean
        fun dlqKafkaProducerFactory(): ProducerFactory<String, ByteArray> {
            val props = mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            )
            return DefaultKafkaProducerFactory(props)
        }

        @Bean
        fun dlqKafkaTemplate(dlqKafkaProducerFactory: ProducerFactory<String, ByteArray>): KafkaTemplate<String, ByteArray> {
            return KafkaTemplate(dlqKafkaProducerFactory)
        }

        @Bean
        fun protoKafkaProducerFactory(errorLogger: ErrorLogger): ProducerFactory<String, WikiEvent> {
            val props = mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            )
            return DefaultKafkaProducerFactory(
                props,
                StringSerializer(),
                ProtoWikiEventSerializer(errorLogger)
            )
        }

        @Bean
        fun protoKafkaTemplate(protoKafkaProducerFactory: ProducerFactory<String, WikiEvent>): KafkaTemplate<String, WikiEvent> {
            return KafkaTemplate(protoKafkaProducerFactory)
        }
    }

    companion object {
        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer("apache/kafka:3.8.1")
            .withStartupTimeout(Duration.ofMinutes(2))

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Autowired
    private lateinit var dlqKafkaTemplate: KafkaTemplate<String, ByteArray>

    @Autowired
    private lateinit var protoKafkaTemplate: KafkaTemplate<String, WikiEvent>

    @Autowired
    private lateinit var errorLogger: ErrorLogger

    @Autowired
    private lateinit var producerMetricsService: ProducerMetricsService

    private lateinit var dlqConsumer: KafkaConsumer<String, ByteArray>
    private var runnerJob: Job? = null

    @AfterEach
    fun cleanup() = runBlocking {
        runnerJob?.cancel()
        runnerJob?.join()

        if (::dlqConsumer.isInitialized) {
            dlqConsumer.close()
        }
    }

    @Test
    fun `corrupted events that cause parser exceptions are sent to DLQ`() = runBlocking {
        // Given: A parser that throws on specific corrupted input
        val corruptedPayload = """{"corrupt": "json without closing brace"""
        val parseException = RuntimeException("JSON parse error: Unexpected end of input")

        val parser = object : WikiEventParser(ObjectMapper()) {
            override fun parseEvent(raw: String): WikiEvent? {
                if (raw == corruptedPayload) {
                    throw parseException
                }
                return super.parseEvent(raw)
            }
        }

        val streamClient = SequencedWikiStreamClient(
            listOf(
                flow { emit(corruptedPayload) },
                emptyFlow()
            )
        )

        val publisher = RedpandaPublisher(protoKafkaTemplate)
        val dlqPublisher = DlqPublisher(dlqKafkaTemplate, errorLogger)

        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = publisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = producerMetricsService
        )

        // When: Runner processes the corrupted event
        dlqConsumer = createDlqConsumer()
        runnerJob = launch {
            try {
                runner.run()
            } catch (_: Exception) {
                // Expected when coroutine is cancelled
            }
        }
        // Give runner time to start
        delay(1000)

        // Then: The corrupted payload is sent to DLQ with error metadata
        val dlqRecords = pollDlqTopic(timeoutSeconds = 10)

        assertTrue(dlqRecords.isNotEmpty(), "Expected at least one DLQ record")

        // Find the record with our corrupted payload
        val dlqRecord = dlqRecords.find { String(it.value()) == corruptedPayload }
        assertNotNull(dlqRecord, "Should find DLQ record with corrupted payload")

        // Verify payload
        assertEquals(corruptedPayload, String(dlqRecord!!.value()))

        // Verify headers
        val errorMessage = getHeaderValue(dlqRecord, "dlq.error.message")
        val errorClass = getHeaderValue(dlqRecord, "dlq.error.class")
        val sourceTopic = getHeaderValue(dlqRecord, "dlq.source.topic")

        assertNotNull(errorMessage, "dlq.error.message header should be present")
        assertTrue(errorMessage!!.contains("JSON parse error") || errorMessage.contains("RuntimeException"))
        assertEquals("java.lang.RuntimeException", errorClass)
        assertEquals("wikimedia-sse-stream", sourceTopic)
    }

    @Test
    fun `events that cause publisher exceptions are sent to DLQ`() = runBlocking {
        // Given: A publisher that throws on publish
        val validEvent = wikiEvent(id = 42L, user = "TestUser")
        val validJson = ObjectMapper().writeValueAsString(validEvent)
        val publishException = RuntimeException("Kafka broker unavailable")

        // Parser that always returns the valid event
        val parser = object : WikiEventParser(ObjectMapper()) {
            override fun parseEvent(raw: String): WikiEvent? {
                return validEvent  // Always return the valid event
            }
        }

        val throwingPublisher = object : RedpandaPublisher(protoKafkaTemplate) {
            override fun publish(event: WikiEvent) {
                throw publishException
            }
        }

        val streamClient = SequencedWikiStreamClient(
            listOf(
                flow { emit(validJson) },
                emptyFlow()
            )
        )

        val dlqPublisher = DlqPublisher(dlqKafkaTemplate, errorLogger)

        val runner = ProducerIngestionRunner(
            streamClient = streamClient,
            parser = parser,
            publisher = throwingPublisher,
            dlqPublisher = dlqPublisher,
            errorLogger = errorLogger,
            producerMetricsService = producerMetricsService
        )

        // When: Runner attempts to publish the event
        dlqConsumer = createDlqConsumer()
        runnerJob = launch {
            try {
                runner.run()
            } catch (_: Exception) {
                // Expected when coroutine is cancelled
            }
        }
        // Give runner time to start
        delay(1000)

        // Then: The event is sent to DLQ
        val dlqRecords = pollDlqTopic(timeoutSeconds = 10)

        assertTrue(dlqRecords.isNotEmpty(), "Expected at least one DLQ record")

        // Find the record that contains TestUser (from our test)
        val dlqRecord = dlqRecords.find { String(it.value()).contains("TestUser") }
        assertNotNull(dlqRecord, "Should find DLQ record containing TestUser")

        // Verify payload contains the original JSON
        val payload = String(dlqRecord!!.value())
        assertTrue(payload.contains("TestUser"))

        // Verify error headers
        val errorMessage = getHeaderValue(dlqRecord, "dlq.error.message")
        val errorClass = getHeaderValue(dlqRecord, "dlq.error.class")

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Kafka broker unavailable"))
        assertEquals("java.lang.RuntimeException", errorClass)
    }

    private fun createDlqConsumer(): KafkaConsumer<String, ByteArray> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-consumer-${System.currentTimeMillis()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
        }

        return KafkaConsumer<String, ByteArray>(props).apply {
            subscribe(listOf(Topics.DLQ))
        }
    }

    private fun pollDlqTopic(timeoutSeconds: Long): List<ConsumerRecord<String, ByteArray>> {
        val records = mutableListOf<ConsumerRecord<String, ByteArray>>()
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000

        // Poll for at least 2 seconds to ensure we get messages, then keep polling if we found some
        val minPollTime = 2000L
        var foundRecords = false

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val polled = dlqConsumer.poll(Duration.ofMillis(500))
            if (!polled.isEmpty) {
                polled.forEach { records.add(it) }
                foundRecords = true
            }

            // If we found records and have been polling for at least minPollTime, we can stop
            if (foundRecords && System.currentTimeMillis() - startTime >= minPollTime) {
                break
            }
        }

        return records
    }

    private fun getHeaderValue(record: ConsumerRecord<String, ByteArray>, headerKey: String): String? {
        return record.headers().lastHeader(headerKey)?.value()?.let { String(it) }
    }


    private fun wikiEvent(id: Long, user: String): WikiEvent =
        WikiEvent(
            schema = "mediawiki/recentchange/1.0.0",
            meta = null,
            id = id,
            type = "edit",
            namespace = 0,
            title = "Test Page",
            titleUrl = "https://test.wikipedia.org/wiki/Test",
            comment = "test edit",
            timestamp = System.currentTimeMillis() / 1000,
            user = user,
            bot = false,
            notifyUrl = null,
            serverUrl = "https://test.wikipedia.org",
            serverName = "test.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "testwiki",
            parsedComment = "test edit",
        )

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
}
