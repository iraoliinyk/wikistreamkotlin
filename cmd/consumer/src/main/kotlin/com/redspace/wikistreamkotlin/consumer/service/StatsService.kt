package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.AppError
import com.redspace.wikistreamkotlin.core.exception.StatsRecordingError
import com.redspace.wikistreamkotlin.core.exception.StatsSnapshotError
import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.consumer.repository.StatsRepository
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service

@Service
class StatsService(
    private val statsRepository: StatsRepository,
    private val activeUserSessionService: ActiveUserSessionService,
) {
    suspend fun recordForActiveUsers(event: WikiEvent) {
        try {
            val activeUsers = activeUserSessionService.listActiveUsers()
            for (email in activeUsers) {
                statsRepository.recordForUser(email, event)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: AppError) {
            throw error
        } catch (exception: Exception) {
            throw StatsRecordingError(
                message = "Failed to record wiki event with id=${event.id} for active users",
                cause = exception,
            )
        }
    }

    suspend fun getSnapshotForUser(userEmail: String): StatsSnapshot {
        try {
            return statsRepository.snapshotForUser(userEmail)
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: AppError) {
            throw error
        } catch (exception: Exception) {
            throw StatsSnapshotError(
                message = "Failed to fetch stats snapshot for user '$userEmail'",
                cause = exception,
            )
        }
    }
}
