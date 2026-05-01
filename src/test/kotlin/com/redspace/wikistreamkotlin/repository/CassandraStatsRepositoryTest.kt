package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class CassandraStatsRepositoryTest {

    private val snapshotRepository = mock(StatsSnapshotCassandraRepository::class.java)
    private val repository = CassandraStatsRepository(snapshotRepository)

    @Test
    fun `record persists updated snapshot to Cassandra`() = runBlocking {
        `when`(snapshotRepository.findById("user@example.com")).thenReturn(Optional.empty())
        `when`(snapshotRepository.save(any(StatsSnapshot::class.java))).thenAnswer { it.getArgument(0) }

        repository.recordForUser(
            "user@example.com",
            WikiEventMockFactory.createWikiEvent(
                user = "alice",
                bot = false
            )
        )

        val snapshotCaptor = ArgumentCaptor.forClass(StatsSnapshot::class.java)
        verify(snapshotRepository).save(snapshotCaptor.capture())

        val storedSnapshot = snapshotCaptor.value
        assertEquals("user@example.com", storedSnapshot.id)
        assertEquals(1, storedSnapshot.totalMessages)
        assertEquals(1, storedSnapshot.distinctUsers)
        assertEquals(0, storedSnapshot.botCount)
        assertEquals(1, storedSnapshot.nonBotCount)
        assertEquals(1, storedSnapshot.countByServerUrl["https://en.wikipedia.org"])
        assertEquals(setOf("alice"), storedSnapshot.trackedUsers)
    }

    @Test
    fun `snapshot returns empty aggregate when row is not yet stored`() = runBlocking {
        `when`(snapshotRepository.findById("user@example.com")).thenReturn(Optional.empty())

        val snapshot = repository.snapshotForUser("user@example.com")

        assertEquals("user@example.com", snapshot.id)
        assertEquals(0, snapshot.totalMessages)
        assertEquals(0, snapshot.distinctUsers)
        assertEquals(0, snapshot.botCount)
        assertEquals(0, snapshot.nonBotCount)
        assertEquals(emptyMap<String, Int>(), snapshot.countByServerUrl)
    }
}
