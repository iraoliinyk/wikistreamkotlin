package itests.recovery

import com.redspace.wikistreamkotlin.consumer.config.ConsumerKafkaConfig
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerRecord
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
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mid-batch restart resilience: "crash before acknowledge does NOT commit offset".
 *
 * Test plan
 *   Phase 1 (simulated crash):
 *     1. Produce N records.
 *     2. Listener observes the batch but DOES NOT call `ack.acknowledge()`
 *        (this is exactly what a JVM kill before ack would leave behind:
 *        records consumed, offset uncommitted).
 *     3. Stop the listener container — simulates the application going down.
 *     4. Inspect the consumer group: committed offset should be < end offset
 *        (or absent entirely) for the partition.
 *
 *   Phase 2 (restart):
 *     5. Flip the listener to "ack mode" and start the container again.
 *     6. Same `group.id` → Kafka redelivers the un-acked batch.
 *     7. Assert: the new listener instance sees the SAME records (by key) AND
 *        the committed offset now equals the topic end offset.
 *
 * Why this is a meaningful test of the production architecture
 *   The blind-write strategy in TODO.md relies entirely on Kafka redelivery for
 *   crash recovery. There is no batch-state table. This test pins down that
 *   contract: uncommitted offsets ⇒ redelivery, committed offsets ⇒ no
 *   redelivery.
 */
@SpringBootTest(
    classes = [OffsetRecoveryIntegrationTest.TestApp::class],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
        "spring.main.web-application-type=none",
        "app.kafka.consumer.concurrency=1",
        "spring.kafka.consumer.group-id=offset-recovery-test",
        "spring.kafka.consumer.max-poll-records=10",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Testcontainers
class OffsetRecoveryIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableKafka
    @Import(ConsumerKafkaConfig::class)
    @ComponentScan(
        basePackageClasses = [SwitchableListener::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [SwitchableListener::class],
            ),
        ],
    )
    class TestApp {
        @Bean
        fun kafkaErrorHandler(): DefaultErrorHandler = DefaultErrorHandler { _, _ -> /* no-op */ }

        /** Single-broker + single-partition for a deterministic test. */
        @Bean
        fun protoTopic(): NewTopic = NewTopic(Topics.PROTO, TOPIC_PARTITIONS, 1.toShort())
    }

    /**
     * Listener whose acknowledge behaviour is controlled by an external switch.
     * Phase 1 → ackEnabled = false  ⇒ batch consumed but offset never committed.
     * Phase 2 → ackEnabled = true   ⇒ redelivered batch is acked.
     */
    @Component
    class SwitchableListener {
        val ackEnabled = AtomicBoolean(false)
        val phase1Keys: MutableList<String> = CopyOnWriteArrayList()
        val phase2Keys: MutableList<String> = CopyOnWriteArrayList()

        @KafkaListener(
            topics = [Topics.PROTO],
            containerFactory = "batchKafkaListenerContainerFactory",
            groupId = "offset-recovery-test",
        )
        fun consume(records: List<ConsumerRecord<String, WikiEvent>>, ack: Acknowledgment) {
            if (records.isEmpty()) return
            val keys = records.mapNotNull { it.key() }
            if (ackEnabled.get()) {
                phase2Keys.addAll(keys)
                ack.acknowledge()
            } else {
                phase1Keys.addAll(keys)
                // Deliberately NO ack — simulates JVM kill before ack.acknowledge().
            }
        }
    }

    @Autowired private lateinit var registry: KafkaListenerEndpointRegistry
    @Autowired private lateinit var listener: SwitchableListener

    @Test
    fun `crash before acknowledge does NOT commit offset and redelivers on restart`() {
        val expectedKeys = (0 until RECORD_COUNT).map { "key-$it" }

        // --- Phase 1: deliver records, listener observes them, NO ack -----------
        awaitListenerAssignment()
        produceRecords(expectedKeys)

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            assertThat(listener.phase1Keys)
                .`as`("listener must observe every produced record in phase 1")
                .containsExactlyInAnyOrderElementsOf(expectedKeys)
        }

        // Stop the container — simulates JVM going down before ack.acknowledge().
        val outerContainer = registry.listenerContainers.single() as ConcurrentMessageListenerContainer<*, *>
        outerContainer.stop()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(outerContainer.isRunning).isFalse()
        }

        // Verify: NO offset was committed for the consumer group.
        val committedAfterPhase1 = readCommittedOffset()
        val endOffsetAfterPhase1 = readEndOffset()
        assertThat(endOffsetAfterPhase1)
            .`as`("topic end offset reflects produced records")
            .isEqualTo(RECORD_COUNT.toLong())
        assertThat(committedAfterPhase1)
            .`as`("no offset must be committed because ack.acknowledge() was never called")
            .matches({ offset -> offset == null || offset == 0L }, "offset is null or zero")

        // --- Phase 2: restart with ack enabled — Kafka must redeliver -----------
        listener.ackEnabled.set(true)
        outerContainer.start()
        awaitListenerAssignment()

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            assertThat(listener.phase2Keys)
                .`as`("redelivered batch must contain the same records as phase 1")
                .containsExactlyInAnyOrderElementsOf(expectedKeys)
        }

        // Wait for the ack to flush a commit, then verify the committed offset.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            val committed = readCommittedOffset()
            assertThat(committed)
                .`as`("after successful ack, committed offset equals topic end offset")
                .isEqualTo(RECORD_COUNT.toLong())
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun awaitListenerAssignment() {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).untilAsserted {
            val outer = registry.listenerContainers.single() as ConcurrentMessageListenerContainer<*, *>
            val assigned = outer.containers.flatMap { it.assignedPartitions.orEmpty() }
            assertThat(assigned).hasSize(TOPIC_PARTITIONS)
        }
    }

    private fun produceRecords(keys: List<String>) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            // ACKs=all guarantees the record is durable before we proceed to Phase 1 assertions.
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        KafkaProducer<String, ByteArray>(props).use { producer ->
            keys.forEach { key ->
                producer.send(ProducerRecord(Topics.PROTO, key, ByteArray(0)))
            }
            producer.flush()
        }
    }

    private fun readCommittedOffset(): Long? = adminClient().use { admin ->
        val committed = admin.listConsumerGroupOffsets("offset-recovery-test")
            .partitionsToOffsetAndMetadata()
            .get()
        committed[TopicPartition(Topics.PROTO, 0)]?.offset()
    }

    private fun readEndOffset(): Long = adminClient().use { admin ->
        val tp = TopicPartition(Topics.PROTO, 0)
        admin.listOffsets(mapOf(tp to OffsetSpec.latest()))
            .partitionResult(tp)
            .get()
            .offset()
    }

    private fun adminClient(): AdminClient {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
        }
        return AdminClient.create(props)
    }

    companion object {
        const val TOPIC_PARTITIONS = 1
        const val RECORD_COUNT = 5

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
}
