package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.consumer.domain.StatsView
import com.redspace.wikistreamkotlin.consumer.repository.StatsReadRepository
import com.redspace.wikistreamkotlin.core.exception.AppError
import com.redspace.wikistreamkotlin.core.exception.StatsSnapshotError
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read-side service for assembling a user's stats view.
 *
 * Write path is handled directly by [com.redspace.wikistreamkotlin.consumer.CoroutineBatchConsumer]
 * via [com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService] and the write repository.
 */
@Service
class StatsService(
    private val statsReadRepository: StatsReadRepository,
) {
    suspend fun getSnapshotForUser(userEmail: String): StatsView {
        val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()
        try {
            return statsReadRepository.getStatsView(userEmail, bucketDay)
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: AppError) {
            throw error
        } catch (exception: Exception) {
            throw StatsSnapshotError(
                message = "Failed to fetch stats view for user '$userEmail'",
                cause = exception,
            )
        }
    }
}
