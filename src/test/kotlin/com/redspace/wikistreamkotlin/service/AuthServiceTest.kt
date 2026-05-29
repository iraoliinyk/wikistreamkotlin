package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.controller.dto.LoginRequest
import com.redspace.wikistreamkotlin.controller.dto.RegisterRequest
import com.redspace.wikistreamkotlin.controller.dto.TokenResponse
import com.redspace.wikistreamkotlin.domain.UserAccount
import com.redspace.wikistreamkotlin.exception.AuthValidationError
import com.redspace.wikistreamkotlin.exception.InvalidCredentialsError
import com.redspace.wikistreamkotlin.exception.MissingTokenSubjectError
import com.redspace.wikistreamkotlin.exception.UserAlreadyExistsError
import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import com.redspace.wikistreamkotlin.repository.SessionRepository
import com.redspace.wikistreamkotlin.repository.UserAccountAtomicRepository
import com.redspace.wikistreamkotlin.repository.UserAccountCassandraRepository
import com.redspace.wikistreamkotlin.security.GeneratedAccessToken
import com.redspace.wikistreamkotlin.security.JwtTokenService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.util.Optional

class AuthServiceTest {
    private val userRepo: UserAccountCassandraRepository = mock()
    private val userAccountAtomicRepo: UserAccountAtomicRepository = mock()
    private val revokedRepo: RevokedTokenCassandraRepository = mock()
    private val passwordEncoder = BCryptPasswordEncoder()
    private val jwtTokenService: JwtTokenService = mock()
    private val activeUserSessionService: ActiveUserSessionService = mock()

    private val authService =
        AuthService(
            userRepo,
            userAccountAtomicRepo,
            revokedRepo,
            passwordEncoder,
            jwtTokenService,
            activeUserSessionService,
        )

    @Test
    fun `register fails when atomic insert reports duplicate`() {
        runBlocking {
            whenever(userAccountAtomicRepo.insertIfNotExists(any())).thenReturn(false)

            assertThrows(UserAlreadyExistsError::class.java) {
                runBlocking {
                    authService.register(RegisterRequest("user@example.com", "password123"))
                }
            }
            verify(userAccountAtomicRepo).insertIfNotExists(any())
        }
    }

    @Test
    fun `login returns token for valid credentials`() =
        runBlocking {
            val hash = passwordEncoder.encode("password123") ?: error("Password hash should not be null")
            whenever(userRepo.findById("user@example.com"))
                .thenReturn(Optional.of(UserAccount(email = "user@example.com", passwordHash = hash, active = true)))
            whenever(jwtTokenService.generateAccessToken("user@example.com"))
                .thenReturn(
                    GeneratedAccessToken(
                        response = TokenResponse(accessToken = "token", expiresIn = 3600),
                        jti = "jti-123",
                    ),
                )

            val response = authService.login(LoginRequest(email = "user@example.com", password = "password123"))

            assertEquals("token", response.accessToken)
            assertEquals(3600, response.expiresIn)
            verify(activeUserSessionService).markLoggedIn(
                eq("user@example.com"),
                eq(mapOf(SessionRepository.SessionMetadataKeys.JTI to "jti-123")),
            )
        }

    @Test
    fun `login fails for invalid credentials`() {
        whenever(userRepo.findById("user@example.com")).thenReturn(Optional.empty())

        assertThrows(InvalidCredentialsError::class.java) {
            runBlocking { authService.login(LoginRequest(email = "user@example.com", password = "wrong")) }
        }
    }

    @Test
    fun `logout stores token jti in revocation table`() =
        runBlocking {
            val now = Instant.now()
            val jwt =
                Jwt
                    .withTokenValue("token")
                    .subject("user@example.com")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claim("jti", "jti-123")
                    .header("alg", "HS256")
                    .build()
            whenever(jwtTokenService.extractJti(jwt)) doReturn "jti-123"
            whenever(revokedRepo.saveWithTtl(any(), any())).thenAnswer { it.arguments[0] }

            authService.logout(jwt)

            val revokedTokenCaptor = argumentCaptor<com.redspace.wikistreamkotlin.domain.RevokedToken>()
            val ttlCaptor = argumentCaptor<Int>()
            verify(revokedRepo).saveWithTtl(revokedTokenCaptor.capture(), ttlCaptor.capture())
            val savedToken = revokedTokenCaptor.firstValue
            assertEquals("jti-123", savedToken.jti)
            assertEquals("user@example.com", savedToken.email)
            assertTrue(ttlCaptor.firstValue in 3598..3600)
            verify(activeUserSessionService).markLoggedOut(same("user@example.com"), same("jti-123"))
        }

    @Test
    fun `logout uses ttl floor of 1 second when token is already expired`() =
        runBlocking {
            val now = Instant.now()
            val jwt =
                Jwt
                    .withTokenValue("token")
                    .subject("user@example.com")
                    .issuedAt(now.minusSeconds(3601))
                    .expiresAt(now.minusSeconds(1))
                    .claim("jti", "jti-123")
                    .header("alg", "HS256")
                    .build()
            whenever(jwtTokenService.extractJti(jwt)).thenReturn("jti-123")
            whenever(revokedRepo.saveWithTtl(any(), any())).thenAnswer { it.arguments[0] }

            authService.logout(jwt)

            val ttlCaptor = argumentCaptor<Int>()
            verify(revokedRepo).saveWithTtl(any(), ttlCaptor.capture())
            assertEquals(1, ttlCaptor.firstValue)
        }

    // --- register edge cases ---

    @Test
    fun `register throws AuthValidationError for blank email`() {
        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.register(RegisterRequest(email = "   ", password = "password123")) }
        }
    }

    @Test
    fun `register throws AuthValidationError for invalid email format`() {
        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.register(RegisterRequest(email = "not-an-email", password = "password123")) }
        }
    }

    @Test
    fun `register throws AuthValidationError when password is shorter than 8 characters`() {
        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.register(RegisterRequest(email = "user@example.com", password = "short")) }
        }

        verify(userRepo, never()).save(any())
    }

    // --- login edge cases ---

    @Test
    fun `login throws InvalidCredentialsError for inactive user`() {
        val hash = passwordEncoder.encode("password123") ?: error("Password hash should not be null")
        whenever(userRepo.findById("user@example.com"))
            .thenReturn(Optional.of(UserAccount(email = "user@example.com", passwordHash = hash, active = false)))

        assertThrows(InvalidCredentialsError::class.java) {
            runBlocking { authService.login(LoginRequest(email = "user@example.com", password = "password123")) }
        }
    }

    @Test
    fun `login throws InvalidCredentialsError for wrong password`() {
        val hash = passwordEncoder.encode("correct-password") ?: error("Password hash should not be null")
        whenever(userRepo.findById("user@example.com"))
            .thenReturn(Optional.of(UserAccount(email = "user@example.com", passwordHash = hash, active = true)))

        assertThrows(InvalidCredentialsError::class.java) {
            runBlocking { authService.login(LoginRequest(email = "user@example.com", password = "wrong-password")) }
        }
    }

    // --- logout edge cases ---

    @Test
    fun `logout throws AuthValidationError when token has no jti`() {
        val now = Instant.now()
        val jwt =
            Jwt
                .withTokenValue("token")
                .subject("user@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .header("alg", "HS256")
                .build()
        whenever(jwtTokenService.extractJti(jwt)).thenReturn(null)

        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.logout(jwt) }
        }

        verify(revokedRepo, never()).saveWithTtl(any(), any())
    }

    @Test
    fun `logout throws MissingTokenSubjectError when token subject is null`() {
        val now = Instant.now()
        val jwt =
            Jwt
                .withTokenValue("token")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("jti", "jti-123")
                .header("alg", "HS256")
                .build()
        whenever(jwtTokenService.extractJti(jwt)).thenReturn("jti-123")

        assertThrows(MissingTokenSubjectError::class.java) {
            runBlocking { authService.logout(jwt) }
        }

        verify(revokedRepo, never()).saveWithTtl(any(), any())
        verify(activeUserSessionService, never()).markLoggedOut(anyOrNull(), anyOrNull())
    }

    @Test
    fun `logout throws MissingTokenSubjectError when token subject is blank`() {
        val now = Instant.now()
        val jwt =
            Jwt
                .withTokenValue("token")
                .subject("   ")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("jti", "jti-123")
                .header("alg", "HS256")
                .build()
        whenever(jwtTokenService.extractJti(jwt)).thenReturn("jti-123")

        assertThrows(MissingTokenSubjectError::class.java) {
            runBlocking { authService.logout(jwt) }
        }

        verify(revokedRepo, never()).saveWithTtl(any(), any())
        verify(activeUserSessionService, never()).markLoggedOut(anyOrNull(), anyOrNull())
    }
}
