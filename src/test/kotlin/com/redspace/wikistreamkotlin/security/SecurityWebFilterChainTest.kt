package com.redspace.wikistreamkotlin.security

import com.redspace.wikistreamkotlin.WikistreamkotlinApplication
import com.redspace.wikistreamkotlin.repository.RevokedTokenCassandraRepository
import com.redspace.wikistreamkotlin.repository.UserAccountAtomicRepository
import com.redspace.wikistreamkotlin.repository.UserAccountCassandraRepository
import com.redspace.wikistreamkotlin.testsupport.NoOpStatsSnapshotCassandraRepositoryConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

/**
 * Integration test that verifies security filter-chain rules defined in [SecurityConfig]:
 * - public endpoints are accessible without authentication,
 * - protected endpoints require a valid Bearer JWT,
 * - an invalid/missing token is rejected with HTTP 401.
 *
 * The test application.properties excludes Cassandra autoconfiguration, and a test-only
 * no-op Cassandra stats repository keeps the full WebFlux context loadable without external services.
 */
@SpringBootTest(
    classes = [
        WikistreamkotlinApplication::class,
        NoOpStatsSnapshotCassandraRepositoryConfig::class,
        SecurityWebFilterChainTest.TestAuthRepositoryConfig::class,
    ],
    properties = ["app.auth.enabled=true"],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
class SecurityWebFilterChainTest {

    @TestConfiguration
    class TestAuthRepositoryConfig {
        @Bean
        fun userAccountRepository(): UserAccountCassandraRepository = mock()

        @Bean
        fun userAccountAtomicRepository(): UserAccountAtomicRepository = mock()

        @Bean
        fun revokedTokenRepository(): RevokedTokenCassandraRepository = mock()
    }


    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @Autowired
    private lateinit var revokedTokenRepository: RevokedTokenCassandraRepository

    @BeforeEach
    fun setUp() {
        whenever(revokedTokenRepository.findById(any())).thenReturn(Optional.empty())
    }

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

