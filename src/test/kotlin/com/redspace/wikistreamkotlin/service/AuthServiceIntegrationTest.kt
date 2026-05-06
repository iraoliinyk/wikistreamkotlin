package com.redspace.wikistreamkotlin.service

import com.datastax.oss.driver.api.core.CqlSession
import com.redspace.wikistreamkotlin.controller.dto.RegisterRequest
import com.redspace.wikistreamkotlin.exception.UserAlreadyExistsError
import com.redspace.wikistreamkotlin.repository.UserAccountCassandraRepository
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.concurrent.CountDownLatch

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
        "app.auth.enabled=true",
    ]
)
@Testcontainers
class AuthServiceIntegrationTest {

    companion object {
        @Container
        @JvmField
        val cassandra: CassandraContainer = CassandraContainer("cassandra:5.0")
            .withStartupTimeout(Duration.ofMinutes(3))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            CqlSession.builder()
                .addContactPoint(cassandra.contactPoint)
                .withLocalDatacenter(cassandra.localDatacenter)
                .build().use { session ->
                    session.execute(
                        "CREATE KEYSPACE IF NOT EXISTS wikistream " +
                                "WITH replication = {'class':'SimpleStrategy','replication_factor':1}"
                    )
                }

            registry.add("spring.cassandra.contact-points") { cassandra.contactPoint.hostString }
            registry.add("spring.cassandra.port") { cassandra.contactPoint.port }
            registry.add("spring.cassandra.keyspace-name") { "wikistream" }
            registry.add("spring.cassandra.local-datacenter") { cassandra.localDatacenter }
            registry.add("spring.cassandra.schema-action") { "CREATE_IF_NOT_EXISTS" }
            registry.add("spring.main.web-application-type") { "none" }
        }
    }

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userAccountRepository: UserAccountCassandraRepository

    @BeforeEach
    fun clean() {
        userAccountRepository.deleteAll()
    }

    @Test
    fun `concurrent register same email yields one success and one duplicate`() = runBlocking {
        val gate = CountDownLatch(1)
        val request = RegisterRequest("Race@Example.com", "password123")

        val attempts = (1..2).map {
            async(Dispatchers.IO) {
                gate.await()
                runCatching { authService.register(request) }
            }
        }

        gate.countDown()
        val results = coroutineScope { attempts.awaitAll() }

        val successCount = results.count { it.isSuccess }
        val duplicateCount = results.count { it.exceptionOrNull() is UserAlreadyExistsError }

        assertEquals(1, successCount)
        assertEquals(1, duplicateCount)
        assertEquals(1L, userAccountRepository.count())
        assertEquals(true, userAccountRepository.existsById("race@example.com"))
    }
}