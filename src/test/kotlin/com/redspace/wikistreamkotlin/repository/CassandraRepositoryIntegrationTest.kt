package com.redspace.wikistreamkotlin.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.redspace.wikistreamkotlin.domain.RevokedToken
import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.UserAccount
import com.redspace.wikistreamkotlin.repository.CassandraRepositoryIntegrationTest.Companion.properties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.cassandra.CassandraContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant

/**
 * Integration tests verifying that the Spring Data Cassandra repositories correctly
 * read, write, and enforce optimistic-locking against a live Cassandra node managed
 * by Testcontainers.
 *
 * The keyspace is created programmatically in [properties] (before the Spring context
 * initializes) so that `schema-action=CREATE_IF_NOT_EXISTS` can then create the tables
 * from the entity `@Table` annotations.
 */
@SpringBootTest(
    classes = [CassandraRepositoryIntegrationTest.TestCassandraConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=",
        "spring.main.web-application-type=none",
    ]
)
@Testcontainers
class CassandraRepositoryIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [UserAccount::class, RevokedToken::class, StatsSnapshot::class])
    @EnableCassandraRepositories(
        basePackageClasses = [
            UserAccountCassandraRepository::class,
            RevokedTokenCassandraRepository::class,
            StatsSnapshotCassandraRepository::class,
        ]
    )
    class TestCassandraConfig

    companion object {
        @Container
        @JvmField
        val cassandra: CassandraContainer = CassandraContainer("cassandra:5.0")
            .withStartupTimeout(Duration.ofMinutes(3))

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            // Create the keyspace using a raw CQL session BEFORE the Spring context
            // initializes, so that Spring Data Cassandra can connect with the keyspace name.
            CqlSession.builder()
                .addContactPoint(cassandra.contactPoint)
                .withLocalDatacenter(cassandra.localDatacenter)
                .build().use { session ->
                    session.execute(
                        "CREATE KEYSPACE IF NOT EXISTS wikistream " +
                                "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
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
    private lateinit var revokedTokenRepository: RevokedTokenCassandraRepository

    @Autowired
    private lateinit var statsSnapshotRepository: StatsSnapshotCassandraRepository

    @BeforeEach
    fun cleanTables() {
        userAccountRepository.deleteAll()
        revokedTokenRepository.deleteAll()
        statsSnapshotRepository.deleteAll()
    }

    // -------------------------------------------------- UserAccountCassandraRepository --------------------------------------------------
    @Test
    fun `save and findById returns persisted UserAccount`() {
        val now = Instant.now()
        val account = UserAccount(
            email = "alice@example.com",
            passwordHash = "hashed-pw",
            createdAt = now,
            updatedAt = now,
            active = true,
        )

        userAccountRepository.save(account)

        val found = userAccountRepository.findById("alice@example.com")
        assertTrue(found.isPresent)
        assertEquals("alice@example.com", found.get().email)
        assertEquals("hashed-pw", found.get().passwordHash)
        assertTrue(found.get().active)
    }

    @Test
    fun `existsById returns true for persisted account`() {
        userAccountRepository.save(UserAccount(email = "bob@example.com", passwordHash = "hash"))

        assertTrue(userAccountRepository.existsById("bob@example.com"))
    }

    @Test
    fun `existsById returns false for non-existent account`() {
        assertFalse(userAccountRepository.existsById("ghost@example.com"))
    }

    @Test
    fun `delete removes persisted account`() {
        val account = UserAccount(email = "carol@example.com", passwordHash = "hash")
        userAccountRepository.save(account)

        userAccountRepository.deleteById("carol@example.com")

        assertFalse(userAccountRepository.existsById("carol@example.com"))
    }

    // -------------------------------------------------- RevokedTokenCassandraRepository --------------------------------------------------

    @Test
    fun `save and findById returns persisted RevokedToken`() {
        val expiry = Instant.now().plusSeconds(3600)
        val token = RevokedToken(
            jti = "jti-test-1",
            email = "user@example.com",
            expiresAt = expiry,
            revokedAt = Instant.now(),
        )

        revokedTokenRepository.save(token)

        val found = revokedTokenRepository.findById("jti-test-1")
        assertTrue(found.isPresent)
        assertEquals("jti-test-1", found.get().jti)
        assertEquals("user@example.com", found.get().email)
    }

    @Test
    fun `findById returns empty for non-existent revoked token`() {
        val result = revokedTokenRepository.findById("non-existent-jti")

        assertFalse(result.isPresent)
    }

    @Test
    fun `saveWithTtl expires revoked token automatically`() {
        val token = RevokedToken(
            jti = "jti-expiring-1",
            email = "user@example.com",
            expiresAt = Instant.now().plusSeconds(3600),
            revokedAt = Instant.now(),
        )

        revokedTokenRepository.saveWithTtl(token, 1)
        assertTrue(revokedTokenRepository.findById(token.jti).isPresent)

        Thread.sleep(1500)
        assertFalse(revokedTokenRepository.findById(token.jti).isPresent)
    }

    // -------------------------------------------------- StatsSnapshotCassandraRepository --------------------------------------------------

    @Test
    fun `save and findById returns persisted StatsSnapshot`() {
        val snapshot = StatsSnapshot(
            id = "user@example.com",
            totalMessages = 5,
            distinctUsers = 2,
            botCount = 1,
            nonBotCount = 4,
            countByServerUrl = mapOf("https://en.wikipedia.org" to 5),
            trackedUsers = setOf("alice", "bob"),
        )

        statsSnapshotRepository.save(snapshot)

        val found = statsSnapshotRepository.findById("user@example.com")
        assertTrue(found.isPresent)
        with(found.get()) {
            assertEquals(5, totalMessages)
            assertEquals(2, distinctUsers)
            assertEquals(1, botCount)
            assertEquals(4, nonBotCount)
            assertEquals(5, countByServerUrl["https://en.wikipedia.org"])
            assertEquals(setOf("alice", "bob"), trackedUsers)
        }
    }

    @Test
    fun `findById returns empty snapshot for non-existent user`() {
        val result = statsSnapshotRepository.findById("nobody@example.com")

        assertFalse(result.isPresent)
    }

    @Test
    fun `concurrent saves with stale version raise OptimisticLockingFailureException`() {
        // Persist initial snapshot; Spring Data Cassandra assigns version = 0.
        statsSnapshotRepository.save(StatsSnapshot(id = "opt-lock-test"))

        // Read same row twice – both copies carry version = 0.
        val copy1 = statsSnapshotRepository.findById("opt-lock-test").get()
        val copy2 = statsSnapshotRepository.findById("opt-lock-test").get()

        assertNotNull(copy1.version)

        // First save increments the row's version to 1.
        statsSnapshotRepository.save(copy1.copy(totalMessages = 1))

        // Second save still carries version = 0, which no longer matches the row.
        assertThrows(OptimisticLockingFailureException::class.java) {
            statsSnapshotRepository.save(copy2.copy(totalMessages = 2))
        }
    }
}

