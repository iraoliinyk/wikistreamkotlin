package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.exception.RepositoryReadError
import com.redspace.wikistreamkotlin.exception.RepositoryWriteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository

@Repository
@ConditionalOnProperty(name = ["app.stats.repository.type"], havingValue = "cassandra", matchIfMissing = true)
class CassandraStatsRepository(
    private val statsSnapshotCassandraRepository: StatsSnapshotCassandraRepository
) : StatsRepository {

    companion object {
        private const val MAX_SAVE_RETRIES = 3
    }

    override suspend fun recordForUser(userEmail: String, event: WikiEvent) {
        try {
            withContext(Dispatchers.IO) {
                var lastOptimisticLockingFailure: OptimisticLockingFailureException? = null

                repeat(MAX_SAVE_RETRIES) {
                    try {
                        val currentSnapshot = statsSnapshotCassandraRepository.findById(userEmail)
                            .orElse(StatsSnapshot(id = userEmail))
                            ?: StatsSnapshot(id = userEmail)

                        val updatedSnapshot = currentSnapshot.applyEvent(event)
                        statsSnapshotCassandraRepository.save(updatedSnapshot)
                        return@withContext
                    } catch (exception: OptimisticLockingFailureException) {
                        lastOptimisticLockingFailure = exception
                    }
                }

                throw RepositoryWriteError(
                    message = "Failed to record wiki event for user '$userEmail' in Cassandra after $MAX_SAVE_RETRIES retries",
                    cause = lastOptimisticLockingFailure
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: RepositoryWriteError) {
            throw error
        } catch (exception: Exception) {
            throw RepositoryWriteError(
                message = "Failed to record wiki event for user '$userEmail' in Cassandra",
                cause = exception
            )
        }
    }

    override suspend fun snapshotForUser(userEmail: String): StatsSnapshot {
        try {
            return withContext(Dispatchers.IO) {
                statsSnapshotCassandraRepository.findById(userEmail)
                    .orElse(StatsSnapshot(id = userEmail))
                    ?: StatsSnapshot(id = userEmail)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw RepositoryReadError(
                message = "Failed to fetch stats snapshot for user '$userEmail' from Cassandra",
                cause = exception
            )
        }
    }

    private fun StatsSnapshot.applyEvent(event: WikiEvent): StatsSnapshot {
        val normalizedUser = event.user?.takeIf { it.isNotBlank() }
        val updatedTrackedUsers = normalizedUser?.let { trackedUsers + it } ?: trackedUsers

        val normalizedServerUrl = event.serverUrl?.takeIf { it.isNotBlank() }
        val updatedCountByServerUrl = normalizedServerUrl?.let { serverUrl ->
            countByServerUrl + (serverUrl to ((countByServerUrl[serverUrl] ?: 0) + 1))
        } ?: countByServerUrl

        return copy(
            totalMessages = totalMessages + 1,
            distinctUsers = updatedTrackedUsers.size,
            botCount = botCount + if (event.bot == true) 1 else 0,
            nonBotCount = nonBotCount + if (event.bot == true) 0 else 1,
            countByServerUrl = updatedCountByServerUrl,
            trackedUsers = updatedTrackedUsers
        )
    }
}
