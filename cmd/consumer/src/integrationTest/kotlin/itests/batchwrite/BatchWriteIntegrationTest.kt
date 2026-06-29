package itests.batchwrite

import com.redspace.wikistreamkotlin.consumer.CoroutineBatchConsumer
import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.consumer.metrics.ConsumerMetricsService
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.kafka.support.Acknowledgment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Batched writes to the database.
 *
 * Verifies the central performance contract of the blind-write architecture:
 *
 *   "A Kafka batch of N events results in **one** counter UPDATE per
 *    active user — not N separate updates."
 *
 * The proof is twofold:
 *   1. A counting spy wrapped around the real [CassandraStatsWriteRepository]
 *      records exactly **one** `incrementCounters` invocation for `alice`
 *      after consuming a 100-record batch addressed to her.
 *   2. The persisted view in real Cassandra reflects the aggregated total
 *      (`totalMessages == 100`), proving the single batched UPDATE was
 *      correctly aggregated in memory before hitting the database.
 *
 * Why this matters
 *   The old read-modify-write pipeline would have issued 100 LWT cycles for
 *   the same batch. This test pins the new contract: one CQL round-trip per
 *   user per batch, regardless of batch size.
 */
@SpringBootTest(
    classes = [BatchWriteIntegrationTest.MinimalCassandraApp::class],
    properties = [
        // Override the parent IT application.properties exclude — we need Cassandra autoconfig.
        "spring.autoconfigure.exclude=",
        "spring.cassandra.schema-action=none",
    ],
)
@Testcontainers
class BatchWriteIntegrationTest {

    /**
     * Minimal Spring application: loads ONLY Cassandra autoconfiguration plus
     * the two repositories and the pure-function aggregation service. Avoids
     * Kafka, Redis, security, and the consumer itself — we instantiate
     * `CoroutineBatchConsumer` by hand so we can plug in a counting spy
     * around the write repository.
     */
    @SpringBootApplication
    @ComponentScan(
        basePackageClasses = [
            CassandraStatsWriteRepository::class,
            BatchAggregationService::class,
        ],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [
                    CassandraStatsWriteRepository::class,
                    CassandraStatsReadRepository::class,
                    BatchAggregationService::class,
                ],
            ),
        ],
    )
    class MinimalCassandraApp

    /**
     * Wraps the real write repository, counting `incrementCounters` calls per user.
     * Other methods pass through unchanged so the read-side view still reflects the
     * append-only tables.
     */
    private class CountingStatsWriteRepository(
        private val delegate: StatsWriteRepository,
    ) : StatsWriteRepository {
        val incrementCountersCallsPerUser: MutableMap<String, AtomicInteger> = HashMap()
        val appendServerUrlEventsCallsPerUser: MutableMap<String, AtomicInteger> = HashMap()
        val upsertTrackedUsersCallsPerUser: MutableMap<String, AtomicInteger> = HashMap()

        override suspend fun incrementCounters(
            userEmail: String,
            bucketDay: String,
            delta: UserStatsDelta,
        ) {
            incrementCountersCallsPerUser
                .getOrPut(userEmail) { AtomicInteger(0) }
                .incrementAndGet()
            delegate.incrementCounters(userEmail, bucketDay, delta)
        }

        override suspend fun appendServerUrlEvents(
            userEmail: String,
            bucketDay: String,
            serverUrls: List<String>,
        ) {
            appendServerUrlEventsCallsPerUser
                .getOrPut(userEmail) { AtomicInteger(0) }
                .incrementAndGet()
            delegate.appendServerUrlEvents(userEmail, bucketDay, serverUrls)
        }

        override suspend fun upsertTrackedUsers(
            userEmail: String,
            bucketDay: String,
            trackedUsers: Set<String>,
        ) {
            upsertTrackedUsersCallsPerUser
                .getOrPut(userEmail) { AtomicInteger(0) }
                .incrementAndGet()
            delegate.upsertTrackedUsers(userEmail, bucketDay, trackedUsers)
        }
    }

    @Autowired private lateinit var realWriteRepository: StatsWriteRepository
    @Autowired private lateinit var statsReadRepository: StatsReadRepository
    @Autowired private lateinit var aggregationService: BatchAggregationService

    @Test
    fun `100-event batch results in single counter update per user`() {
        runBlocking {
            // ---- Arrange ---------------------------------------------------------
            val alice = "alice-${UUID.randomUUID()}@example.com"
            val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()

            val spy = CountingStatsWriteRepository(realWriteRepository)

            val activeUserSessionService = mock(ActiveUserSessionService::class.java).apply {
                org.mockito.Mockito.`when`(listActiveUsers()).thenReturn(setOf(alice))
            }
            val dlqPublisher = mock(DlqPublisher::class.java)
            val metricsService = ConsumerMetricsService(SimpleMeterRegistry())
            val ack = mock(Acknowledgment::class.java)

            val consumer = CoroutineBatchConsumer(
                aggregationService = aggregationService,
                statsWriteRepository = spy,
                activeUserSessionService = activeUserSessionService,
                dlqPublisher = dlqPublisher,
                metricsService = metricsService,
            )

            val records: List<ConsumerRecord<String, WikiEvent>> = (0 until BATCH_SIZE).map { i ->
                ConsumerRecord(
                    Topics.PROTO,
                    /* partition = */ 0,
                    /* offset    = */ i.toLong(),
                    /* key       = */ "key-$i",
                    /* value     = */ mockWikiEvent(user = alice, bot = i % 2 == 0),
                )
            }

            // ---- Act -------------------------------------------------------------
            consumer.consume(records, ack)

            // ---- Assert: exactly ONE batched write per side per user -------------
            assertThat(spy.incrementCountersCallsPerUser[alice]?.get())
                .`as`("incrementCounters must be invoked exactly once for the 100-event batch")
                .isEqualTo(1)
            assertThat(spy.appendServerUrlEventsCallsPerUser[alice]?.get())
                .`as`("appendServerUrlEvents must be invoked once per user per batch")
                .isEqualTo(1)
            assertThat(spy.upsertTrackedUsersCallsPerUser[alice]?.get())
                .`as`("upsertTrackedUsers must be invoked once per user per batch")
                .isEqualTo(1)

            // ---- Assert: persisted view reflects the aggregated batch ------------
            val view = statsReadRepository.getStatsView(alice, bucketDay)
            assertThat(view.totalMessages)
                .`as`("counter must equal the size of the batch")
                .isEqualTo(BATCH_SIZE.toLong())
            assertThat(view.botCount + view.nonBotCount).isEqualTo(BATCH_SIZE.toLong())
            assertThat(view.distinctUsers)
                .`as`("alice contributed her own user → distinct count is 1")
                .isEqualTo(1)

            // ---- Assert: offset acknowledged exactly once ------------------------
            org.mockito.Mockito.verify(ack, org.mockito.Mockito.times(1)).acknowledge()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun mockWikiEvent(
        user: String,
        bot: Boolean,
        serverUrl: String = "https://en.wikipedia.org",
    ): WikiEvent = WikiEvent(
        schema = null,
        meta = null,
        id = System.nanoTime(),
        type = null,
        namespace = null,
        title = null,
        titleUrl = null,
        comment = null,
        timestamp = null,
        user = user,
        bot = bot,
        notifyUrl = null,
        serverUrl = serverUrl,
        serverName = null,
        serverScriptPath = null,
        wiki = null,
        parsedComment = null,
    )

    companion object {
        const val BATCH_SIZE = 100

        @JvmField
        @Container
        val cassandra: CassandraContainer =
            CassandraContainer(DockerImageName.parse("cassandra:5.0"))
                .withInitScript("db/cassandra/schema-it.cql")

        @JvmStatic
        @DynamicPropertySource
        fun cassandraProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cassandra.contact-points") {
                val contact = cassandra.contactPoint
                "${contact.hostString}:${contact.port}"
            }
            registry.add("spring.cassandra.local-datacenter") { cassandra.localDatacenter }
            registry.add("spring.cassandra.keyspace-name") { "wikistream_it" }
        }
    }
}
