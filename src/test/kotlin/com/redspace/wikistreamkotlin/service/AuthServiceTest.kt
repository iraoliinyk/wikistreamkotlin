package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.controller.dto.LoginRequest
import com.redspace.wikistreamkotlin.controller.dto.RegisterRequest
import com.redspace.wikistreamkotlin.controller.dto.TokenResponse
import com.redspace.wikistreamkotlin.domain.UserAccount
import com.redspace.wikistreamkotlin.exception.AuthValidationError
import com.redspace.wikistreamkotlin.exception.InvalidCredentialsError
import com.redspace.wikistreamkotlin.exception.UserAlreadyExistsError
import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import com.redspace.wikistreamkotlin.repository.UserAccountCassandraRepository
import com.redspace.wikistreamkotlin.security.JwtTokenService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doAnswer
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
    private val revokedRepo: RevokedTokenCassandraRepository = mock()
    private val passwordEncoder = BCryptPasswordEncoder()
    private val jwtTokenService: JwtTokenService = mock()
    private val activeUserSessionService: ActiveUserSessionService = mock()

    private val authService = AuthService(
        userRepo,
        revokedRepo,
        passwordEncoder,
        jwtTokenService,
        activeUserSessionService
    )

    @Test
    fun `register saves normalized email with hashed password`() {
        runBlocking {
            whenever(userRepo.existsById("user@example.com")).thenReturn(false)
            doAnswer { invocation -> invocation.getArgument<UserAccount>(0) }
                .whenever(userRepo)
                .save(any<UserAccount>())

            val response = authService.register(RegisterRequest(email = " User@Example.com ", password = "password123"))

            assertEquals("user@example.com", response.email)
            val savedUserCaptor = argumentCaptor<UserAccount>()
            verify(userRepo).save(savedUserCaptor.capture())
            val savedUser = savedUserCaptor.firstValue

            assertEquals("user@example.com", savedUser.email)
            assertNotEquals("password123", savedUser.passwordHash)
            assertTrue(passwordEncoder.matches("password123", savedUser.passwordHash))
        }
    }

    @Test
    fun `register fails when email already exists`() {
        whenever(userRepo.existsById("user@example.com")).thenReturn(true)

        assertThrows(UserAlreadyExistsError::class.java) {
            runBlocking {
                authService.register(RegisterRequest(email = "user@example.com", password = "password123"))
            }
        }

        verify(userRepo, never()).save(any())
    }

    @Test
    fun `login returns token for valid credentials`() = runBlocking {
        val hash = passwordEncoder.encode("password123") ?: error("Password hash should not be null")
        whenever(userRepo.findById("user@example.com"))
            .thenReturn(Optional.of(UserAccount(email = "user@example.com", passwordHash = hash, active = true)))
        whenever(jwtTokenService.createAccessToken("user@example.com"))
            .thenReturn(TokenResponse(accessToken = "token", expiresIn = 3600))

        val response = authService.login(LoginRequest(email = "user@example.com", password = "password123"))

        assertEquals("token", response.accessToken)
        assertEquals(3600, response.expiresIn)
        verify(activeUserSessionService).markLoggedIn("user@example.com")
    }

    @Test
    fun `login fails for invalid credentials`() {
        whenever(userRepo.findById("user@example.com")).thenReturn(Optional.empty())

        assertThrows(InvalidCredentialsError::class.java) {
            runBlocking { authService.login(LoginRequest(email = "user@example.com", password = "wrong")) }
        }
    }

    @Test
    fun `logout stores token jti in revocation table`() = runBlocking {
        val now = Instant.now()
        val jwt = Jwt.withTokenValue("token")
            .subject("user@example.com")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .claim("jti", "jti-123")
            .header("alg", "HS256")
            .build()
        whenever(jwtTokenService.extractJti(jwt)) doReturn "jti-123"
        whenever(revokedRepo.save(any())).thenAnswer { it.arguments[0] }

        authService.logout(jwt)

        val revokedTokenCaptor = argumentCaptor<com.redspace.wikistreamkotlin.domain.RevokedToken>()
        verify(revokedRepo).save(revokedTokenCaptor.capture())
        val savedToken = revokedTokenCaptor.firstValue
        assertEquals("jti-123", savedToken.jti)
        assertEquals("user@example.com", savedToken.email)
        verify(activeUserSessionService).markLoggedOut(same("user@example.com"))
    }

    // -------------------------------------------------- emailExists --------------------------------------------------

    @Test
    fun `emailExists returns true when user is registered`() = runBlocking {
        whenever(userRepo.existsById("user@example.com")).thenReturn(true)

        assertTrue(authService.emailExists("user@example.com"))
    }

    @Test
    fun `emailExists returns false when user is not registered`() = runBlocking {
        whenever(userRepo.existsById("unknown@example.com")).thenReturn(false)

        assertEquals(false, authService.emailExists("unknown@example.com"))
    }

    @Test
    fun `emailExists normalizes email before lookup`() = runBlocking {
        whenever(userRepo.existsById("user@example.com")).thenReturn(true)

        assertTrue(authService.emailExists(" User@Example.COM "))
    }

    // -------------------------------------------------- register edge cases --------------------------------------------------

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
        whenever(userRepo.existsById(any())).thenReturn(false)

        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.register(RegisterRequest(email = "user@example.com", password = "short")) }
        }

        verify(userRepo, never()).save(any())
    }

    // -------------------------------------------------- login edge cases --------------------------------------------------

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

    // -------------------------------------------------- logout edge cases --------------------------------------------------

    @Test
    fun `logout throws AuthValidationError when token has no jti`() {
        val now = Instant.now()
        val jwt = Jwt.withTokenValue("token")
            .subject("user@example.com")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .header("alg", "HS256")
            .build()
        whenever(jwtTokenService.extractJti(jwt)).thenReturn(null)

        assertThrows(AuthValidationError::class.java) {
            runBlocking { authService.logout(jwt) }
        }

        verify(revokedRepo, never()).save(any())
    }
}
