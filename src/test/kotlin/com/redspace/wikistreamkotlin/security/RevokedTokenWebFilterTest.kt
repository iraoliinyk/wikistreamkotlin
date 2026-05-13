package com.redspace.wikistreamkotlin.security

import com.redspace.wikistreamkotlin.domain.RevokedToken
import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant
import java.util.Optional

class RevokedTokenWebFilterTest {

    private val revokedTokenRepo: RevokedTokenCassandraRepository = mock()
    private val filter = RevokedTokenWebFilter(revokedTokenRepo)

    @Test
    fun `null repository skips revocation check and continues chain`() {
        val filterWithNullRepo = RevokedTokenWebFilter(null)
        val exchange: ServerWebExchange = mock()
        val chain = chainThatCompletes()

        StepVerifier.create(filterWithNullRepo.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `no authentication principal continues chain`() {
        val exchange: ServerWebExchange = mock()
        whenever(exchange.getPrincipal<JwtAuthenticationToken>()).thenReturn(Mono.empty())
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `blank jti in JWT continues chain without lookup`() {
        val jwt = buildJwt(id = "   ")
        val exchange = exchangeWithAuth(jwt)
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
        verify(revokedTokenRepo, never()).findById(any())
    }

    @Test
    fun `null jti in JWT continues chain without lookup`() {
        val jwt = buildJwt(id = null)
        val exchange = exchangeWithAuth(jwt)
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
        verify(revokedTokenRepo, never()).findById(any())
    }

    @Test
    fun `token not found in store continues chain`() {
        whenever(revokedTokenRepo.findById("jti-abc")).thenReturn(Optional.empty())
        val jwt = buildJwt(id = "jti-abc")
        val exchange = exchangeWithAuth(jwt)
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `active revoked token returns 401 and completes response`() {
        val futureExpiry = Instant.now().plusSeconds(3600)
        val revokedToken = RevokedToken(
            jti = "jti-revoked",
            email = "user@example.com",
            expiresAt = futureExpiry,
        )
        whenever(revokedTokenRepo.findById("jti-revoked")).thenReturn(Optional.of(revokedToken))

        val response: ServerHttpResponse = mock()
        whenever(response.setComplete()).thenReturn(Mono.empty())
        val jwt = buildJwt(id = "jti-revoked")
        val exchange = exchangeWithAuth(jwt, response)
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED)
        verify(chain, never()).filter(any())
    }

    @Test
    fun `expired revocation record is treated as not revoked and continues chain`() {
        val pastExpiry = Instant.now().minusSeconds(3600)
        val expiredRevocation = RevokedToken(
            jti = "jti-old",
            email = "user@example.com",
            expiresAt = pastExpiry,
        )
        whenever(revokedTokenRepo.findById("jti-old")).thenReturn(Optional.of(expiredRevocation))

        val jwt = buildJwt(id = "jti-old")
        val exchange = exchangeWithAuth(jwt)
        val chain = chainThatCompletes()

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        verify(chain).filter(exchange)
    }

    // -------------------------------------------------- helpers --------------------------------------------------

    private fun buildJwt(id: String?): Jwt {
        val builder = Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .subject("user@example.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
        if (id != null) builder.claim("jti", id)
        return builder.build()
    }

    private fun exchangeWithAuth(
        jwt: Jwt,
        response: ServerHttpResponse = stubResponse(),
    ): ServerWebExchange {
        val exchange: ServerWebExchange = mock()
        whenever(exchange.getPrincipal<JwtAuthenticationToken>())
            .thenReturn(Mono.just(JwtAuthenticationToken(jwt)))
        whenever(exchange.response).thenReturn(response)
        return exchange
    }

    private fun stubResponse(): ServerHttpResponse {
        val response: ServerHttpResponse = mock()
        whenever(response.setComplete()).thenReturn(Mono.empty())
        return response
    }

    private fun chainThatCompletes(): WebFilterChain {
        val chain: WebFilterChain = mock()
        whenever(chain.filter(any())).thenReturn(Mono.empty())
        return chain
    }
}

