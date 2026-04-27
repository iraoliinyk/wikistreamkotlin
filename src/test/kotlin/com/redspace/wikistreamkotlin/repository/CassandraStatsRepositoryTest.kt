package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

class CassandraStatsRepositoryTest {

    private val snapshotRepository = mock<StatsSnapshotCassandraRepository>()
    private val repository = CassandraStatsRepository(snapshotRepository)

    @Test
    fun `record persists updated snapshot to Cassandra`() = runBlocking {
        whenever(snapshotRepository.findById(StatsSnapshot.GLOBAL_ID)).thenReturn(Optional.empty())
        whenever(snapshotRepository.save(any<StatsSnapshot>())).thenAnswer { it.getArgument(0) }

        repository.record(
            WikiEventMockFactory.createWikiEvent(
                user = "alice",
                bot = false
            )
        )

        val snapshotCaptor = argumentCaptor<StatsSnapshot>()
        verify(snapshotRepository).save(snapshotCaptor.capture())

        val storedSnapshot = snapshotCaptor.firstValue
        assertEquals(StatsSnapshot.GLOBAL_ID, storedSnapshot.id)
        assertEquals(1, storedSnapshot.totalMessages)
        assertEquals(1, storedSnapshot.distinctUsers)
        assertEquals(0, storedSnapshot.botCount)
        assertEquals(1, storedSnapshot.nonBotCount)
        assertEquals(1, storedSnapshot.countByServerUrl["https://en.wikipedia.org"])
        assertEquals(setOf("alice"), storedSnapshot.trackedUsers)
    }

    @Test
    fun `snapshot returns empty aggregate when row is not yet stored`() = runBlocking {
        whenever(snapshotRepository.findById(StatsSnapshot.GLOBAL_ID)).thenReturn(Optional.empty())

        val snapshot = repository.snapshot()

        assertEquals(StatsSnapshot.GLOBAL_ID, snapshot.id)
        assertEquals(0, snapshot.totalMessages)
        assertEquals(0, snapshot.distinctUsers)
        assertEquals(0, snapshot.botCount)
        assertEquals(0, snapshot.nonBotCount)
        assertEquals(emptyMap<String, Int>(), snapshot.countByServerUrl)
    }
}

