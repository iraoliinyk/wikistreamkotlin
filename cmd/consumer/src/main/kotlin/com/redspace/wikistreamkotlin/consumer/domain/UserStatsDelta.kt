package com.redspace.wikistreamkotlin.consumer.domain

/**
 * Immutable result of aggregating a batch of WikiEvents for a single user.
 * Produced by BatchAggregationService; consumed by StatsWriteRepository.
 * Contains only deltas — never reads from the database.
 */
data class UserStatsDelta(
    val totalMessages: Long,
    val botCount: Long,
    val nonBotCount: Long,
    val serverUrls: List<String>,       // raw list; deduplicated in DB via timeuuid PK
    val trackedUsers: Set<String>        // deduplicated in memory before write
)