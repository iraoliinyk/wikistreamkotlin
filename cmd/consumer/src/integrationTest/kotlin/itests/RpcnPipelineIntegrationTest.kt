package itests

import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.domain.WikiEventMeta
import com.redspace.wikistreamkotlin.core.mapper.ProtoWikiEventMapper
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * The stateless-contract test for the Redpanda Connect (RPCN) validation pipeline
 * (`rpcn/pipeline.yaml`) — the one test that pins the entire boundary between the
 * producer's `wiki.recentchange.proto` topic and the Spring consumer's
 * `wiki.recentchange.validated` topic.
 *
 * Runs the *real* pipeline YAML inside the pinned `redpandadata/connect` image against
 * a real Kafka broker, both on a shared Docker [Network] (RPCN must reach Kafka
 * in-network, which the Spring-based integration tests never need).
 *
 * Contract asserted end-to-end:
 *  - **valid protobuf → `validated`, byte-identical** (pass-through, never re-serialized)
 *    with no metadata leaking as Kafka headers, and
 *  - **undecodable bytes → `dlq`** carrying the exact five `dlq.*` headers the JVM-side
 *    [com.redspace.wikistreamkotlin.kafka.DlqPublisher] writes, so both DLQ producers
 *    share one header schema.
 *
 * Offset handling: the pipeline defaults to `start_offset: latest`; this test forces
 * `earliest` via `RPCN_START_OFFSET` so a late consumer-group join can't silently skip
 * the records we produce. Topics are created *before* RPCN starts so its group joins
 * against existing, empty topics — deterministic, no start-position race.
 */
@Testcontainers
class RpcnPipelineIntegrationTest {

    @Test
    fun `valid proto reaches validated topic byte-identical, garbage lands on DLQ with DlqPublisher headers`() {
        val validBytes = ProtoWikiEventMapper.serializeWikiEvent(validWikiEvent())
        val garbage = "not-protobuf".toByteArray()

        producer().use {
            it.send(ProducerRecord(Topics.PROTO, null, validBytes)).get()
            it.send(ProducerRecord(Topics.PROTO, null, garbage)).get()
            it.flush()
        }

        val validated = drain(Topics.VALIDATED, expected = 1)
        assertThat(validated.single().value())
            .`as`("valid proto must pass through byte-identical — RPCN validates, never re-serializes")
            .isEqualTo(validBytes)
        assertThat(validated.single().headers().toArray())
            .`as`("validated_out sets include_patterns: [] — no internal metadata may leak as headers")
            .isEmpty()

        val dlqRecord = drain(Topics.DLQ, expected = 1).single()
        assertThat(dlqRecord.value())
            .`as`("DLQ payload is the ORIGINAL bytes, untouched")
            .isEqualTo(garbage)
        assertThat(dlqRecord.headers().lastHeader("dlq.error.message")).isNotNull()
        assertThat(headerValue(dlqRecord, "dlq.error.class"))
            .isEqualTo("RpcnProtobufValidationError")
        assertThat(headerValue(dlqRecord, "dlq.source.topic"))
            .isEqualTo(Topics.PROTO)
        assertThat(headerValue(dlqRecord, "dlq.source.partition")).isNotBlank()
        assertThat(headerValue(dlqRecord, "dlq.source.offset")).isNotBlank()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun validWikiEvent(): WikiEvent = WikiEvent(
        schema = "/mediawiki/recentchange/1.0.0",
        meta = WikiEventMeta(
            uri = "https://en.wikipedia.org/wiki/Kotlin",
            requestId = null,
            id = "fixture-1",
            domain = "en.wikipedia.org",
            stream = "mediawiki.recentchange",
            dt = null,
            topic = null,
            partition = null,
            offset = null,
        ),
        id = 12345L,
        type = "edit",
        namespace = 0,
        title = "Kotlin (programming language)",
        titleUrl = "https://en.wikipedia.org/wiki/Kotlin",
        comment = "fixture edit",
        timestamp = 1_700_000_000L,
        user = "test-user",
        bot = false,
        notifyUrl = null,
        serverUrl = "https://en.wikipedia.org",
        serverName = "en.wikipedia.org",
        serverScriptPath = "/w",
        wiki = "enwiki",
        parsedComment = "fixture edit",
    )

    private fun headerValue(record: ConsumerRecord<ByteArray, ByteArray>, key: String): String =
        String(record.headers().lastHeader(key).value())

    private fun producer(): KafkaProducer<ByteArray, ByteArray> = KafkaProducer(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        },
    )

    /**
     * Poll [topic] from the beginning until [expected] records have arrived (RPCN routes them
     * asynchronously, so this waits). Fresh random group per call so `earliest` always replays
     * from offset 0.
     */
    private fun drain(topic: String, expected: Int): List<ConsumerRecord<ByteArray, ByteArray>> {
        val consumer = KafkaConsumer<ByteArray, ByteArray>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "drain-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
        )
        val collected = mutableListOf<ConsumerRecord<ByteArray, ByteArray>>()
        consumer.use { c ->
            c.subscribe(listOf(topic))
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted {
                c.poll(Duration.ofMillis(500)).forEach { collected.add(it) }
                assertThat(collected)
                    .`as`("expected at least %d record(s) on %s", expected, topic)
                    .hasSizeGreaterThanOrEqualTo(expected)
            }
        }
        return collected
    }

    companion object {
        private val network: Network = Network.newNetwork()

        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer("apache/kafka:3.8.1")
            .withNetwork(network)
            .withListener("kafka:19093") // in-network listener + "kafka" network alias for RPCN
            .withStartupTimeout(Duration.ofMinutes(2))

        // NOT a @Container: started manually in setUp() AFTER topics exist, so RPCN's
        // consumer group joins against existing topics with no start-position race.
        private lateinit var rpcn: GenericContainer<*>

        @BeforeAll
        @JvmStatic
        fun setUp() {
            createTopics()
            rpcn = GenericContainer(DockerImageName.parse("redpandadata/connect:4.99.0"))
                .withNetwork(network)
                .withCopyFileToContainer(
                    MountableFile.forHostPath(repoRootPath("rpcn/pipeline.yaml")),
                    "/etc/rpcn/pipeline.yaml",
                )
                .withCopyFileToContainer(
                    MountableFile.forHostPath(repoRootPath("lib/core/src/main/proto/wikievent.proto")),
                    "/etc/rpcn/proto/wikievent.proto",
                )
                .withEnv("RPCN_BROKERS", "kafka:19093")
                .withEnv("RPCN_START_OFFSET", "earliest") // deterministic: no latest-join race
                .withCommand("run", "/etc/rpcn/pipeline.yaml")
                .withExposedPorts(4195)
                .waitingFor(Wait.forHttp("/ready").forPort(4195).withStartupTimeout(Duration.ofMinutes(2)))
            rpcn.start()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::rpcn.isInitialized) rpcn.stop()
        }

        /** proto(6) + validated(6) mirror production partitioning; dlq(1) mirrors redpanda-init. */
        private fun createTopics() {
            AdminClient.create(
                Properties().apply {
                    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                },
            ).use { admin ->
                admin.createTopics(
                    listOf(
                        NewTopic(Topics.PROTO, 6, 1.toShort()),
                        NewTopic(Topics.VALIDATED, 6, 1.toShort()),
                        NewTopic(Topics.DLQ, 1, 1.toShort()),
                    ),
                ).all().get()
            }
        }

        /** Resolve a repo-relative path by walking up from the Gradle module dir to the repo root. */
        private fun repoRootPath(relative: String): String {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return File(dir, relative).absolutePath
        }
    }
}