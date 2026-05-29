package com.redspace.wikistreamkotlin.security

import com.redspace.wikistreamkotlin.controller.dto.TokenResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Creates and parses JWT access tokens for authenticated users.
 *
 * Main responsibilities:
 * - Build a signed HS256 access token using Spring Security's [JwtEncoder].
 * - Populate standard claims (`iss`, `sub`, `iat`, `exp`, `jti`) for identity and lifecycle.
 * - Add authorization scope (`scope=stats:read`) used by resource authorization rules.
 * - Return token metadata to API callers via [TokenResponse].
 *
 * Token flow in this service:
 * 1. `createAccessToken(email)` builds claims from application security properties.
 * 2. The claims are signed with the configured encoder and secret key.
 * 3. The resulting compact JWT string is returned to clients for Bearer authentication.
 * 4. `extractJti(jwt)` exposes JWT ID for logout/revocation checks.
 */
@Service
@ConditionalOnProperty(name = ["app.auth.enabled"], havingValue = "true", matchIfMissing = true)
class JwtTokenService(
    // Native Spring Security JWT encoder (typically NimbusJwtEncoder).
    private val jwtEncoder: JwtEncoder,
    // Application-specific JWT settings (issuer, TTL, secret-backed configuration).
    private val jwtSecurityProperties: JwtSecurityProperties,
) {
    fun createAccessToken(email: String): TokenResponse = generateAccessToken(email).response

    fun generateAccessToken(email: String): GeneratedAccessToken {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(jwtSecurityProperties.accessTokenTtlSeconds)
        val jti = UUID.randomUUID().toString()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(jwtSecurityProperties.issuer)
                .subject(email)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(jti)
                // `scope` is the standard OAuth2/JWT claim for permissions; `stats:read`
                // follows resource:action format (`stats` = protected API resource, `read` = operation)
                // and is mapped by Spring Security to authority `SCOPE_stats:read`.
                .claim("scope", "stats:read")
                .build()

        val headers = JwsHeader.with(MacAlgorithm.HS256).build()
        val jwt = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims))

        return GeneratedAccessToken(
            response =
                TokenResponse(
                    accessToken = jwt.tokenValue,
                    expiresIn = jwtSecurityProperties.accessTokenTtlSeconds,
                ),
            jti = jti,
        )
    }

    fun extractJti(jwt: Jwt): String? = jwt.id
}
