package com.redspace.wikistreamkotlin.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant

class JwtTokenServiceTest {

    private val jwtEncoder: JwtEncoder = mock()
    private val jwtSecurityProperties = JwtSecurityProperties(
        issuer = "test-issuer",
        secret = "test-secret-minimum-32-chars-long",
        accessTokenTtlSeconds = 3600L,
    )
    private val jwtTokenService = JwtTokenService(jwtEncoder, jwtSecurityProperties)

    @Test
    fun `createAccessToken encodes claims with configured issuer and subject`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        jwtTokenService.createAccessToken("user@example.com")

        val claims = captor.firstValue.claims
        assertEquals("test-issuer", claims.claims["iss"])
        assertEquals("user@example.com", claims.claims["sub"])
    }

    @Test
    fun `createAccessToken includes stats read scope claim`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        jwtTokenService.createAccessToken("user@example.com")

        assertEquals("stats:read", captor.firstValue.claims.claims["scope"])
    }

    @Test
    fun `createAccessToken sets expiration based on configured TTL`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        val before = Instant.now()
        jwtTokenService.createAccessToken("user@example.com")
        val after = Instant.now()

        val expiresAt = captor.firstValue.claims.claims["exp"] as Instant
        assertTrue(expiresAt.isAfter(before.plusSeconds(3599)))
        assertTrue(expiresAt.isBefore(after.plusSeconds(3601)))
    }

    @Test
    fun `createAccessToken includes a non-null jti claim`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        jwtTokenService.createAccessToken("user@example.com")

        assertNotNull(captor.firstValue.claims.claims["jti"])
    }

    @Test
    fun `createAccessToken generates unique jti per successive calls`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        jwtTokenService.createAccessToken("user@example.com")
        jwtTokenService.createAccessToken("user@example.com")

        val jtis = captor.allValues.map { it.claims.claims["jti"] }
        assertNotEquals(jtis[0], jtis[1])
    }

    @Test
    fun `createAccessToken uses HS256 algorithm header`() {
        val captor = argumentCaptor<JwtEncoderParameters>()
        stubEncoder(captor = captor)

        jwtTokenService.createAccessToken("user@example.com")

        assertEquals(MacAlgorithm.HS256, captor.firstValue.jwsHeader?.algorithm)
    }

    @Test
    fun `createAccessToken returns token value and configured TTL in response`() {
        stubEncoder(tokenValue = "signed.token.value")

        val response = jwtTokenService.createAccessToken("user@example.com")

        assertEquals("signed.token.value", response.accessToken)
        assertEquals(3600L, response.expiresIn)
    }

    @Test
    fun `extractJti returns the JWT id claim`() {
        val jwt = buildJwt(id = "jti-uuid-42")

        assertEquals("jti-uuid-42", jwtTokenService.extractJti(jwt))
    }

    @Test
    fun `extractJti returns null for JWT without id claim`() {
        val jwt = buildJwt(id = null)

        assertNull(jwtTokenService.extractJti(jwt))
    }

    // -------------------------------------------------- helpers --------------------------------------------------

    private fun stubEncoder(
        tokenValue: String = "token",
        captor: KArgumentCaptor<JwtEncoderParameters>? = null,
    ): Jwt {
        val mockJwt: Jwt = mock()
        whenever(mockJwt.tokenValue).thenReturn(tokenValue)
        if (captor != null) {
            whenever(jwtEncoder.encode(captor.capture()))
                .thenReturn(mockJwt)
        } else {
            whenever(jwtEncoder.encode(any()))
                .thenReturn(mockJwt)
        }
        return mockJwt
    }

    private fun buildJwt(id: String?): Jwt {
        val builder = Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject("user@example.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
        if (id != null) builder.claim("jti", id)
        return builder.build()
    }
}
