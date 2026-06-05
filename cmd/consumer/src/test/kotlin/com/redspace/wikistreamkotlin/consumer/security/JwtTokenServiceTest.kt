package com.redspace.wikistreamkotlin.consumer.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Instant

class JwtTokenServiceTest {
    private val jwtEncoder = CapturingJwtEncoder()
    private val jwtSecurityProperties =
        JwtSecurityProperties(
            issuer = "wikistream-test",
            secret = "12345678901234567890123456789012",
            accessTokenTtlSeconds = 3600L,
        )
    private val service = JwtTokenService(jwtEncoder, jwtSecurityProperties)

    @Test
    fun `generated token includes expected claims`() {
        val generated = service.generateAccessToken("alice@example.com")

        assertTrue(generated.response.accessToken.startsWith("encoded-token-"))
        assertEquals(3600L, generated.response.expiresIn)
        assertNotNull(generated.jti)

        val claims = requireNotNull(jwtEncoder.lastClaims)
        assertEquals("wikistream-test", claims.claims["iss"])
        assertEquals("alice@example.com", claims.subject)
        assertEquals("stats:read", claims.getClaim<String>("scope"))
        assertEquals(generated.jti, claims.id)
        assertTrue((claims.expiresAt?.epochSecond ?: 0L) > (claims.issuedAt?.epochSecond ?: 0L))
    }

    @Test
    fun `extractJti returns null when claim is absent`() {
        val jwt =
            Jwt(
                "token",
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(60),
                mapOf("alg" to "HS256"),
                mapOf("sub" to "alice@example.com"),
            )

        assertNull(service.extractJti(jwt))
    }

    private class CapturingJwtEncoder : JwtEncoder {
        var lastClaims: JwtClaimsSet? = null

        override fun encode(parameters: JwtEncoderParameters): Jwt {
            lastClaims = parameters.claims
            val claimsSet = parameters.claims
            val jti = claimsSet.id ?: "generated-jti"
            val claims = mutableMapOf<String, Any>()
            claims["jti"] = jti
            claims["sub"] = claimsSet.subject
            return Jwt(
                "encoded-token-$jti",
                claimsSet.issuedAt ?: Instant.now(),
                claimsSet.expiresAt ?: Instant.now().plusSeconds(60),
                mapOf("alg" to MacAlgorithm.HS256.name),
                claims,
            )
        }
    }
}
