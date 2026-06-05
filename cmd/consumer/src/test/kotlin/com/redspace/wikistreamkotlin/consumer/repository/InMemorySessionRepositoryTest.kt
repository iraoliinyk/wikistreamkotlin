package com.redspace.wikistreamkotlin.consumer.repository

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemorySessionRepositoryTest {
    private val repository = InMemorySessionRepository()

    @Test
    fun `markLoggedOut with jti keeps email active until the last in-memory session logs out`() {
        val email = "alice@example.com"

        repository.markLoggedIn(
            email,
            mapOf(
                SessionRepository.SessionMetadataKeys.JTI to "jti-1",
                SessionRepository.SessionMetadataKeys.EXPIRES_AT to Instant.now().plusSeconds(3600).toString(),
            ),
        )
        repository.markLoggedIn(
            email,
            mapOf(
                SessionRepository.SessionMetadataKeys.JTI to "jti-2",
                SessionRepository.SessionMetadataKeys.EXPIRES_AT to Instant.now().plusSeconds(3600).toString(),
            ),
        )

        repository.markLoggedOut(email, "jti-1")
        assertTrue(repository.isActive(email))

        repository.markLoggedOut(email, "jti-2")
        assertFalse(repository.isActive(email))
    }

    @Test
    fun `expired in-memory sessions are removed during active checks`() {
        val email = "expired@example.com"

        repository.markLoggedIn(
            email,
            mapOf(
                SessionRepository.SessionMetadataKeys.JTI to "expired-jti",
                SessionRepository.SessionMetadataKeys.EXPIRES_AT to Instant.now().minusSeconds(5).toString(),
            ),
        )

        assertFalse(repository.isActive(email))
        assertFalse(repository.listActiveEmails().contains(email))
    }
}
