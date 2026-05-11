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
        val ttl = Duration.ofSeconds(jwtSecurityProperties.accessTokenTtlSeconds)

        val storedMetadata = sessionMetadata
            .filterKeys { it in SUPPORTED_METADATA_KEYS }
            .toMutableMap()
            .apply {
                putIfAbsent(SessionRepository.SessionMetadataKeys.LOGIN_AT, Instant.now().toString())
            }

        redisTemplate.opsForHash<String, String>().putAll(key, storedMetadata)
        redisTemplate.expire(key, ttl)
    }

    override fun markLoggedOut(email: String?) {
        if (email.isNullOrBlank()) return
        redisTemplate.delete(email.toRedisKey())
    }

    override fun isActive(email: String): Boolean {
        return redisTemplate.hasKey(email.toRedisKey())
    }

    override fun listActiveEmails(): Set<String> {
        return redisTemplate.keys("$KEY_PREFIX*")
            .orEmpty()
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
    }

    private fun String.toRedisKey() = "$KEY_PREFIX$this"

    companion object {
        private const val KEY_PREFIX = "active-session:"

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