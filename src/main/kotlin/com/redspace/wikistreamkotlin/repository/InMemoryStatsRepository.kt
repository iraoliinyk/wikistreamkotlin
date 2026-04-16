package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogging
import org.springframework.stereotype.Repository

@Repository
class InMemoryStatsRepository : StatsRepository {

    companion object : KLogging() {
        private const val SNAPSHOT_ID_PREFIX = "snapshot-"
    }

    private val lock = Mutex()
    private var totalMessages: Int = 0
    private var botCount: Int = 0
    private var nonBotCount: Int = 0
    private val distinctUsers = mutableSetOf<String>()
    private val serverUrlCounts = mutableMapOf<String, Int>()

    override suspend fun record(event: WikiEvent) {
        lock.withLock {
            totalMessages += 1
            event.user?.let { distinctUsers.add(it) }
            if (event.bot == true) {
                botCount += 1
            } else {
                nonBotCount += 1
            }
            event.serverUrl?.let { serverUrl ->
                serverUrlCounts[serverUrl] = (serverUrlCounts[serverUrl] ?: 0) + 1
            }
        }
    }

    override suspend fun snapshot(): StatsSnapshot {
        return lock.withLock {
            StatsSnapshot(
                id = "$SNAPSHOT_ID_PREFIX${System.currentTimeMillis()}",
                totalMessages = totalMessages,
                distinctUsers = distinctUsers.size,
                botCount = botCount,
                nonBotCount = nonBotCount,
                countByServerUrl = serverUrlCounts.toMap()
            )
        }
    }

}