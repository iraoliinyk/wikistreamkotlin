package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.controller.dto.LoginRequest
import com.redspace.wikistreamkotlin.controller.dto.RegisterRequest
import com.redspace.wikistreamkotlin.controller.dto.RegisterResponse
import com.redspace.wikistreamkotlin.controller.dto.TokenResponse
import com.redspace.wikistreamkotlin.domain.RevokedToken
import com.redspace.wikistreamkotlin.domain.UserAccount
import com.redspace.wikistreamkotlin.exception.AuthValidationError
import com.redspace.wikistreamkotlin.exception.InvalidCredentialsError
import com.redspace.wikistreamkotlin.exception.UserAlreadyExistsError
import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import com.redspace.wikistreamkotlin.repository.UserAccountAtomicRepository
import com.redspace.wikistreamkotlin.repository.UserAccountCassandraRepository
import com.redspace.wikistreamkotlin.security.JwtTokenService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import java.time.Instant

@Service
@ConditionalOnProperty(name = ["app.auth.enabled"], havingValue = "true", matchIfMissing = true)
class AuthService(
    private val userAccountRepository: UserAccountCassandraRepository,
    private val userAccountAtomicRepository: UserAccountAtomicRepository,
    private val revokedTokenRepository: RevokedTokenCassandraRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenService: JwtTokenService,
    private val activeUserSessionService: ActiveUserSessionService
) {

    suspend fun emailExists(rawEmail: String): Boolean = withContext(Dispatchers.IO) {
        userAccountRepository.existsById(normalizeEmail(rawEmail))
    }

    suspend fun register(request: RegisterRequest): RegisterResponse = withContext(Dispatchers.IO) {
        val email = normalizeEmail(request.email)
        validatePassword(request.password)

       val passwordHash = passwordEncoder.encode(request.password)
            ?: throw AuthValidationError("Password hashing failed")

        val now = Instant.now()
        val account = UserAccount(
        email = email,
        passwordHash = passwordHash,
        createdAt = now,
        updatedAt = now,
        active = true
        )
        val inserted = userAccountAtomicRepository.insertIfNotExists(account)
        if (!inserted) {
            throw UserAlreadyExistsError("User with email '$email' already exists")
        }
        RegisterResponse(email = account.email, createdAt = account.createdAt)
    }

    suspend fun login(request: LoginRequest): TokenResponse = withContext(Dispatchers.IO) {
        val email = normalizeEmail(request.email)
        val user = userAccountRepository.findById(email).orElse(null)
            ?: throw InvalidCredentialsError("Invalid email or password")

        if (!user.active || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw InvalidCredentialsError("Invalid email or password")
        }

        activeUserSessionService.markLoggedIn(user.email)
        jwtTokenService.createAccessToken(user.email)
    }

    suspend fun logout(jwt: Jwt) = withContext(Dispatchers.IO) {
        val jti = jwtTokenService.extractJti(jwt)
            ?: throw AuthValidationError("Token does not contain jti")

        val expiresAt = jwt.expiresAt ?: Instant.now()
        revokedTokenRepository.save(
            RevokedToken(
                jti = jti,
                email = jwt.subject ?: "unknown",
                expiresAt = expiresAt,
                revokedAt = Instant.now()
            )
        )
        activeUserSessionService.markLoggedOut(jwt.subject)
    }

    private fun normalizeEmail(rawEmail: String): String {
        val normalized = rawEmail.trim().lowercase()
        if (normalized.isBlank()) {
            throw AuthValidationError("Email must not be blank")
        }
        if (!EMAIL_REGEX.matches(normalized)) {
            throw AuthValidationError("Email format is invalid")
        }
        return normalized
    }

    private fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw AuthValidationError("Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
    }
}
