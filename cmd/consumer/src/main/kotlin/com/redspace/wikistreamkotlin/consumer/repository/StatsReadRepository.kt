package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.StatsView

/**
 * Read-side contract for assembling the stats view.
 * Intentionally separate from the write repository (CQRS).
 */
interface StatsReadRepository {
    /**
     * Assembles a StatsView for the given user and day from three Cassandra tables.
     * Returns an empty view if no data exists yet.
     */
    suspend fun getStatsView(userEmail: String, bucketDay: String): StatsView
}