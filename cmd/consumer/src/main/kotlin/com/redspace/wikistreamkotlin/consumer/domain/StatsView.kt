package com.redspace.wikistreamkotlin.consumer.domain

/**
 * Read-side projection assembled from three Cassandra tables.
 * Never written to Cassandra directly — always assembled on demand.
 */
data class StatsView(
    val userEmail: String,
    val bucketDay: String,
    val totalMessages: Long = 0,
    val distinctUsers: Int = 0,
    val botCount: Long = 0,
    val nonBotCount: Long = 0,
    val countByServerUrl: Map<String, Long> = emptyMap()
) {
    companion object {
        fun empty(userEmail: String, bucketDay: String) =
            StatsView(userEmail = userEmail, bucketDay = bucketDay)
    }
}