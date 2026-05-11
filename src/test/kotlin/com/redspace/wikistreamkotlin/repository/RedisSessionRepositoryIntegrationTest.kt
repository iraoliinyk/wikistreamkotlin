package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.WikistreamkotlinApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [WikistreamkotlinApplication::class, RedisSessionRepositoryIntegrationTest.TestRedisConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.web-application-type=none",
        "app.session.backend=redis",
    ],
)

@Testcontainers
class RedisSessionRepositoryIntegrationTest {

    @TestConfiguration
    class TestRedisConfig {
        @Bean
        @Primary
        fun testRedisConnectionFactory(): RedisConnectionFactory {
            return LettuceConnectionFactory(redis.host, redis.getMappedPort(REDIS_PORT))
        }

        @Bean
        @Primary
        fun testRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
            return RedisTemplate<String, String>().apply {
                setConnectionFactory(connectionFactory)
                keySerializer = StringRedisSerializer()
                valueSerializer = StringRedisSerializer()
                hashKeySerializer = StringRedisSerializer()
                hashValueSerializer = StringRedisSerializer()
                afterPropertiesSet()
            }
        }
    }

    @Autowired
    private lateinit var sessionRepository: SessionRepository

    @Autowired
    private lateinit var redisTemplate: RedisTemplate<String, String>

    @BeforeEach
    fun cleanRedis() {
        redisTemplate.keys("$KEY_PREFIX*").orEmpty().forEach { redisTemplate.delete(it) }
    }

    @Test
    fun `markLoggedIn marks user active and markLoggedOut deactivates`() {
        val email = "alice@example.com"

        sessionRepository.markLoggedIn(email)
        assertTrue(sessionRepository.isActive(email))

        sessionRepository.markLoggedOut(email)
        assertFalse(sessionRepository.isActive(email))
    }

    @Test
    fun `listActiveEmails returns active users only`() {
        val alice = "alice@example.com"
        val bob = "bob@example.com"

        sessionRepository.markLoggedIn(alice)
        sessionRepository.markLoggedIn(bob)

        assertEquals(setOf(alice, bob), sessionRepository.listActiveEmails())

        sessionRepository.markLoggedOut(alice)
        assertEquals(setOf(bob), sessionRepository.listActiveEmails())
    }

    @Test
    fun `markLoggedIn persists supported metadata, ignores unsupported keys, and sets loginAt`() {
        val email = "meta@example.com"
        val metadata = mapOf(
            SessionRepository.SessionMetadataKeys.JTI to "jti-123",
            SessionRepository.SessionMetadataKeys.CLIENT_IP to "127.0.0.1",
            SessionRepository.SessionMetadataKeys.SOURCE to "postman",
            "unsupported" to "value",
        )

        sessionRepository.markLoggedIn(email, metadata)

        val stored = redisTemplate.opsForHash<String, String>().entries("$KEY_PREFIX$email")
        assertEquals("jti-123", stored[SessionRepository.SessionMetadataKeys.JTI])
        assertEquals("127.0.0.1", stored[SessionRepository.SessionMetadataKeys.CLIENT_IP])
        assertEquals("postman", stored[SessionRepository.SessionMetadataKeys.SOURCE])
        assertNotNull(stored[SessionRepository.SessionMetadataKeys.LOGIN_AT])
        assertFalse(stored.containsKey("unsupported"))
    }

    @Test
    fun `markLoggedIn sets Redis key TTL based on JWT access token ttl`() {
        val email = "ttl@example.com"

        sessionRepository.markLoggedIn(email)

        val ttlSeconds = redisTemplate.getExpire("$KEY_PREFIX$email", TimeUnit.SECONDS)
        assertTrue(ttlSeconds in 1..3600)
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val KEY_PREFIX = "active-session:"

        @JvmField
        val redis = GenericContainer("redis:7").withExposedPorts(REDIS_PORT).apply {
            start()

        }
    }
}