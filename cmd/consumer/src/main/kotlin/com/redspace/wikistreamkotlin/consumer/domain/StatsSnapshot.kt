package com.redspace.wikistreamkotlin.consumer.domain

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.springframework.data.cassandra.core.cql.PrimaryKeyType
import org.springframework.data.cassandra.core.mapping.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Deprecated("Mutable RMW domain object no longer needed")
@Table("stats_snapshots")
data class StatsSnapshot(
    @PrimaryKeyColumn(name = "user_email", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    val userEmail: String,

    @PrimaryKeyColumn(name = "bucket_day", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    val bucketDay: String, // "2026-06-19" format

    @PrimaryKeyColumn(name = "event_timestamp", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    val eventTimestamp: Instant = Instant.now(),

    @Column("batch_id")
    val batchId: String? = null,

    @Column("total_messages")
    val totalMessages: Int = 0,

    @Column("distinct_users")
    val distinctUsers: Int = 0,

    @Column("bot_count")
    val botCount: Int = 0,

    @Column("non_bot_count")
    val nonBotCount: Int = 0,

    @Column("count_by_server_url")
    @CassandraType(type = CassandraType.Name.MAP, typeArguments = [CassandraType.Name.TEXT, CassandraType.Name.INT])
    val countByServerUrl: Map<String, Int> = emptyMap(),

    @Column("tracked_users")
    @CassandraType(type = CassandraType.Name.SET, typeArguments = [CassandraType.Name.TEXT])
    val trackedUsers: Set<String> = emptySet(),

    @Column("version")
    val version: Long = 0
) {
    companion object {
        fun createBucketDay(instant: Instant = Instant.now()): String {
            return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString()
        }

        fun empty(userEmail: String, bucketDay: String? = null): StatsSnapshot {
            return StatsSnapshot(
                userEmail = userEmail,
                bucketDay = bucketDay ?: createBucketDay()
            )
        }
    }

    fun applyEvent(event: WikiEvent): StatsSnapshot {
        val normalizedUser = event.user?.takeIf { it.isNotBlank() }
        val updatedTrackedUsers = normalizedUser?.let { trackedUsers + it } ?: trackedUsers

        val normalizedServerUrl = event.serverUrl?.takeIf { it.isNotBlank() }
        val updatedCountByServerUrl = normalizedServerUrl?.let { serverUrl ->
            countByServerUrl + (serverUrl to ((countByServerUrl[serverUrl] ?: 0) + 1))
        } ?: countByServerUrl

        return copy(
            eventTimestamp = Instant.now(), // Update timestamp for new snapshot
            totalMessages = totalMessages + 1,
            distinctUsers = updatedTrackedUsers.size,
            botCount = botCount + if (event.bot == true) 1 else 0,
            nonBotCount = nonBotCount + if (event.bot == true) 0 else 1,
            countByServerUrl = updatedCountByServerUrl,
            trackedUsers = updatedTrackedUsers,
            version = version + 1
        )
    }
}