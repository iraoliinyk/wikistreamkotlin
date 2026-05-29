package com.redspace.wikistreamkotlin.consumer.testsupport

import com.redspace.wikistreamkotlin.consumer.repository.StatsSnapshotCassandraRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.mockito.Mockito

/**
 * Test configuration that provides a no-op mock for StatsSnapshotCassandraRepository.
 * Used in integration tests that want to test session management without Cassandra.
 */
@TestConfiguration
class NoOpStatsSnapshotCassandraRepositoryConfig {
    @Bean
    @Primary
    fun noOpStatsSnapshotCassandraRepository(): StatsSnapshotCassandraRepository {
        return Mockito.mock(StatsSnapshotCassandraRepository::class.java)
    }
}

