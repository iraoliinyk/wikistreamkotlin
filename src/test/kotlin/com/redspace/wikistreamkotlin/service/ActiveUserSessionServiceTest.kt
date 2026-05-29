package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.repository.SessionRepository
import com.redspace.wikistreamkotlin.security.JwtSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ActiveUserSessionServiceTest {
    private val sessionRepository = FakeSessionRepository()
    private val jwtSecurityProperties =
        JwtSecurityProperties(
            issuer = "test-issuer",
            secret = "test-secret",
            accessTokenTtlSeconds = 3600L,
        )
    private val service = ActiveUserSessionService(sessionRepository, jwtSecurityProperties, "test-instance")

    @Test
    fun `markLoggedIn adds email to active users`() {
        service.markLoggedIn("alice@example.com")

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut removes logged-in email`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut("alice@example.com")

        assertFalse(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut with null email does nothing`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut(null)

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut with blank email does nothing`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut("   ")

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `listActiveUsers returns a snapshot unaffected by subsequent mutations`() {
        service.markLoggedIn("alice@example.com")
        val snapshotBefore = service.listActiveUsers()

        service.markLoggedIn("bob@example.com")

        assertFalse(snapshotBefore.contains("bob@example.com"))
    }

    @Test
    fun `multiple users can be tracked simultaneously`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedIn("bob@example.com")
        service.markLoggedIn("carol@example.com")

        val active = service.listActiveUsers()
        assertEquals(3, active.size)
        assertTrue(active.containsAll(listOf("alice@example.com", "bob@example.com", "carol@example.com")))
    }

    @Test
    fun `markLoggedIn is idempotent for the same email`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedIn("alice@example.com")

        assertEquals(1, service.listActiveUsers().size)
    }

    @Test
    fun `markLoggedOut with jti keeps email active until the last session logs out`() {
        service.markLoggedIn(
            "alice@example.com",
            mapOf(SessionRepository.SessionMetadataKeys.JTI to "jti-1"),
        )
        service.markLoggedIn(
            "alice@example.com",
            mapOf(SessionRepository.SessionMetadataKeys.JTI to "jti-2"),
        )

        service.markLoggedOut("alice@example.com", "jti-1")
        assertTrue(service.listActiveUsers().contains("alice@example.com"))

        service.markLoggedOut("alice@example.com", "jti-2")
        assertFalse(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut without jti removes all sessions for the email`() {
        service.markLoggedIn(
            "alice@example.com",
            mapOf(SessionRepository.SessionMetadataKeys.JTI to "jti-1"),
        )
        service.markLoggedIn(
            "alice@example.com",
            mapOf(SessionRepository.SessionMetadataKeys.JTI to "jti-2"),
        )

        service.markLoggedOut("alice@example.com")

        assertFalse(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut for non-registered email does nothing`() {
        service.markLoggedOut("ghost@example.com")

        assertTrue(service.listActiveUsers().isEmpty())
    }

    @Test
    fun `markLoggedIn generates base session metadata and keeps supported overrides`() {
        service.markLoggedIn(
            "alice@example.com",
            mapOf(
                SessionRepository.SessionMetadataKeys.JTI to "jti-1",
                SessionRepository.SessionMetadataKeys.CLIENT_IP to "127.0.0.1",
                SessionRepository.SessionMetadataKeys.SOURCE to "mobile",
                "ignored" to "ignored",
            ),
        )

        val metadata = sessionRepository.lastLoginMetadata
        assertEquals("jti-1", metadata[SessionRepository.SessionMetadataKeys.JTI])
        assertEquals("127.0.0.1", metadata[SessionRepository.SessionMetadataKeys.CLIENT_IP])
        assertEquals("mobile", metadata[SessionRepository.SessionMetadataKeys.SOURCE])
        assertEquals("test-instance", metadata[SessionRepository.SessionMetadataKeys.INSTANCE_ID])
        assertNotNull(metadata[SessionRepository.SessionMetadataKeys.LOGIN_AT])
        assertNotNull(metadata[SessionRepository.SessionMetadataKeys.EXPIRES_AT])
        assertFalse(metadata.containsKey("ignored"))

        val loginAt = requireNotNull(metadata[SessionRepository.SessionMetadataKeys.LOGIN_AT])
        val expiresAt = requireNotNull(metadata[SessionRepository.SessionMetadataKeys.EXPIRES_AT])
        assertTrue(Instant.parse(expiresAt).isAfter(Instant.parse(loginAt)))
    }

    private class FakeSessionRepository : SessionRepository {
        private val activeSessions = linkedMapOf<String, MutableSet<String>>()
        private val anonymousSessions = linkedMapOf<String, Int>()
        var lastLoginMetadata: Map<String, String> = emptyMap()
            private set

        override fun markLoggedIn(
            email: String,
            sessionMetadata: Map<String, String>,
        ) {
            val sessionId = sessionMetadata[SessionRepository.SessionMetadataKeys.JTI]
            if (sessionId.isNullOrBlank()) {
                anonymousSessions[email] = (anonymousSessions[email] ?: 0) + 1
            } else {
                activeSessions.computeIfAbsent(email) { linkedSetOf() }.add(sessionId)
            }
            lastLoginMetadata = sessionMetadata
        }

        override fun markLoggedOut(
            email: String?,
            sessionId: String?,
        ) {
            if (email.isNullOrBlank()) return
            if (sessionId.isNullOrBlank()) {
                activeSessions.remove(email)
                anonymousSessions.remove(email)
                return
            }

            activeSessions[email]?.remove(sessionId)
            if (activeSessions[email].isNullOrEmpty()) {
                activeSessions.remove(email)
            }
        }

        @Suppress("MaxLineLength")
        override fun isActive(email: String): Boolean = !activeSessions[email].isNullOrEmpty() || (anonymousSessions[email] ?: 0) > 0

        @Suppress("MaxLineLength")
        override fun listActiveEmails(): Set<String> = (activeSessions.keys + anonymousSessions.filterValues { it > 0 }.keys).toSet()
    }
}
