package itests.recovery

import com.redspace.wikistreamkotlin.consumer.config.ConsumerKafkaConfig
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.mapper.ProtoWikiEventMapper
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
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
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end redelivery idempotency contract for the blind-write architecture.
 *
 * Scenario (the "unacknowledged batch is redelivered after restart" case):
 *   Phase 1 — simulated crash:
 *     1. Producer publishes N events to Kafka (some users repeat, so the
 *        distinct-user count is strictly less than N — this is what makes the
 *        idempotency assertion non-trivial).
 *     2. The consumer aggregates the batch, performs all three blind writes
 *        against real Cassandra (counters, server_url_events, tracked_user_sets),
 *        and then THROWS just before ack.acknowledge() — exactly the failure
 *        window the production [com.redspace.wikistreamkotlin.consumer.CoroutineBatchConsumer]
 *        comment calls out: "If the JVM crashes before this point, Kafka
 *        redelivers the batch."
 *     3. The container is stopped → simulates JVM termination with writes
 *        persisted but the consumer-group offset uncommitted.
 *
 *   Phase 2 — restart:
 *     4. Crash switch is flipped off; the listener container is started again.
 *     5. Same group.id → Kafka redelivers the un-acked batch.
 *     6. The consumer re-runs the same blind writes and then acknowledges.
 *
 * Contract under test:
 *   `distinctUsers` (sourced from `tracked_user_sets`, whose PK includes
 *   `tracked_user_email`) MUST equal `events.distinctBy { it.user }.size`
 *   regardless of how many times the batch was redelivered. This is the
 *   schema-level idempotency guarantee the blind-write architecture depends on
 *   for set-valued projections — INSERTs on the same primary key are no-ops on
 *   the second delivery.
 *
 *   Counter-valued projections (totalMessages / botCount / nonBotCount) are
 *   intentionally NOT asserted here: Cassandra COUNTER increments are NOT
 *   idempotent under redelivery, and the production code accepts that
 *   trade-off (see TODO.md and CoroutineBatchConsumer KDoc).
 */
@SpringBootTest(
    classes = [RedeliveryIdempotencyIntegrationTest.TestApp::class],
    properties = [
        // Re-enable Cassandra autoconfig (parent application.properties disables it).
        // Keep Redis/Security excluded — they are unrelated to this test.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration," +
            "org.springframework.boot.data.redis.autoconfigure.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration",
        "spring.cassandra.schema-action=none",
        "spring.main.web-application-type=none",
        "app.kafka.consumer.concurrency=1",
        "spring.kafka.consumer.group-id=redelivery-idempotency-test",
        "spring.kafka.consumer.max-poll-records=100",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Testcontainers
class RedeliveryIdempotencyIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableKafka
    @Import(ConsumerKafkaConfig::class)
    @ComponentScan(
        basePackageClasses = [
            CrashableBatchConsumer::class,
            CassandraStatsWriteRepository::class,
            BatchAggregationService::class,
        ],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [
                    CrashableBatchConsumer::class,
                    CassandraStatsWriteRepository::class,
                    CassandraStatsReadRepository::class,
                    BatchAggregationService::class,
                ],
            ),
        ],
    )
    class TestApp {
        /** No-op error handler → exception thrown by listener is swallowed, offset stays uncommitted. */
        @Bean
        fun kafkaErrorHandler(): DefaultErrorHandler = DefaultErrorHandler { _, _ -> /* no-op */ }

        /**
         * Replace the production [ActiveUserSessionService] (which depends on Redis and the
         * full security stack) with a stub that always reports `alice` as the dashboard owner.
         * `alice` is the partition-key user against which we read the [StatsView].
         */
        @Bean
        @Primary
        fun stubActiveUserSessionService(): ActiveUserSessionService =
            Mockito.mock(ActiveUserSessionService::class.java).also {
                Mockito.`when`(it.listActiveUsers()).thenReturn(setOf(ALICE))
            }
    }

    /**
     * Mirrors [com.redspace.wikistreamkotlin.consumer.CoroutineBatchConsumer]'s pipeline
     * — aggregate → fan-out blind writes → acknowledge — but adds a single controllable
     * crash point between "all writes complete" and "ack.acknowledge()". This is the
     * exact failure window the production redelivery contract is designed to survive.
     */
    @Component
    class CrashableBatchConsumer(
        private val aggregationService: BatchAggregationService,
        private val statsWriteRepository: StatsWriteRepository,
        private val activeUserSessionService: ActiveUserSessionService,
    ) {
        val crashBeforeAck = AtomicBoolean(true)
        val processedKeysPhase1: MutableList<String> = CopyOnWriteArrayList()
        val processedKeysPhase2: MutableList<String> = CopyOnWriteArrayList()
        val ackedBatchCount = AtomicInteger(0)

        @KafkaListener(
            topics = [Topics.PROTO],
            containerFactory = "batchKafkaListenerContainerFactory",
            groupId = "redelivery-idempotency-test",
        )
        fun consume(
            records: List<ConsumerRecord<String, WikiEvent>>,
            ack: Acknowledgment,
        ) {
            if (records.isEmpty()) return

            val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()
            val activeUsers = activeUserSessionService.listActiveUsers()
            val events = records.mapNotNull { it.value() }
            val deltas = aggregationService.aggregateBatch(events).filterKeys { it in activeUsers }

            // runBlocking inside a non-suspend listener: deliberate. A `suspend fun`
            // @KafkaListener is dispatched via Spring Kafka's Kotlin-coroutines adapter,
            // which auto-acknowledges the batch when the suspend method returns
            // successfully — that would silently commit the offset in phase 1 and
            // defeat the redelivery contract under test. A blocking listener gives us
            // full control over when (and whether) `ack.acknowledge()` is called.
            runBlocking {
                deltas.forEach { (userEmail, delta) ->
                    // Sequential per-user writes — order doesn't matter for the test (the
                    // contract under test is set-PK idempotency, not write concurrency).
                    statsWriteRepository.incrementCounters(userEmail, bucketDay, delta)
                    statsWriteRepository.appendServerUrlEvents(userEmail, bucketDay, delta.serverUrls)
                    statsWriteRepository.upsertTrackedUsers(userEmail, bucketDay, delta.trackedUsers)
                }
            }

            val keys = records.mapNotNull { it.key() }
            if (crashBeforeAck.get()) {
                processedKeysPhase1.addAll(keys)
                // Writes persisted; ack NOT called. With AckMode.MANUAL, simply not invoking
                // ack.acknowledge() is the broker-visible equivalent of a JVM kill at this
                // exact point — the in-memory consumer position advances, but no commit is
                // sent to Kafka, so a fresh consumer in the same group will redeliver.
                return
            }

            processedKeysPhase2.addAll(keys)
            ack.acknowledge()
            ackedBatchCount.incrementAndGet()
        }
    }

    @Autowired private lateinit var registry: KafkaListenerEndpointRegistry
    @Autowired private lateinit var consumer: CrashableBatchConsumer
    @Autowired private lateinit var statsReadRepository: StatsReadRepository

    @Test
    fun `unacknowledged batch is redelivered after restart and distinctUsers stays idempotent`(): Unit = runBlocking {
        val expectedKeys = (0 until RECORD_COUNT).map { "key-$it" }
        // All events are attributed to alice. The blind-write pipeline groups events
        // by `event.user` and then keeps only those whose group key is an active user —
        // so for alice's dashboard view, every event in this batch contributes to her
        // tracked_user_sets row. `events.distinctBy { it.user }.size` therefore equals 1,
        // and that is also the expected `distinctUsers` count both before AND after
        // redelivery (re-INSERTing the same PK is a Cassandra no-op).
        val eventUsers = List(RECORD_COUNT) { ALICE }
        val expectedDistinctUsers = eventUsers.distinct().size
        val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()

        // --- Phase 1: produce, observe writes, crash before ack ----------------
        awaitListenerAssignment()
        produceEvents(expectedKeys.zip(eventUsers))

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            assertThat(consumer.processedKeysPhase1)
                .`as`("listener must observe every produced record in phase 1 before throwing")
                .containsAll(expectedKeys)
        }

        // Sanity: distinctUsers is already populated by the phase-1 writes (writes happened,
        // ack didn't). This is the precondition that makes the idempotency assertion meaningful.
        val phase1View = statsReadRepository.getStatsView(ALICE, bucketDay)
        assertThat(phase1View.distinctUsers)
            .`as`("phase 1 writes must have populated tracked_user_sets even though ack was skipped")
            .isEqualTo(expectedDistinctUsers)

        // Simulate the JVM going down between writes and ack — stop the listener container.
        val container = registry.listenerContainers.single() as ConcurrentMessageListenerContainer<*, *>
        container.stop()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(container.isRunning).isFalse()
        }

        // Verify Kafka has nothing committed for this group yet — the contract precondition
        // for redelivery on restart.
        val committedAfterCrash = readCommittedOffset()
        assertThat(committedAfterCrash)
            .`as`("no offset must be committed because ack.acknowledge() was never called")
            .matches({ offset -> offset == null || offset == 0L }, "offset is null or zero")

        // --- Phase 2: restart with crash switch off — Kafka must redeliver -----
        consumer.crashBeforeAck.set(false)
        container.start()
        awaitListenerAssignment()

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            assertThat(consumer.processedKeysPhase2)
                .`as`("redelivered batch must contain the same records as phase 1")
                .containsAll(expectedKeys)
            assertThat(consumer.ackedBatchCount.get())
                .`as`("at least one batch must be acknowledged after restart")
                .isGreaterThanOrEqualTo(1)
        }

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted {
            val committed = readCommittedOffset()
            assertThat(committed)
                .`as`("after successful ack on the redelivered batch, offset must be committed")
                .isEqualTo(RECORD_COUNT.toLong())
        }

        // --- The contract: distinctUsers is unchanged by the double delivery ---
        val finalView = statsReadRepository.getStatsView(ALICE, bucketDay)
        assertThat(finalView.distinctUsers)
            .`as`(
                "distinctUsers comes from tracked_user_sets (PK includes tracked_user_email) — " +
                    "duplicate INSERTs from redelivery must be no-ops",
            )
            .isEqualTo(expectedDistinctUsers)
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

    private fun produceEvents(keyToUser: List<Pair<String, String>>) {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        KafkaProducer<String, ByteArray>(props).use { producer ->
            keyToUser.forEachIndexed { idx, (key, user) ->
                val event = WikiEvent(
                    schema = null,
                    meta = null,
                    id = System.nanoTime() + idx,
                    type = null,
                    namespace = null,
                    title = null,
                    titleUrl = null,
                    comment = null,
                    timestamp = null,
                    user = user,
                    bot = false,
                    notifyUrl = null,
                    serverUrl = "https://en.wikipedia.org",
                    serverName = null,
                    serverScriptPath = null,
                    wiki = null,
                    parsedComment = null,
                )
                producer.send(
                    ProducerRecord(
                        Topics.PROTO,
                        key,
                        ProtoWikiEventMapper.serializeWikiEvent(event),
                    ),
                )
            }
            producer.flush()
        }
    }

    private fun readCommittedOffset(): Long? = adminClient().use { admin ->
        admin.listConsumerGroupOffsets("redelivery-idempotency-test")
            .partitionsToOffsetAndMetadata()
            .get()[TopicPartition(Topics.PROTO, 0)]
            ?.offset()
    }

    private fun adminClient(): AdminClient = AdminClient.create(
        Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
        },
    )

    companion object {
        const val TOPIC_PARTITIONS = 1
        const val RECORD_COUNT = 12
        private val ALICE = "alice-${UUID.randomUUID()}@example.com"

        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer("apache/kafka:3.8.1")
            .withStartupTimeout(Duration.ofMinutes(2))

        @Container
        @JvmField
        val cassandra: CassandraContainer =
            CassandraContainer(DockerImageName.parse("cassandra:5.0"))
                .withInitScript("db/cassandra/schema-it.cql")

        @DynamicPropertySource
        @JvmStatic
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            // Topic creation is owned by redpanda-init in production (not the consumer app),
            // so we provision it here explicitly before the listener container starts rather
            // than relying on broker auto-creation.
            createProtoTopic()
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("spring.cassandra.contact-points") {
                val cp = cassandra.contactPoint
                "${cp.hostString}:${cp.port}"
            }
            registry.add("spring.cassandra.local-datacenter") { cassandra.localDatacenter }
            registry.add("spring.cassandra.keyspace-name") { "wikistream_it" }
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
