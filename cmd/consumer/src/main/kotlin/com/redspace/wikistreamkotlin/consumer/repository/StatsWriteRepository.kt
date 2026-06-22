package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta

/**
 * Write-side contract for the stats persistence layer.
 * All operations are blind writes (no reads before writing).
 */
interface StatsWriteRepository {
    /**
     * Atomically increments counters for the user's daily bucket.
     * Cassandra COUNTER semantics guarantee no lost updates under concurrency.
     */
    suspend fun incrementCounters(userEmail: String, bucketDay: String, delta: UserStatsDelta)

    /**
     * Appends server URL hit events. Duplicate event_ids are impossible (timeuuid).
     */
    suspend fun appendServerUrlEvents(userEmail: String, bucketDay: String, serverUrls: List<String>)

    /**
     * Upserts distinct tracked users. Inserting an existing PK is a Cassandra no-op.
     */
    suspend fun upsertTrackedUsers(userEmail: String, bucketDay: String, trackedUsers: Set<String>)
}