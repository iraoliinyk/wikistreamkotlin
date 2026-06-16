package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.consumer.controller.dto.LoginRequest
import com.redspace.wikistreamkotlin.consumer.controller.dto.RegisterRequest
import com.redspace.wikistreamkotlin.consumer.domain.RevokedToken
import com.redspace.wikistreamkotlin.consumer.domain.UserAccount
import com.redspace.wikistreamkotlin.consumer.repository.RevokedTokenCassandraRepository
import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.repository.UserAccountCassandraRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import com.redspace.wikistreamkotlin.consumer.security.JwtTokenService
import com.redspace.wikistreamkotlin.core.exception.AuthValidationError
import com.redspace.wikistreamkotlin.core.exception.InvalidCredentialsError
import com.redspace.wikistreamkotlin.core.exception.MissingTokenSubjectError
import com.redspace.wikistreamkotlin.core.exception.UserAlreadyExistsError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class AuthServiceTest {
    private val userAccountRepository = Mockito.mock(UserAccountCassandraRepository::class.java)
    private val revokedTokenRepository = Mockito.mock(RevokedTokenCassandraRepository::class.java)
    private val passwordEncoder = Mockito.mock(PasswordEncoder::class.java)
    private val jwtEncoder = CapturingJwtEncoder()
    private val jwtSecurityProperties =
        JwtSecurityProperties(
            issuer = "test-issuer",
            secret = "12345678901234567890123456789012",
            accessTokenTtlSeconds = 3600L,
        )
    private val sessionRepository = RecordingSessionRepository()
    private val activeUserSessionService = ActiveUserSessionService(sessionRepository, jwtSecurityProperties, "test-instance")
    private val jwtTokenService = JwtTokenService(jwtEncoder, jwtSecurityProperties)
    private val service =
        AuthService(
            userAccountRepository,
            revokedTokenRepository,
            passwordEncoder,
            jwtTokenService,
            activeUserSessionService,
        )

    @BeforeEach
    fun resetState() {
        Mockito.reset(userAccountRepository, revokedTokenRepository, passwordEncoder)
        sessionRepository.reset()
    }

    @Test
    fun `register rejects blank email`() {
        val actual =
            assertThrows(AuthValidationError::class.java) {
                runBlocking { service.register(RegisterRequest("   ", "password123")) }
            }

        assertEquals("Email must not be blank", actual.message)
    }

    @Test
    fun `register rejects invalid email format`() {
        val actual =
            assertThrows(AuthValidationError::class.java) {
                runBlocking { service.register(RegisterRequest("not-an-email", "password123")) }
            }

        assertEquals("Email format is invalid", actual.message)
    }

    @Test
    fun `register rejects short passwords`() {
        val actual =
            assertThrows(AuthValidationError::class.java) {
                runBlocking { service.register(RegisterRequest("alice@example.com", "short")) }
            }

        assertEquals("Password must be at least 8 characters", actual.message)
    }

    @Test
    fun `register throws UserAlreadyExistsError when user already exists`() {
        Mockito.`when`(passwordEncoder.encode("password123")).thenReturn("hashed-password")
        Mockito.`when`(userAccountRepository.existsById("alice@example.com")).thenReturn(true)

        val actual =
            assertThrows(UserAlreadyExistsError::class.java) {
                runBlocking { service.register(RegisterRequest("Alice@Example.com", "password123")) }
            }

        assertEquals("User with email 'alice@example.com' already exists", actual.message)
    }

    @Test
    fun `login fails when user is inactive`() {
        Mockito.`when`(userAccountRepository.findById("alice@example.com"))
            .thenReturn(Optional.of(UserAccount("alice@example.com", "stored-hash", active = false)))

        val actual =
            assertThrows(InvalidCredentialsError::class.java) {
                runBlocking { service.login(LoginRequest("alice@example.com", "password123")) }
            }

        assertEquals("Invalid email or password", actual.message)
    }

    @Test
    fun `login fails on wrong password`() {
        Mockito.`when`(userAccountRepository.findById("alice@example.com"))
            .thenReturn(Optional.of(UserAccount("alice@example.com", "stored-hash", active = true)))
        Mockito.`when`(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false)

        val actual =
            assertThrows(InvalidCredentialsError::class.java) {
                runBlocking { service.login(LoginRequest("alice@example.com", "wrong-password")) }
            }

        assertEquals("Invalid email or password", actual.message)
    }

    @Test
    fun `logout throws when JWT has no jti`() {
        val actual =
            assertThrows(AuthValidationError::class.java) {
                runBlocking { service.logout(jwt(subject = "alice@example.com", jti = null, expiresAt = Instant.now().plusSeconds(30))) }
            }

        assertEquals("Token does not contain jti", actual.message)
    }

    @Test
    fun `logout throws when JWT subject is missing`() {
        val actual =
            assertThrows(MissingTokenSubjectError::class.java) {
                runBlocking { service.logout(jwt(subject = null, jti = "jti-1", expiresAt = Instant.now().plusSeconds(30))) }
            }

        assertEquals("Token does not contain subject", actual.message)
    }

    @Test
    fun `logout throws when JWT subject is blank`() {
        val actual =
            assertThrows(MissingTokenSubjectError::class.java) {
                runBlocking { service.logout(jwt(subject = "   ", jti = "jti-1", expiresAt = Instant.now().plusSeconds(30))) }
            }

        assertEquals("Token does not contain subject", actual.message)
    }

    @Test
    fun `logout computes TTL floor of one second for expired tokens`() {
        val capturedTtl = AtomicInteger(-1)
        Mockito.`when`(revokedTokenRepository.saveWithTtl(any(), Mockito.anyInt()))
            .thenAnswer {
                capturedTtl.set(it.getArgument(1))
                it.getArgument<RevokedToken>(0)
            }

        runBlocking {
            service.logout(jwt(subject = "alice@example.com", jti = "jti-1", expiresAt = Instant.now().minusSeconds(60)))
        }

        assertEquals(1, capturedTtl.get())
        assertEquals("alice@example.com", sessionRepository.lastLoggedOutEmail)
        assertEquals("jti-1", sessionRepository.lastLoggedOutSessionId)
    }

    @Test
    fun `logout computes TTL ceiling of Int MAX VALUE for very long lived tokens`() {
        val capturedTtl = AtomicInteger(-1)
        Mockito.`when`(revokedTokenRepository.saveWithTtl(any(), Mockito.anyInt()))
            .thenAnswer {
                capturedTtl.set(it.getArgument(1))
                it.getArgument<RevokedToken>(0)
            }

        runBlocking {
            service.logout(
                jwt(
                    subject = "alice@example.com",
                    jti = "jti-2",
                    expiresAt = Instant.now().plusSeconds(Int.MAX_VALUE.toLong() + 120L),
                ),
            )
        }

        assertEquals(Int.MAX_VALUE, capturedTtl.get())
        assertEquals("alice@example.com", sessionRepository.lastLoggedOutEmail)
        assertEquals("jti-2", sessionRepository.lastLoggedOutSessionId)
    }

    @Test
    fun `login stores generated session jti for active user`() {
        Mockito.`when`(userAccountRepository.findById("alice@example.com"))
            .thenReturn(Optional.of(UserAccount("alice@example.com", "stored-hash", active = true)))
        Mockito.`when`(passwordEncoder.matches("password123", "stored-hash")).thenReturn(true)

        val response = runBlocking { service.login(LoginRequest("Alice@example.com", "password123")) }

        assertTrue(response.accessToken.isNotBlank())
        assertEquals("alice@example.com", sessionRepository.lastLoggedInEmail)
        assertEquals(sessionRepository.lastLoggedInMetadata[SessionRepository.SessionMetadataKeys.JTI], jwtEncoder.lastJti)
    }

    private fun jwt(
        subject: String?,
        jti: String?,
        expiresAt: Instant,
    ): Jwt {
        val claims = mutableMapOf<String, Any>()
        if (subject != null) claims["sub"] = subject
        if (jti != null) claims["jti"] = jti
        return Jwt(
            "token-value",
            expiresAt.minusSeconds(5),
            expiresAt,
            mapOf("alg" to "HS256"),
            claims,
        )
    }

    private class RecordingSessionRepository : SessionRepository {
        var lastLoggedInEmail: String? = null
        var lastLoggedInMetadata: Map<String, String> = emptyMap()
        var lastLoggedOutEmail: String? = null
        var lastLoggedOutSessionId: String? = null

        fun reset() {
            lastLoggedInEmail = null
            lastLoggedInMetadata = emptyMap()
            lastLoggedOutEmail = null
            lastLoggedOutSessionId = null
        }

        override fun markLoggedIn(
            email: String,
            sessionMetadata: Map<String, String>,
        ) {
            lastLoggedInEmail = email
            lastLoggedInMetadata = sessionMetadata
        }

        override fun markLoggedOut(
            email: String?,
            sessionId: String?,
        ) {
            lastLoggedOutEmail = email
            lastLoggedOutSessionId = sessionId
        }

        override fun isActive(email: String): Boolean = false

        override fun listActiveEmails(): Set<String> = emptySet()
    }

    private class CapturingJwtEncoder : JwtEncoder {
        var lastJti: String? = null

        override fun encode(parameters: JwtEncoderParameters): Jwt {
            val claimsSet: JwtClaimsSet = parameters.claims
            val claims = mutableMapOf<String, Any>()
            val jti = claimsSet.id ?: "generated-jti"
            claims["jti"] = jti
            claims["sub"] = claimsSet.subject
            claims["scope"] = claimsSet.getClaim<String>("scope")
            lastJti = jti
            return Jwt(
                "encoded-token-$jti",
                claimsSet.issuedAt ?: Instant.now(),
                claimsSet.expiresAt ?: Instant.now().plusSeconds(60),
                mapOf("alg" to MacAlgorithm.HS256.name),
                claims,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T {
        Mockito.any<T>()
        return null as T
    }
}
