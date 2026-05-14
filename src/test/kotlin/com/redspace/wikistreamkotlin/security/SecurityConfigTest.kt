package com.redspace.wikistreamkotlin.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityConfigTest {
    private val validProperties =
        JwtSecurityProperties(
            issuer = "test-issuer",
            secret = "test-secret-minimum-32-chars-long",
            accessTokenTtlSeconds = 3600L,
        )
    private val config = SecurityConfig(validProperties)

    // --- passwordEncoder ---

    @Test
    fun `passwordEncoder encodes a raw password`() {
        val encoder = config.passwordEncoder()
        val raw = "myPassword123"

        val encoded = encoder.encode(raw)

        assertNotNull(encoded)
        assertTrue(encoder.matches(raw, encoded))
    }

    @Test
    fun `passwordEncoder rejects wrong password`() {
        val encoder = config.passwordEncoder()
        val encoded = encoder.encode("correct-password")

        assertFalse(encoder.matches("wrong-password", encoded))
    }

    // -------------------------------------------------- jwtEncoder --------------------------------------------------

    @Test
    fun `jwtEncoder builds successfully with secret of 32 bytes`() {
        val props =
            JwtSecurityProperties(
                issuer = "test",
                secret = "12345678901234567890123456789012", // exactly 32 bytes
                accessTokenTtlSeconds = 3600L,
            )
        assertNotNull(SecurityConfig(props).jwtEncoder())
    }

    @Test
    fun `jwtEncoder throws IllegalArgumentException when secret is shorter than 32 bytes`() {
        val shortSecretConfig =
            SecurityConfig(
                JwtSecurityProperties(issuer = "test", secret = "too-short", accessTokenTtlSeconds = 60L),
            )

        assertThrows(IllegalArgumentException::class.java) {
            shortSecretConfig.jwtEncoder()
        }
    }

    // --- reactiveJwtDecoder ---

    @Test
    fun `reactiveJwtDecoder builds successfully with valid secret`() {
        assertNotNull(config.reactiveJwtDecoder())
    }
}
