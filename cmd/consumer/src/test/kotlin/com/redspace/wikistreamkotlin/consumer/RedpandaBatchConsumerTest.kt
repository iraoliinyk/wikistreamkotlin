package com.redspace.wikistreamkotlin.consumer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.StatsService
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.support.Acknowledgment
import java.util.Collections

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
    private val consumer = RedpandaBatchConsumer(statsService, jacksonObjectMapper())

    @Test
    fun `acknowledges batch after processing records`() {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(listOf(record(validPayload(1L))), ack)

        Mockito.verify(ack).acknowledge()
    }

    @Test
    fun `skips malformed JSON without failing the whole batch`() {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(listOf(record("not-json")), ack)

        assertTrue(statsRepository.recorded.isEmpty())
        Mockito.verify(ack).acknowledge()
    }

    @Test
    fun `calls StatsService once per valid record`() {
        val ack = Mockito.mock(Acknowledgment::class.java)

        consumer.consume(
            listOf(
                record(validPayload(1L)),
                record(validPayload(2L)),
                record("not-json"),
            ),
            ack,
        )

        assertEquals(2, statsRepository.recorded.size)
        assertEquals(setOf(1L, 2L), statsRepository.recorded.mapNotNull { it.second.id }.toSet())
        Mockito.verify(ack).acknowledge()
    }

    private fun record(value: String): ConsumerRecord<String, String> =
        ConsumerRecord("wiki.recentchange.raw", 0, 0L, "key", value)

    private fun validPayload(id: Long): String =
        """
        {
          "schema": "mediawiki/recentchange/1.0.0",
          "meta": null,
          "id": $id,
          "type": "edit",
          "namespace": 0,
          "title": "Main Page",
          "title_url": "https://en.wikipedia.org/wiki/Main_Page",
          "comment": "updated",
          "timestamp": 1713312000,
          "user": "TestUser",
          "bot": false,
          "notify_url": "https://en.wikipedia.org/w/index.php?diff=1&oldid=0",
          "server_url": "https://en.wikipedia.org",
          "server_name": "en.wikipedia.org",
          "server_script_path": "/w",
          "wiki": "enwiki",
          "parsedcomment": "updated"
        }
        """.trimIndent()

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
