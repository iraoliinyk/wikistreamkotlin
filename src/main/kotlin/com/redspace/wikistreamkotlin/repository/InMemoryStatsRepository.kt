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

    private val lock = Mutex()
    private val snapshotsByUser = mutableMapOf<String, StatsSnapshot>()

    override suspend fun recordForUser(userEmail: String, event: WikiEvent) {
        try {
            lock.withLock {
                val current = snapshotsByUser[userEmail] ?: StatsSnapshot(id = userEmail)

                val normalizedUser = event.user?.takeIf { it.isNotBlank() }
                val updatedTrackedUsers = normalizedUser?.let { current.trackedUsers + it } ?: current.trackedUsers
                val normalizedServerUrl = event.serverUrl?.takeIf { it.isNotBlank() }
                val updatedCountByServerUrl = normalizedServerUrl?.let { serverUrl ->
                    current.countByServerUrl + (serverUrl to ((current.countByServerUrl[serverUrl] ?: 0) + 1))
                } ?: current.countByServerUrl

                snapshotsByUser[userEmail] = current.copy(
                    totalMessages = current.totalMessages + 1,
                    distinctUsers = updatedTrackedUsers.size,
                    botCount = current.botCount + if (event.bot == true) 1 else 0,
                    nonBotCount = current.nonBotCount + if (event.bot == true) 0 else 1,
                    countByServerUrl = updatedCountByServerUrl,
                    trackedUsers = updatedTrackedUsers
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RepositoryWriteError(
                message = "Failed to record wiki event in memory for user '$userEmail'",
                cause = exception
            )
        }
    }

    override suspend fun snapshotForUser(userEmail: String): StatsSnapshot {
        try {
            return lock.withLock {
                snapshotsByUser[userEmail] ?: StatsSnapshot(id = userEmail)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RepositoryReadError(
                message = "Failed to build stats snapshot from in-memory state for user '$userEmail'",
                cause = exception
            )
        }
    }

}