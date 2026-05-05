package com.redspace.wikistreamkotlin.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Integration test that verifies security filter-chain rules defined in [SecurityConfig]:
 * - public endpoints are accessible without authentication,
 * - protected endpoints require a valid Bearer JWT,
 * - an invalid/missing token is rejected with HTTP 401.
 *
 * The test application.properties excludes Cassandra autoconfiguration and uses the
 * in-memory stats repository, so the full WebFlux context loads without external services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SecurityWebFilterChainTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @Test
    fun `GET status endpoint is public and returns 200`() {
        webTestClient.get()
            .uri("/v1/status")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `GET stats without Authorization returns 401`() {
        webTestClient.get()
            .uri("/v1/stats")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GET stats with invalid Bearer token returns 401`() {
        webTestClient.get()
            .uri("/v1/stats")
            .header("Authorization", "Bearer completely.invalid.token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GET stats with valid Bearer token returns 200`() {
        val token = jwtTokenService.createAccessToken("test@example.com").accessToken

        webTestClient.get()
            .uri("/v1/stats")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
    }
}

