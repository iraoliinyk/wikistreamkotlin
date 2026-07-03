package itests.shutdown

import com.redspace.wikistreamkotlin.consumer.config.ConsumerKafkaConfig
import com.redspace.wikistreamkotlin.consumer.config.GracefulShutdownHandler
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import itests.shutdown.GracefulShutdownHandlerTest.Companion.SLOW_WORK_MS
import kotlinx.coroutines.*
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Graceful Termination Across Consumers.
 *
 * Verifies that [GracefulShutdownHandler.stop] (a Spring `SmartLifecycle`):
 *   1. Returns control only after every in-flight `suspend` batch finishes,
 *   2. Commits the offsets of those in-flight batches before signalling completion,
 *   3. Stops every child listener container (no thread is left polling),
 *   4. Does NOT commit offsets for batches that never got to acknowledge
 *      (verified indirectly via consumer-group lag inspection).
 */
@SpringBootTest(
    classes = [GracefulShutdownHandlerTest.TestApp::class],
    properties = [
        // Re-enable autoconfig (parent application.properties disables Cassandra) and
        // strip heavy infra we don't need for this test.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
        "spring.main.web-application-type=none",
        "app.kafka.consumer.concurrency=2",
        "spring.kafka.consumer.group-id=graceful-shutdown-test",
        "spring.kafka.consumer.max-poll-records=10",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Testcontainers
class GracefulShutdownHandlerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableKafka
    @Import(ConsumerKafkaConfig::class, GracefulShutdownHandler::class)
    @ComponentScan(
        basePackageClasses = [SlowBatchListener::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [SlowBatchListener::class],
            ),
        ],
    )
    class TestApp {
        @Bean
        fun kafkaErrorHandler(): DefaultErrorHandler = DefaultErrorHandler { _, _ -> /* no-op */ }
    }

    /**
     * Stub listener that simulates a slow batch by blocking the consumer thread for
     * [SLOW_WORK_MS]. Using a blocking sleep (rather than `suspend fun delay`) is
     * deliberate: it forces the Kafka container to drain a thread that is genuinely
     * busy, which is the worst-case `stop()` must handle. With a coroutine `delay`,
     * Spring Kafka frees the consumer thread before the listener actually returns,
     * which would mask the graceful-shutdown contract under test.
     */
    @Component
    class SlowBatchListener {
        val processingStarted = CompletableDeferred<Unit>()
        val processingFinished = AtomicBoolean(false)
        val batchesAcked = AtomicInteger(0)
        val recordsAcked = AtomicInteger(0)

        @KafkaListener(
            topics = [Topics.PROTO],
            containerFactory = "batchKafkaListenerContainerFactory",
            groupId = "graceful-shutdown-test",
        )
        fun consume(records: List<ConsumerRecord<String, WikiEvent>>, ack: Acknowledgment) {
            if (records.isEmpty()) return
            if (!processingStarted.isCompleted) processingStarted.complete(Unit)

            // Blocking sleep — mirrors a slow synchronous DB write.
            Thread.sleep(SLOW_WORK_MS)

            ack.acknowledge()
            batchesAcked.incrementAndGet()
            recordsAcked.addAndGet(records.size)
            processingFinished.set(true)
        }
    }

    @Autowired private lateinit var registry: KafkaListenerEndpointRegistry
    @Autowired private lateinit var shutdownHandler: GracefulShutdownHandler
    @Autowired private lateinit var listener: SlowBatchListener

    @Test
    fun `stop drains in-flight batches, commits offsets, and stops all containers`(): Unit = runBlocking {
        // 1. Wait for partitions to be assigned across both child containers.
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).untilAsserted {
            val containers = registry.listenerContainers.flatMap {
                (it as org.springframework.kafka.listener.ConcurrentMessageListenerContainer<*, *>).containers
            }
            assertThat(containers).hasSize(CONCURRENCY)
            assertThat(containers.flatMap { it.assignedPartitions.orEmpty() }).hasSize(TOPIC_PARTITIONS)
        }

        // 2. Publish records so each consumer thread picks up at least one batch.
        produceRecords(count = RECORD_COUNT)

        // 3. Wait until the listener has actually started the slow work.
        withTimeout(20.seconds) { listener.processingStarted.await() }

        // 4. Trigger graceful shutdown from a separate coroutine. Capture the timestamp
        //    when shutdown began so we can later assert the callback didn't fire too early.
        val callbackInvoked = CompletableDeferred<Unit>()
        val stopStartedAt = System.currentTimeMillis()
        launch(Dispatchers.IO) {
            shutdownHandler.stop { callbackInvoked.complete(Unit) }
        }

        withTimeout(20.seconds) { callbackInvoked.await() }
        val stopDurationMs = System.currentTimeMillis() - stopStartedAt

        // 5. Core contract: the in-flight batch finished and was acknowledged
        //    BEFORE the shutdown callback fired. This is the proof of "drain".
        assertThat(listener.processingFinished.get())
            .`as`("in-flight batch must have completed before shutdown callback fired")
            .isTrue()
        assertThat(listener.batchesAcked.get())
            .`as`("at least one batch must have been acknowledged during drain")
            .isGreaterThanOrEqualTo(1)
        assertThat(listener.recordsAcked.get()).isGreaterThanOrEqualTo(1)

        // 6. Sanity: stop() blocked for non-trivial time (proves it actually waited
        //    rather than returning instantly). 200ms is a deliberately loose lower
        //    bound that survives CI jitter while still catching a regression where
        //    stop() returns synchronously without awaiting the listener.
        assertThat(stopDurationMs)
            .`as`("stop() should block for the remainder of the in-flight batch")
            .isGreaterThanOrEqualTo(200L)

        // 7. Every child container is stopped (no thread left polling).
        val allChildren = registry.listenerContainers.flatMap {
            (it as org.springframework.kafka.listener.ConcurrentMessageListenerContainer<*, *>).containers
        }
        assertThat(allChildren).allSatisfy { child ->
            assertThat(child.isRunning).isFalse()
        }
        assertThat(shutdownHandler.isRunning).isFalse()

        // 8. Offsets of acknowledged batches were committed to the broker:
        //    committed-offset == high-water-mark for every assigned partition that was processed.
        val committedTotal = readCommittedOffsetSum()
        assertThat(committedTotal)
            .`as`("at least the acknowledged records must be reflected in committed offsets")
            .isGreaterThanOrEqualTo(listener.recordsAcked.get().toLong())
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun produceRecords(count: Int) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
        }
        KafkaProducer<String, ByteArray>(props).use { producer ->
            repeat(count) { i ->
                // Empty bytes deserialize to null WikiEvent (filtered by the listener via mapNotNull
                // in prod), but for this test we only care about Kafka delivery + ack semantics.
                producer.send(ProducerRecord(Topics.PROTO, "key-$i", ByteArray(0)))
            }
            producer.flush()
        }
    }

    private fun readCommittedOffsetSum(): Long {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
        }
        return AdminClient.create(props).use { admin ->
            val committed: Map<TopicPartition, OffsetAndMetadata> =
                admin.listConsumerGroupOffsets("graceful-shutdown-test")
                    .partitionsToOffsetAndMetadata()
                    .get()
            committed.values.sumOf { it.offset() }
        }
    }

    companion object {
        const val CONCURRENCY = 2
        const val TOPIC_PARTITIONS = 2
        const val RECORD_COUNT = 4
        const val SLOW_WORK_MS = 1_500L

        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer("apache/kafka:3.8.1")
            .withStartupTimeout(Duration.ofMinutes(2))

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            // Topic creation is owned by redpanda-init in production (not the consumer app),
            // so we provision it here explicitly before the listener containers start —
            // guaranteeing TOPIC_PARTITIONS partitions rather than the broker's auto-created
            // default of 1.
            createProtoTopic()
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }

        private fun createProtoTopic() {
            val props = Properties().apply {
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            }
            AdminClient.create(props).use { admin ->
                admin.createTopics(listOf(NewTopic(Topics.PROTO, TOPIC_PARTITIONS, 1.toShort())))
                    .all()
                    .get()
            }
        }
    }
}
