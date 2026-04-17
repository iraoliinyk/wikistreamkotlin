package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.exception.AppError
import com.redspace.wikistreamkotlin.exception.StatsRecordingError
import com.redspace.wikistreamkotlin.exception.StatsSnapshotError
import kotlinx.coroutines.CancellationException
import com.redspace.wikistreamkotlin.repository.StatsRepository
import org.springframework.stereotype.Service

@Service
class StatsService(private val statsRepository: StatsRepository) {
    suspend fun record(event: WikiEvent) {
        try {
            statsRepository.record(event)
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: AppError) {
            throw error
        } catch (exception: Exception) {
            throw StatsRecordingError(
                message = "Failed to record wiki event with id=${event.id}",
                cause = exception
            )
        }
    }

    suspend fun getSnapshot(): StatsSnapshot {
        try {
            return statsRepository.snapshot()
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: AppError) {
            throw error
        } catch (exception: Exception) {
            throw StatsSnapshotError(
                message = "Failed to fetch stats snapshot",
                cause = exception
            )
        }
    }
}