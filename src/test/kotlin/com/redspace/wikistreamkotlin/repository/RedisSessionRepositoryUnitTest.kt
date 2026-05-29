package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.security.JwtSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisKeyCommands
import org.springframework.data.redis.core.Cursor
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions

class RedisSessionRepositoryUnitTest {
    private val redisTemplate: RedisTemplate<String, String> = mock()
    private val jwtSecurityProperties: JwtSecurityProperties = mock()

    private val repository = RedisSessionRepository(redisTemplate, jwtSecurityProperties)

    @BeforeEach
    fun setUp() {
        whenever(jwtSecurityProperties.accessTokenTtlSeconds).thenReturn(3600L)
    }

    // ---------------------------------------------------------------------------
    // listActiveEmails — must use SCAN, never KEYS
    // ---------------------------------------------------------------------------

    @Test
    fun `listActiveEmails never invokes the blocking KEYS command`() {
        val connection: RedisConnection = mock()
        val keyCommands: RedisKeyCommands = mock()
        val cursor: Cursor<ByteArray> = mock()

        // Wire the mock chain: execute -> connection -> keyCommands -> scan -> cursor
        whenever(redisTemplate.execute(any<RedisCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<RedisCallback<Any>>(0)
            callback.doInRedis(connection)
        }
        whenever(connection.keyCommands()).thenReturn(keyCommands)
        whenever(keyCommands.scan(any<ScanOptions>())).thenReturn(cursor)
        whenever(cursor.hasNext()).thenReturn(false)

        repository.listActiveEmails()

        // KEYS must never be called
        verify(redisTemplate, never()).keys(any())
        // SCAN path must be taken via keyCommands
        verify(connection, never()).scan(any()) // deprecated overload must not be called
        verify(keyCommands).scan(any<ScanOptions>()) // non-deprecated path is used
    }

    @Test
    fun `listActiveEmails returns emails extracted from SCAN results`() {
        val connection: RedisConnection = mock()
        val keyCommands: RedisKeyCommands = mock()

        val keys =
            listOf(
                "active-session:alice@example.com",
                "active-session:bob@example.com",
                "active-session-jtis:alice@example.com", // must be excluded
            )
        val keyIterator = keys.map { it.toByteArray(Charsets.UTF_8) }.iterator()

        val cursor: Cursor<ByteArray> = mock()
        whenever(cursor.hasNext()).thenAnswer { keyIterator.hasNext() }
        whenever(cursor.next()).thenAnswer { keyIterator.next() }

        whenever(redisTemplate.execute(any<RedisCallback<Any>>())).thenAnswer { invocation ->
            val callback = invocation.getArgument<RedisCallback<Any>>(0)
            callback.doInRedis(connection)
        }
        whenever(connection.keyCommands()).thenReturn(keyCommands)
        whenever(keyCommands.scan(any<ScanOptions>())).thenReturn(cursor)

        // isActive → cleanupExpiredSessions → opsForZSet + opsForHash
        val opsForZSet: org.springframework.data.redis.core.ZSetOperations<String, String> = mock()
        val opsForHash: org.springframework.data.redis.core.HashOperations<String, String, String> = mock()
        whenever(redisTemplate.opsForZSet()).thenReturn(opsForZSet)
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(opsForHash)
        // Both emails have 1 active named session each → not deleted
        whenever(opsForZSet.zCard(any())).thenReturn(1L)
        // isActive → hasKey returns true for both emails
        whenever(redisTemplate.hasKey("active-session:alice@example.com")).thenReturn(true)
        whenever(redisTemplate.hasKey("active-session:bob@example.com")).thenReturn(true)

        val result = repository.listActiveEmails()

        assertEquals(setOf("alice@example.com", "bob@example.com"), result)
        verify(redisTemplate, never()).keys(any())
    }
}
