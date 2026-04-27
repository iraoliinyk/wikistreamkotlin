package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.exception.RepositoryReadError
import com.redspace.wikistreamkotlin.exception.RepositoryWriteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository

@Repository
@ConditionalOnProperty(name = ["app.stats.repository.type"], havingValue = "in-memory")
class InMemoryStatsRepository : StatsRepository {

    companion object {
        private const val SNAPSHOT_ID_PREFIX = "snapshot-"
    }

    private val lock = Mutex()
    private var totalMessages: Int = 0
    private var botCount: Int = 0
    private var nonBotCount: Int = 0
    private val distinctUsers = mutableSetOf<String>()
    private val serverUrlCounts = mutableMapOf<String, Int>()

    override suspend fun record(event: WikiEvent) {
        try {
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RepositoryWriteError(
                message = "Failed to record wiki event in memory",
                cause = exception
            )
        }
    }

    override suspend fun snapshot(): StatsSnapshot {
        try {
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
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RepositoryReadError(
                message = "Failed to build stats snapshot from in-memory state",
                cause = exception
            )
        }
    }

}