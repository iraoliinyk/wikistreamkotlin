package com.redspace.wikistreamkotlin.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.redspace.wikistreamkotlin.WikistreamkotlinApplication
import com.redspace.wikistreamkotlin.domain.UserAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.time.Instant
import java.util.concurrent.CountDownLatch

@SpringBootTest(
    classes = [WikistreamkotlinApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
    ],
)
@Testcontainers
class UserAccountAtomicRepositoryIntegrationTest {
    companion object {
        @Container
        @JvmField
        val cassandra: CassandraContainer =
            CassandraContainer("cassandra:5.0")
                .withStartupTimeout(Duration.ofMinutes(3))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            CqlSession
                .builder()
                .addContactPoint(cassandra.contactPoint)
                .withLocalDatacenter(cassandra.localDatacenter)
                .build()
                .use { session ->
                    session.execute(
                        "CREATE KEYSPACE IF NOT EXISTS wikistream " +
                            "WITH replication = {'class':'SimpleStrategy','replication_factor':1}",
                    )
                    session.execute(
                        "CREATE TABLE IF NOT EXISTS wikistream.user_accounts (" +
                            "email text PRIMARY KEY," +
                            "password_hash text," +
                            "created_at timestamp," +
                            "updated_at timestamp," +
                            "active boolean)",
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
    private lateinit var userAccountRepository: UserAccountCassandraRepository

    @Autowired
    private lateinit var userAccountAtomicRepository: UserAccountAtomicRepository

    @BeforeEach
    fun clean() {
        userAccountRepository.deleteAll()
    }

    @Test
    fun `insertIfNotExists returns true then false for duplicate email`() {
        val now = Instant.now()
        val first =
            userAccountAtomicRepository.insertIfNotExists(
                UserAccount("race@example.com", "hash-1", now, now, true),
            )
        val second =
            userAccountAtomicRepository.insertIfNotExists(
                UserAccount("race@example.com", "hash-2", now, now, true),
            )

        assertTrue(first)
        assertEquals(false, second)
        assertEquals(1L, userAccountRepository.count())
        assertEquals("hash-1", userAccountRepository.findById("race@example.com").get().passwordHash)
    }

    @Test
    fun `concurrent insertIfNotExists yields exactly one applied`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val attempts =
                (1..2).map { idx ->
                    async(Dispatchers.IO) {
                        gate.await()
                        val now = Instant.now()
                        userAccountAtomicRepository.insertIfNotExists(
                            UserAccount("race@example.com", "hash-$idx", now, now, true),
                        )
                    }
                }

            gate.countDown()
            val results = coroutineScope { attempts.awaitAll() }

            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
            assertEquals(1L, userAccountRepository.count())
        }
}
