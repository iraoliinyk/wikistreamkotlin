package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.StatsView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate
import org.springframework.stereotype.Repository

private val logger = KotlinLogging.logger {}

@Repository
class CassandraStatsReadRepository(
    private val cassandraTemplate: ReactiveCassandraTemplate
) : StatsReadRepository {

    override suspend fun getStatsView(userEmail: String, bucketDay: String): StatsView =
        coroutineScope {
            // Fan-out: query all three tables concurrently
            val countersDeferred = async { fetchCounters(userEmail, bucketDay) }
            val serverUrlsDeferred = async { fetchServerUrlCounts(userEmail, bucketDay) }
            val distinctUsersDeferred = async { fetchDistinctUserCount(userEmail, bucketDay) }

            val counters = countersDeferred.await()
            val serverUrls = serverUrlsDeferred.await()
            val distinctUsers = distinctUsersDeferred.await()

            StatsView(
                userEmail = userEmail,
                bucketDay = bucketDay,
                totalMessages = counters?.totalMessages ?: 0,
                botCount = counters?.botCount ?: 0,
                nonBotCount = counters?.nonBotCount ?: 0,
                countByServerUrl = serverUrls,
                distinctUsers = distinctUsers
            )
        }

    private suspend fun fetchCounters(userEmail: String, bucketDay: String): CounterRow? {
        val cql = """
            SELECT total_messages, bot_count, non_bot_count
            FROM stats_counters
            WHERE user_email = ? AND bucket_day = ?
        """.trimIndent()

        // Use queryForMap for a single expected row
        return cassandraTemplate.reactiveCqlOperations
            .queryForMap(cql, userEmail, bucketDay)
            .map { row ->
                CounterRow(
                    totalMessages = row["total_messages"] as? Long ?: 0L,
                    botCount = row["bot_count"] as? Long ?: 0L,
                    nonBotCount = row["non_bot_count"] as? Long ?: 0L
                )
            }
            .awaitSingleOrNull()
    }

    private suspend fun fetchServerUrlCounts(
        userEmail: String,
        bucketDay: String
    ): Map<String, Long> {
        val cql = """
            SELECT server_url, COUNT(*) AS hit_count
            FROM server_url_events
            WHERE user_email = ? AND bucket_day = ?
            GROUP BY server_url
        """.trimIndent()

        // Use queryForRows for a Flux of multiple rows
        val rows = cassandraTemplate.reactiveCqlOperations
            .queryForRows(cql, userEmail, bucketDay)
            .collectList()
            .awaitSingleOrNull() ?: emptyList() // Fallback to emptyList if nothing is found

        return rows.mapNotNull { row ->
            val serverUrl = row.getString("server_url")
            if (serverUrl != null) {
                serverUrl to row.getLong("hit_count")
            } else {
                null
            }
        }.toMap()
    }

    private suspend fun fetchDistinctUserCount(userEmail: String, bucketDay: String): Int {
        val cql = """
            SELECT COUNT(*) AS distinct_count
            FROM tracked_user_sets
            WHERE user_email = ? AND bucket_day = ?
        """.trimIndent()

        // Use queryForObject since we only care about a single scalar value (Long)
        return cassandraTemplate.reactiveCqlOperations
            .queryForObject(cql, Long::class.javaObjectType, userEmail, bucketDay)
            .awaitSingleOrNull()?.toInt() ?: 0
    }

    private data class CounterRow(
        val totalMessages: Long,
        val botCount: Long,
        val nonBotCount: Long
    )
}