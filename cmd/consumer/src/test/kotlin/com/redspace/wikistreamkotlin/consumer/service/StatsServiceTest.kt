package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.repository.StatsRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.RepositoryReadError
import com.redspace.wikistreamkotlin.core.exception.StatsRecordingError
import com.redspace.wikistreamkotlin.core.exception.StatsSnapshotError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StatsServiceTest {
    private val sessionRepository = FakeSessionRepository()
    private val activeUserSessionService =
        ActiveUserSessionService(
            sessionRepository,
            JwtSecurityProperties("issuer", "12345678901234567890123456789012", 3600L),
            "test-instance",
        )
    private val statsRepository = FakeStatsRepository()
    private val service = StatsService(statsRepository, activeUserSessionService)

    @Test
    fun `recordForActiveUsers records event for all active users`() =
        runBlocking {
            sessionRepository.activeEmails += setOf("alice@example.com", "bob@example.com")
            val event = wikiEvent(id = 42L)

            service.recordForActiveUsers(event)

            assertEquals(
                listOf(
                    "alice@example.com" to event,
                    "bob@example.com" to event,
                ),
                statsRepository.recordedEvents,
            )
        }

    @Test
    fun `recordForActiveUsers rethrows CancellationException unchanged`() =
        runBlocking {
            sessionRepository.activeEmails += "alice@example.com"
            val expected = CancellationException("cancelled")
            statsRepository.recordException = expected

            val actual =
                assertThrows(CancellationException::class.java) {
                    runBlocking { service.recordForActiveUsers(wikiEvent(id = 7L)) }
                }

            assertSame(expected, actual)
        }

    @Test
    fun `recordForActiveUsers rethrows AppError unchanged`() =
        runBlocking {
            sessionRepository.activeEmails += "alice@example.com"
            val expected = RepositoryReadError("boom")
            statsRepository.recordException = expected

            val actual =
                assertThrows(RepositoryReadError::class.java) {
                    runBlocking { service.recordForActiveUsers(wikiEvent(id = 8L)) }
                }

            assertSame(expected, actual)
        }

    @Test
    fun `recordForActiveUsers wraps unknown exceptions into StatsRecordingError`() =
        runBlocking {
            sessionRepository.activeEmails += "alice@example.com"
            val cause = IllegalStateException("unexpected")
            statsRepository.recordException = cause

            val actual =
                assertThrows(StatsRecordingError::class.java) {
                    runBlocking { service.recordForActiveUsers(wikiEvent(id = 99L)) }
                }

            assertEquals("Failed to record wiki event with id=99 for active users", actual.message)
            assertSame(cause, actual.cause)
        }

    @Test
    fun `getSnapshotForUser wraps unknown exceptions into StatsSnapshotError`() =
        runBlocking {
            val cause = IllegalArgumentException("unexpected")
            statsRepository.snapshotException = cause

            val actual =
                assertThrows(StatsSnapshotError::class.java) {
                    runBlocking { service.getSnapshotForUser("alice@example.com") }
                }

            assertEquals("Failed to fetch stats snapshot for user 'alice@example.com'", actual.message)
            assertSame(cause, actual.cause)
        }

    private fun wikiEvent(id: Long) =
        WikiEvent(
            schema = "schema",
            meta = null,
            id = id,
            type = "edit",
            namespace = 0,
            title = "Main Page",
            titleUrl = null,
            comment = null,
            timestamp = 1L,
            user = "tester",
            bot = false,
            notifyUrl = null,
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = null,
        )

    private class FakeSessionRepository : SessionRepository {
        val activeEmails = linkedSetOf<String>()

        override fun markLoggedIn(
            email: String,
            sessionMetadata: Map<String, String>,
        ) {
            activeEmails += email
        }

        override fun markLoggedOut(
            email: String?,
            sessionId: String?,
        ) {
            if (email != null) {
                activeEmails -= email
            }
        }

        override fun isActive(email: String): Boolean = email in activeEmails

        override fun listActiveEmails(): Set<String> = activeEmails.toSet()
    }

    private class FakeStatsRepository : StatsRepository {
        val recordedEvents = mutableListOf<Pair<String, WikiEvent>>()
        var recordException: Exception? = null
        var snapshotException: Exception? = null

        override suspend fun recordForUser(
            userEmail: String,
            event: WikiEvent,
        ) {
            recordException?.let { throw it }
            recordedEvents += userEmail to event
        }

        override suspend fun snapshotForUser(userEmail: String): StatsSnapshot {
            snapshotException?.let { throw it }
            return StatsSnapshot(id = userEmail)
        }
    }
}
