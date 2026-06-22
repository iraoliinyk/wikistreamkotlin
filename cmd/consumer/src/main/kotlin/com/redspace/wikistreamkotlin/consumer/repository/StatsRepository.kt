package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.core.domain.WikiEvent

/**
 * User-centric repository interface.
 * Maintains backward compatibility while using time-series schema internally.
 */

@Deprecated("Mixed-concern interface replaced by ISP-compliant split ")
interface StatsRepository {
    /**
     * Record a WikiEvent for a specific user.
     * This is the PRIMARY method used by StatsService.
     * Internally handles time-bucketing and time-series writes.
     */
    suspend fun recordForUser(userEmail: String, event: WikiEvent)

    /**
     * Record multiple events for a user as a batch (high-performance).
     * Used by BatchPersistenceService for bulk writes.
     * More efficient than calling recordForUser multiple times.
     */
    suspend fun recordBatchForUser(userEmail: String, events: List<WikiEvent>, batchId: String)

    /**
     * Get the current/latest stats snapshot for a user.
     * This is used by controllers to display user stats.
     * Internally queries the latest bucket automatically.
     */
    suspend fun snapshotForUser(userEmail: String): StatsSnapshot

    /**
     * Get historical snapshots for a date range.
     * This is for analytics/reporting features.
     */
    suspend fun getSnapshotsForDateRange(userEmail: String, startDay: String, endDay: String): List<StatsSnapshot>
}