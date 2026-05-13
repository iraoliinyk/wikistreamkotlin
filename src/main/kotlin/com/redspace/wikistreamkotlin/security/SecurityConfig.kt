package com.redspace.wikistreamkotlin.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableWebFluxSecurity
@ConditionalOnProperty(name = ["app.auth.enabled"], havingValue = "true", matchIfMissing = true)
class SecurityConfig(
    private val jwtSecurityProperties: JwtSecurityProperties
) {

    @Bean
    /**
     * Provides the application password encoder.
     *
     * BCrypt is chosen as a strong default for backend authentication because it is:
     * - adaptive (work factor can be increased over time as hardware gets faster),
     * - salted by design (protects against rainbow-table attacks),
     * - widely supported and battle-tested in Spring Security ecosystems.
     *
     * Other encoders (e.g., PBKDF2, SCrypt, Argon2) are also valid in specific environments,
     * but BCrypt offers a pragmatic security/compatibility baseline for this service.
     */
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * Creates the JWT encoder used to sign access tokens.
     *
     * Uses an HMAC-SHA256 secret (`HS256`) sourced from application security properties.
     * A minimum secret length is enforced to reduce weak-key risk.
     */
    @Bean
    fun jwtEncoder(): JwtEncoder {
        val keyBytes = jwtSecurityProperties.secret.toByteArray(Charsets.UTF_8)
        require(keyBytes.size >= 32) { "JWT secret must be at least 32 bytes for HS256" }
        // Builds Hash-based Message Authentication Code (HMAC) key object for signing algorithm
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        // Returns Spring/Nimbus encoder that will sign JWTs with the shared secret
        return org.springframework.security.oauth2.jwt.NimbusJwtEncoder(ImmutableSecret(secretKey))
    }

    /**
     * Creates the reactive JWT decoder used by the resource server to validate Bearer tokens.
     *
     * The decoder verifies HS256 signatures with the shared secret and enforces issuer (`iss`)
     * validation so only tokens from this service issuer are accepted.
     */
    @Bean
    fun reactiveJwtDecoder(): ReactiveJwtDecoder {
        //Uses same shared secret as encoder
        val keyBytes = jwtSecurityProperties.secret.toByteArray(Charsets.UTF_8)
        // Rebuilds HMAC key for verification
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        // Configures decoder to verify HS256-signed tokens reactively (WebFlux compatible)
        return NimbusReactiveJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply {
                // Creates validator that requires iss claim to match configured issuer
                val withIssuer = JwtValidators.createDefaultWithIssuer(jwtSecurityProperties.issuer)
                // Activates issuer validation (plus default JWT validations through default issuer validator)
                setJwtValidator(DelegatingOAuth2TokenValidator(withIssuer))
            }
    }


    /**
     * Configures the WebFlux security filter chain for API authentication and authorization.
     *
     * Main behavior:
     * - disables stateful/default browser auth features (CSRF, Basic, form login, default logout),
     * - permits public auth/bootstrap endpoints,
     * - requires authentication for all remaining routes,
     * - enables JWT Bearer token resource-server mode,
     * - executes [RevokedTokenWebFilter] after JWT authentication to enforce token revocation.
     */
    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        revokedTokenWebFilter: RevokedTokenWebFilter
    ): SecurityWebFilterChain {
        val jwtAuthConverter = JwtAuthenticationConverter()
        // Converter from JWT claims to Spring Authentication authorities/principal.
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            // Routes authorization rules
            .authorizeExchange {
                // Public GET endpoints (health + email check).
                it.pathMatchers(HttpMethod.GET, "/v1/status").permitAll()
                // Public register/login endpoints
                it.pathMatchers(HttpMethod.POST, "/v1/auth/register", "/v1/auth/login").permitAll()
                // Everything else requires valid authentication
                it.anyExchange().authenticated()
            }
            // Enables Bearer token resource-server mode
            .oauth2ResourceServer { oauth2ResourceServerCustomizer ->
                oauth2ResourceServerCustomizer.jwt {
                    // Adapts non-reactive converter for WebFlux pipeline
                    it.jwtAuthenticationConverter(ReactiveJwtAuthenticationConverterAdapter(jwtAuthConverter))
                }
            }
            // Runs RevokedTokenWebFilter after JWT authentication, so token is already parsed/validated and principal exists
            .addFilterAfter(revokedTokenWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }
}

