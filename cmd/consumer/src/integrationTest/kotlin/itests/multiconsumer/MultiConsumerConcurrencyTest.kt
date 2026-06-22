package itests.multiconsumer

import com.redspace.wikistreamkotlin.consumer.config.ConsumerKafkaConfig
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multiple Concurrent Consumers in a Single Application.
 *
 * Verifies that a single JVM instance hosts N parallel Kafka consumer threads,
 * each assigned a non-overlapping subset of partitions, and that the
 * concurrency is driven by the `app.kafka.consumer.concurrency` property.
 *
 * The test boots a minimal Spring application that loads ONLY
 * [ConsumerKafkaConfig] plus a stub [KafkaListener] — no Cassandra, Redis,
 * security, or web layer. This keeps the test focused on the listener
 * container topology, which is what the acceptance criterion is about.
 */
@SpringBootTest(
    classes = [MultiConsumerConcurrencyTest.TestApp::class],
    properties = [
        // Override parent application.properties: re-enable everything, then exclude only the heavy
        // infra autoconfigs we don't need for this test (Cassandra, Redis, Security). Listed by
        // FQN as strings so this compiles without import-time class resolution.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraAutoConfiguration," +
            "org.springframework.boot.data.cassandra.autoconfigure.DataCassandraRepositoriesAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
        "spring.main.web-application-type=none",
        "app.kafka.consumer.concurrency=4",
        "spring.kafka.consumer.group-id=mc-concurrency-test",
        "spring.kafka.consumer.max-poll-records=10",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Testcontainers
class MultiConsumerConcurrencyTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableKafka
    @Import(ConsumerKafkaConfig::class)
    @ComponentScan(
        basePackageClasses = [StubBatchListener::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [StubBatchListener::class],
            ),
        ],
    )
    class TestApp {
        @Bean
        fun kafkaErrorHandler(): DefaultErrorHandler = DefaultErrorHandler { _, _ -> /* no-op */ }

        /**
         * Override the production [ConsumerKafkaConfig.protoTopic] bean (which uses RF=3)
         * so the single-broker test container can create the topic.
         */
        @Bean
        fun protoTopic(): org.apache.kafka.clients.admin.NewTopic =
            org.apache.kafka.clients.admin.NewTopic(Topics.PROTO, TOPIC_PARTITIONS, 1.toShort())
    }

    /**
     * Stub @KafkaListener — uses the production `batchKafkaListenerContainerFactory`
     * so the topology under test matches production exactly. We only need
     * the listener to exist so partitions get assigned; received records
     * are recorded for an end-to-end sanity check.
     */
    @Component
    class StubBatchListener {
        val received: MutableList<ConsumerRecord<String, WikiEvent>> = CopyOnWriteArrayList()

        @KafkaListener(
            topics = [Topics.PROTO],
            containerFactory = "batchKafkaListenerContainerFactory",
            groupId = "mc-concurrency-test",
        )
        fun consume(records: List<ConsumerRecord<String, WikiEvent>>, ack: Acknowledgment) {
            received.addAll(records)
            ack.acknowledge()
        }
    }

    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Test
    fun `factory creates N concurrent listener containers with non-overlapping partition assignments`() {
        val container = registry.listenerContainers.single() as ConcurrentMessageListenerContainer<*, *>

        // 1. Concurrency property is honoured
        assertThat(container.concurrency)
            .`as`("ConcurrentMessageListenerContainer.concurrency")
            .isEqualTo(CONCURRENCY)

        // 2. Wait for partition assignment to settle. Each child container is a
        //    KafkaMessageListenerContainer that polls Kafka on its own thread.
        await()
            .atMost(Duration.ofSeconds(45))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val children = container.containers
                assertThat(children).hasSize(CONCURRENCY)

                val assignmentsPerChild = children.map { it.assignedPartitions.orEmpty() }
                val totalAssigned = assignmentsPerChild.flatten()

                // 3. All 6 topic partitions are covered exactly once across the N threads.
                assertThat(totalAssigned)
                    .`as`("union of partitions assigned across child containers")
                    .hasSize(TOPIC_PARTITIONS)
                assertThat(totalAssigned.toSet())
                    .`as`("no partition is assigned to more than one child container")
                    .hasSize(TOPIC_PARTITIONS)

                // 4. Every child container actually owns at least one partition.
                assertThat(assignmentsPerChild)
                    .allSatisfy { partitions ->
                        assertThat(partitions).isNotEmpty
                    }
            }

        // 5. Each child container is running on its own thread.
        assertThat(container.containers).allSatisfy { child ->
            assertThat(child.isRunning).isTrue()
        }
    }

    companion object {
        const val CONCURRENCY = 4
        const val TOPIC_PARTITIONS = 6 // matches ConsumerKafkaConfig.protoTopic()

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
