package com.redspace.wikistreamkotlin.security

import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Instant

@Component
/**
 * Enforces JWT revocation in the WebFlux filter chain.
 *
 * Spring Security validates JWT signature and expiration, but a valid token can still be reused
 * after logout until it expires. This filter adds a server-side revocation check by reading the
 * token `jti` (JWT ID) and verifying whether it exists in `revoked_tokens` storage.
 *
 * Flow:
 * - Skip revocation checks when the repository is unavailable.
 * - Resolve `JwtAuthenticationToken` principal from the request.
 * - Read `jti`; if missing, continue request (cannot evaluate revocation).
 * - Lookup revocation record on `Schedulers.boundedElastic()` to avoid blocking the event loop.
 * - Return `401 Unauthorized` when token is revoked and revocation is still active.
 * - Otherwise continue the filter chain.
 */
class RevokedTokenWebFilter(
    private val revokedTokenCassandraRepository: RevokedTokenCassandraRepository?
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (revokedTokenCassandraRepository == null) {
            return chain.filter(exchange)
        }

        return exchange.getPrincipal<JwtAuthenticationToken>()
            .flatMap { authentication ->
                val jti = authentication.token.id
                // if token has no jti, filter cannot check revocation, so it allows request
                if (jti.isNullOrBlank()) {
                    return@flatMap chain.filter(exchange)
                }
                Mono.fromCallable {
                    revokedTokenCassandraRepository.findById(jti).orElse(null)
                }
                    // Moves blocking call off event-loop thread to avoid WebFlux thread starvation.
                    // Thread starvation is a concurrency issue where a thread is perpetually
                    // denied necessary resources (like CPU time or locks) because "greedy" threads
                    // consume them, preventing the starved thread from making progress
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap { revokedToken ->
                        if (revokedToken.expiresAt.isAfter(Instant.now())) {
                            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                            exchange.response.setComplete()
                        } else {
                            chain.filter(exchange)
                        }
                    }
            }
            .switchIfEmpty(chain.filter(exchange))
    }
}

