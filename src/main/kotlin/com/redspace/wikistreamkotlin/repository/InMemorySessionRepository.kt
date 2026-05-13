package com.redspace.wikistreamkotlin.repository

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session repository using a concurrent hash map.
 * Used for testing and as fallback when Redis is disabled.
 */
@Repository
@ConditionalOnProperty(name = ["app.session.backend"], havingValue = "in-memory", matchIfMissing = true)
class InMemorySessionRepository : SessionRepository {
    private val activeSessions = ConcurrentHashMap<String, SessionState>()

    override fun markLoggedIn(email: String, sessionMetadata: Map<String, String>) {
        val expiresAt = sessionMetadata[SessionRepository.SessionMetadataKeys.EXPIRES_AT]
            ?.let(Instant::parse)
        val sessionId = sessionMetadata[SessionRepository.SessionMetadataKeys.JTI]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        activeSessions.compute(email) { _, existing ->
            val current = existing ?: SessionState()
            current.removeExpiredSessions()
            if (sessionId != null) {
                current.jtiExpirations[sessionId] = expiresAt
            } else {
                current.anonymousSessionCount += 1
            }
            current.takeIf { !it.isEmpty() }
        }
    }

    override fun markLoggedOut(email: String?, sessionId: String?) {
        if (email.isNullOrBlank()) return

        val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedSessionId == null) {
            activeSessions.remove(email)
            return
        }

        activeSessions.computeIfPresent(email) { _, state ->
            state.removeExpiredSessions()
            state.jtiExpirations.remove(normalizedSessionId)
            state.takeUnless { it.isEmpty() }
        }
    }

    override fun isActive(email: String): Boolean {
        cleanupExpiredSessions(email)
        return activeSessions.containsKey(email)
    }

    override fun listActiveEmails(): Set<String> {
        activeSessions.keys.forEach(::cleanupExpiredSessions)
        return activeSessions.keys.toSet()
    }

    private fun cleanupExpiredSessions(email: String) {
        activeSessions.computeIfPresent(email) { _, state ->
            state.removeExpiredSessions()
            state.takeUnless { it.isEmpty() }
        }
    }

    private class SessionState {
        val jtiExpirations: MutableMap<String, Instant?> = ConcurrentHashMap()
        var anonymousSessionCount: Int = 0

        fun removeExpiredSessions(now: Instant = Instant.now()) {
            jtiExpirations.entries.removeIf { (_, expiresAt) -> expiresAt?.isAfter(now) == false }
        }

        fun isEmpty(): Boolean = jtiExpirations.isEmpty() && anonymousSessionCount <= 0
    }
}
