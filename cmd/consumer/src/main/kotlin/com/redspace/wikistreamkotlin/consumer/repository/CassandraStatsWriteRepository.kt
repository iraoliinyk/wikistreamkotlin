package com.redspace.wikistreamkotlin.consumer.repository

import com.datastax.oss.driver.api.core.uuid.Uuids
import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.cassandra.core.ReactiveCassandraTemplate
import org.springframework.stereotype.Repository

@Repository
class CassandraStatsWriteRepository(
    private val cassandraTemplate: ReactiveCassandraTemplate
) : StatsWriteRepository {

    override suspend fun incrementCounters(
        userEmail: String,
        bucketDay: String,
        delta: UserStatsDelta
    ) {
        val cql = """
            UPDATE stats_counters
            SET total_messages = total_messages + ?,
                bot_count      = bot_count      + ?,
                non_bot_count  = non_bot_count  + ?
            WHERE user_email = ? AND bucket_day = ?
        """.trimIndent()

        // Use reactiveCqlOperations for raw CQL execution
        cassandraTemplate.reactiveCqlOperations.execute(
            cql,
            delta.totalMessages,
            delta.botCount,
            delta.nonBotCount,
            userEmail,
            bucketDay
        ).awaitSingleOrNull()
    }

    override suspend fun appendServerUrlEvents(
        userEmail: String,
        bucketDay: String,
        serverUrls: List<String>
    ) {
        if (serverUrls.isEmpty()) return

        val cql = """
            INSERT INTO server_url_events (user_email, bucket_day, server_url, event_id)
            VALUES (?, ?, ?, ?)
        """.trimIndent()

        // coroutineScope allows for parallel asynchronous inserts
        coroutineScope {
            serverUrls.map { serverUrl ->
                async {
                    cassandraTemplate.reactiveCqlOperations.execute(
                        cql,
                        userEmail,
                        bucketDay,
                        serverUrl,
                        Uuids.timeBased() // Guaranteed unique
                    ).awaitSingleOrNull()
                }
            }.awaitAll() // Wait for all parallel inserts to finish
        }
    }

    override suspend fun upsertTrackedUsers(
        userEmail: String,
        bucketDay: String,
        trackedUsers: Set<String>
    ) {
        if (trackedUsers.isEmpty()) return

        val cql = """
            INSERT INTO tracked_user_sets (user_email, bucket_day, tracked_user_email)
            VALUES (?, ?, ?)
        """.trimIndent()

        coroutineScope {
            trackedUsers.map { tracked ->
                async {
                    cassandraTemplate.reactiveCqlOperations.execute(
                        cql,
                        userEmail,
                        bucketDay,
                        tracked
                    ).awaitSingleOrNull()
                }
            }.awaitAll()
        }
    }
}