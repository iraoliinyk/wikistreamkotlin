package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.domain.StatsView
import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.consumer.metrics.ConsumerMetricsService
import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsReadRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.support.Acknowledgment
import java.util.Collections

class CoroutineBatchConsumerTest {
    private val sessionRepository = FakeSessionRepository(setOf("alice@example.com"))
    private val statsWriteRepository = RecordingStatsWriteRepository()
//    private val statsReadRepository = RecordingStatsReadRepository()
    private val activeUserSessionService = ActiveUserSessionService(
        sessionRepository,
        JwtSecurityProperties("issuer", "12345678901234567890123456789012", 3600L),
        "test-instance",
    )
    private val aggregationService = BatchAggregationService()
    private val dlqPublisher = Mockito.mock(DlqPublisher::class.java)
    private val metricsService = Mockito.mock(ConsumerMetricsService::class.java)
    private val consumer = CoroutineBatchConsumer(
        aggregationService,
        statsWriteRepository,
        activeUserSessionService,
        dlqPublisher,
        metricsService
    )

    @Test
    fun `acknowledges batch only after all writes complete`() {
        runBlocking {
            val ack = Mockito.mock(Acknowledgment::class.java)

            consumer.consume(listOf(record(1L, "alice@example.com")), ack)

            Mockito.verify(ack).acknowledge()
            assertThat(statsWriteRepository.writes).isNotEmpty
        }
    }

    @Test
    fun `groups events by active user and writes aggregated deltas`() = runBlocking {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(
            listOf(
                record(1L, "alice@example.com", bot = false, serverUrl = "https://en.wikipedia.org"),
                record(2L, "alice@example.com", bot = true, serverUrl = "https://de.wikipedia.org"),
            ),
            ack,
        )

        assertThat(statsWriteRepository.writes).isNotEmpty()
        val userWrite = statsWriteRepository.writes.find { it.userEmail == "alice@example.com" }

        assertThat(userWrite).isNotNull
        assertThat(userWrite!!.delta.totalMessages).isEqualTo(2)
        assertThat(userWrite.delta.botCount).isEqualTo(1)
        assertThat(userWrite.delta.nonBotCount).isEqualTo(1)

        Mockito.verify(ack).acknowledge()
    }

    @Test
    fun `filters out inactive users`() = runBlocking {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(
            listOf(
                record(1L, "alice@example.com"),  // active
                record(2L, "inactive@example.com"), // inactive
            ),
            ack,
        )

        val inactiveWrite = statsWriteRepository.writes.find { it.userEmail == "inactive@example.com" }
        assertThat(inactiveWrite).isNull()

        Mockito.verify(ack).acknowledge()
    }

    private fun record(
        id: Long,
        user: String = "alice@example.com",
        bot: Boolean? = false,
        serverUrl: String? = "https://en.wikipedia.org"
    ): ConsumerRecord<String, WikiEvent> {
        val event = WikiEvent(
            schema = "mediawiki/recentchange/1.0.0",
            meta = null,
            id = id,
            type = "edit",
            namespace = 0,
            title = "Main Page",
            titleUrl = "https://en.wikipedia.org/wiki/Main_Page",
            comment = "updated",
            timestamp = 1713312000,
            user = user,
            bot = bot,
            notifyUrl = "https://en.wikipedia.org/w/index.php?diff=1&oldid=0",
            serverUrl = serverUrl,
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = "updated"
        )
        return ConsumerRecord("proto", 0, id, "key", event)
    }

    private class FakeSessionRepository(
        private val activeEmails: Set<String>,
    ) : SessionRepository {
        override fun markLoggedIn(
            email: String,
            sessionMetadata: Map<String, String>,
        ) = Unit

        override fun markLoggedOut(
            email: String?,
            sessionId: String?,
        ) = Unit

        override fun isActive(email: String): Boolean = email in activeEmails

        override fun listActiveEmails(): Set<String> = activeEmails
    }

    private class RecordingStatsWriteRepository : StatsWriteRepository {
        data class WriteRecord(val userEmail: String, val bucketDay: String, val delta: UserStatsDelta)

        val writes = Collections.synchronizedList(mutableListOf<WriteRecord>())

        override suspend fun incrementCounters(userEmail: String, bucketDay: String, delta: UserStatsDelta) {
            writes += WriteRecord(userEmail, bucketDay, delta)
        }

        override suspend fun appendServerUrlEvents(userEmail: String, bucketDay: String, serverUrls: List<String>) {
            // no-op for test
        }

        override suspend fun upsertTrackedUsers(userEmail: String, bucketDay: String, trackedUsers: Set<String>) {
            // no-op for test
        }
    }

    private class RecordingStatsReadRepository : StatsReadRepository {
        override suspend fun getStatsView(userEmail: String, bucketDay: String): StatsView {
            return StatsView(userEmail = userEmail, bucketDay = bucketDay)
        }
    }
}
