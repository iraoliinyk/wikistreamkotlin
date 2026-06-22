package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.CassandraStatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsReadRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsWriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    classes = [CounterConcurrencyTest.MinimalCassandraApp::class],
    properties = [
        // Reset the parent application.properties exclude — we need Cassandra autoconfig here.
        "spring.autoconfigure.exclude=",
        "spring.cassandra.schema-action=none",
    ],
)
@Testcontainers
class CounterConcurrencyTest {

    /**
     * Minimal Spring app: loads Cassandra autoconfiguration and only the two
     * repositories under test. Avoids Kafka, the consumer, security, etc.
     */
    @SpringBootApplication
    @ComponentScan(
        basePackageClasses = [CassandraStatsWriteRepository::class],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = [CassandraStatsWriteRepository::class, CassandraStatsReadRepository::class],
            ),
        ],
    )
    class MinimalCassandraApp

    @Autowired
    private lateinit var statsWriteRepository: StatsWriteRepository

    @Autowired
    private lateinit var statsReadRepository: StatsReadRepository

    @Test
    fun `concurrent counter increments must not lose updates`() {
        runBlocking {
            val userEmail = "counter-test-${UUID.randomUUID()}@example.com"
            val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()
            val parallelWrites = 50
            val deltaPerWrite = UserStatsDelta(
                totalMessages = 10L,
                botCount = 3L,
                nonBotCount = 7L,
                serverUrls = emptyList(),
                trackedUsers = emptySet(),
            )

            (1..parallelWrites).map {
                async(Dispatchers.IO) {
                    statsWriteRepository.incrementCounters(userEmail, bucketDay, deltaPerWrite)
                }
            }.awaitAll()

            val view = statsReadRepository.getStatsView(userEmail, bucketDay)
            assertThat(view.totalMessages).isEqualTo(parallelWrites * 10L)
            assertThat(view.botCount).isEqualTo(parallelWrites * 3L)
            assertThat(view.nonBotCount).isEqualTo(parallelWrites * 7L)
        }
    }

    @Test
    fun `redelivered batch should not double-count due to idempotent schema`() {
        runBlocking {
            val userEmail = "idempotent-test-${UUID.randomUUID()}@example.com"
            val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()
            val trackedUser = "wiki-editor-1"

            repeat(2) {
                statsWriteRepository.upsertTrackedUsers(userEmail, bucketDay, setOf(trackedUser))
            }

            val view = statsReadRepository.getStatsView(userEmail, bucketDay)
            assertThat(view.distinctUsers).isEqualTo(1)
        }
    }

    companion object {
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
