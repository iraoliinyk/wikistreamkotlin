package com.redspace.wikistreamkotlin.repository

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session repository using a concurrent hash map.
 * Used for testing and as fallback when Redis is disabled.
 */
@Repository
@ConditionalOnProperty(name = ["app.session.backend"], havingValue = "in-memory", matchIfMissing = true)
class InMemorySessionRepository : SessionRepository {
    private val activeEmails = ConcurrentHashMap.newKeySet<String>()

    override fun markLoggedIn(email: String, sessionMetadata: Map<String, String>) {
        activeEmails.add(email)
    }

    override fun markLoggedOut(email: String?) {
        if (email.isNullOrBlank()) return
        activeEmails.remove(email)
    }

    override fun isActive(email: String): Boolean {
        return activeEmails.contains(email)
    }

    override fun listActiveEmails(): Set<String> {
        return activeEmails.toSet()
    }
}
