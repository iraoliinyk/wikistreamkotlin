package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.security.JwtSecurityProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

@Repository
@ConditionalOnProperty(name = ["app.session.backend"], havingValue = "redis")
class RedisSessionRepository(
    private val redisTemplate: RedisTemplate<String, String>,
    private val jwtSecurityProperties: JwtSecurityProperties
) : SessionRepository {

    override fun markLoggedIn(
        email: String,
        sessionMetadata: Map<String, String>
    ) {
        val key = email.toRedisKey()
        val sessionsKey = email.toRedisSessionsKey()
        val ttl = Duration.ofSeconds(jwtSecurityProperties.accessTokenTtlSeconds)
        val sessionId = sessionMetadata[SessionRepository.SessionMetadataKeys.JTI]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val expiresAt = sessionMetadata[SessionRepository.SessionMetadataKeys.EXPIRES_AT]
            ?.let(Instant::parse)
            ?: Instant.now().plusSeconds(jwtSecurityProperties.accessTokenTtlSeconds)

        val storedMetadata = sessionMetadata
            .filterKeys { it in SUPPORTED_METADATA_KEYS }
            .toMutableMap()
            .apply {
                putIfAbsent(SessionRepository.SessionMetadataKeys.LOGIN_AT, Instant.now().toString())
            }

        cleanupExpiredSessions(email)
        redisTemplate.opsForHash<String, String>().putAll(key, storedMetadata)
        redisTemplate.expire(key, ttl)
        if (sessionId != null) {
            redisTemplate.opsForZSet().add(sessionsKey, sessionId, expiresAt.toEpochMilli().toDouble())
            redisTemplate.expire(sessionsKey, ttl)
        } else {
            redisTemplate.opsForHash<String, String>().increment(key, ANONYMOUS_SESSION_COUNT_FIELD, 1)
        }
    }

    override fun markLoggedOut(email: String?, sessionId: String?) {
        if (email.isNullOrBlank()) return
        val key = email.toRedisKey()
        val sessionsKey = email.toRedisSessionsKey()
        val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }

        if (normalizedSessionId == null) {
            redisTemplate.delete(listOf(key, sessionsKey))
            return
        }

        cleanupExpiredSessions(email)
        redisTemplate.opsForZSet().remove(sessionsKey, normalizedSessionId)
        deleteKeysWhenInactive(email)
    }

    override fun isActive(email: String): Boolean {
        cleanupExpiredSessions(email)
        return redisTemplate.hasKey(email.toRedisKey())
    }

    override fun listActiveEmails(): Set<String> {
        return redisTemplate.keys("$KEY_PREFIX*")
            .orEmpty()
            .filterNot { it.contains(SESSIONS_KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .filter { isActive(it) }
            .toSet()
    }

    private fun String.toRedisKey() = "$KEY_PREFIX$this"

    private fun String.toRedisSessionsKey() = "$SESSIONS_KEY_PREFIX$this"

    private fun cleanupExpiredSessions(email: String) {
        val sessionsKey = email.toRedisSessionsKey()
        redisTemplate.opsForZSet().removeRangeByScore(sessionsKey, 0.0, Instant.now().toEpochMilli().toDouble())
        deleteKeysWhenInactive(email)
    }

    private fun deleteKeysWhenInactive(email: String) {
        val key = email.toRedisKey()
        val sessionsKey = email.toRedisSessionsKey()
        val activeNamedSessions = redisTemplate.opsForZSet().zCard(sessionsKey) ?: 0L
        val anonymousSessions = redisTemplate.opsForHash<String, String>()
            .get(key, ANONYMOUS_SESSION_COUNT_FIELD)
            ?.toLongOrNull()
            ?: 0L

        if (activeNamedSessions <= 0 && anonymousSessions <= 0) {
            redisTemplate.delete(listOf(key, sessionsKey))
        }
    }

    companion object {
        private const val KEY_PREFIX = "active-session:"
        private const val SESSIONS_KEY_PREFIX = "active-session-jtis:"
        private const val ANONYMOUS_SESSION_COUNT_FIELD = "__anonymousSessionCount"

        private val SUPPORTED_METADATA_KEYS = setOf(
            SessionRepository.SessionMetadataKeys.JTI,
            SessionRepository.SessionMetadataKeys.LOGIN_AT,
            SessionRepository.SessionMetadataKeys.EXPIRES_AT,
            SessionRepository.SessionMetadataKeys.INSTANCE_ID,
            SessionRepository.SessionMetadataKeys.CLIENT_IP,
            SessionRepository.SessionMetadataKeys.USER_AGENT,
            SessionRepository.SessionMetadataKeys.DEVICE_ID,
            SessionRepository.SessionMetadataKeys.SOURCE,
        )
    }
}