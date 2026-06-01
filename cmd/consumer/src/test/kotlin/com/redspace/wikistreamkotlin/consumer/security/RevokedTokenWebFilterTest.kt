package com.redspace.wikistreamkotlin.consumer.security

import com.redspace.wikistreamkotlin.consumer.domain.RevokedToken
import com.redspace.wikistreamkotlin.consumer.repository.RevokedTokenCassandraRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebExchangeDecorator
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.security.Principal
import java.time.Instant
import java.util.Optional

class RevokedTokenWebFilterTest {
    private val repository = Mockito.mock(RevokedTokenCassandraRepository::class.java)
    private val filter = RevokedTokenWebFilter(repository)

    @Test
    fun `blocks request when token jti is revoked`() {
        Mockito.`when`(repository.findById("revoked-jti"))
            .thenReturn(
                Optional.of(
                    RevokedToken(
                        jti = "revoked-jti",
                        email = "alice@example.com",
                        expiresAt = Instant.now().plusSeconds(300),
                    ),
                ),
            )

        val chain = RecordingWebFilterChain()
        val exchange = exchangeWithPrincipal(jwtAuthentication(jti = "revoked-jti"))

        filter.filter(exchange, chain).block()

        assertFalse(chain.called)
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
    }

    @Test
    fun `allows request when token is not revoked`() {
        Mockito.`when`(repository.findById("active-jti")).thenReturn(Optional.empty())

        val chain = RecordingWebFilterChain()
        val exchange = exchangeWithPrincipal(jwtAuthentication(jti = "active-jti"))

        filter.filter(exchange, chain).block()

        assertTrue(chain.called)
    }

    @Test
    fun `bypasses when authentication principal is not JWT`() {
        val chain = RecordingWebFilterChain()
        val exchange = exchangeWithPrincipal(SimplePrincipal("plain-principal"))

        filter.filter(exchange, chain).block()

        assertTrue(chain.called)
    }

    private fun exchangeWithPrincipal(principal: Principal): ServerWebExchange =
        object : ServerWebExchangeDecorator(MockServerWebExchange.from(MockServerHttpRequest.get("/v1/stats").build())) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Principal> getPrincipal(): Mono<T> =
                when (principal) {
                    is JwtAuthenticationToken -> Mono.just(principal as T)
                    else -> Mono.empty()
                }
        }

    private fun jwtAuthentication(jti: String): JwtAuthenticationToken {
        val jwt =
            Jwt(
                "token-$jti",
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(60),
                mapOf("alg" to "HS256"),
                mapOf("sub" to "alice@example.com", "jti" to jti),
            )
        return JwtAuthenticationToken(jwt)
    }

    private class RecordingWebFilterChain : WebFilterChain {
        var called = false

        override fun filter(exchange: ServerWebExchange): Mono<Void> =
            Mono.empty<Void>().doFirst { called = true }
    }

    private class SimplePrincipal(
        private val name: String,
    ) : Principal {
        override fun getName(): String = name
    }
}
