package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.consumer.exception.ConsumerErrorLogger
import com.redspace.wikistreamkotlin.consumer.metrics.ConsumerMetricsService
import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.StatsService
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.support.Acknowledgment
import java.util.*

class RedpandaBatchConsumerTest {
    private val sessionRepository = FakeSessionRepository(setOf("alice@example.com"))
    private val statsRepository = RecordingStatsRepository()
    private val statsService =
        StatsService(
            statsRepository,
            ActiveUserSessionService(
                sessionRepository,
                JwtSecurityProperties("issuer", "12345678901234567890123456789012", 3600L),
                "test-instance",
            ),
        )
    private val dlqPublisher = Mockito.mock(DlqPublisher::class.java)
    private val errorLogger = ConsumerErrorLogger()
    private val consumerMetricsService = Mockito.mock(ConsumerMetricsService::class.java)
    private val consumer = RedpandaBatchConsumer(statsService, dlqPublisher, errorLogger, consumerMetricsService)

    @Test
    fun `acknowledges batch after processing records`() {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(listOf(record(1L)), ack)

        Mockito.verify(ack).acknowledge()
    }

    @Test
    fun `calls StatsService once per record`() {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(
            listOf(
                record(1L),
                record(2L),
            ),
            ack,
        )

        assertEquals(2, statsRepository.recorded.size)
        assertEquals(setOf(1L, 2L), statsRepository.recorded.mapNotNull { it.second.id }.toSet())
        Mockito.verify(ack).acknowledge()
    }

    private fun record(id: Long): ConsumerRecord<String, WikiEvent> {
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
            user = "TestUser",
            bot = false,
            notifyUrl = "https://en.wikipedia.org/w/index.php?diff=1&oldid=0",
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = "updated"
        )
        return ConsumerRecord("wiki.recentchange.proto", 0, 0L, "key", event)
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

    private class RecordingStatsRepository : StatsRepository {
        val recorded = Collections.synchronizedList(mutableListOf<Pair<String, WikiEvent>>())

        override suspend fun recordForUser(
            userEmail: String,
            event: WikiEvent,
        ) {
            recorded += userEmail to event
        }

        override suspend fun snapshotForUser(userEmail: String): StatsSnapshot = StatsSnapshot(id = userEmail)
    }
}
