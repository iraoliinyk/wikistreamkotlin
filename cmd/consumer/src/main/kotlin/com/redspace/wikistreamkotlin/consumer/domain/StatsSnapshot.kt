package com.redspace.wikistreamkotlin.consumer.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.annotation.Version
import org.springframework.data.cassandra.core.mapping.CassandraType
import org.springframework.data.cassandra.core.mapping.Column
import org.springframework.data.cassandra.core.mapping.PrimaryKey
import org.springframework.data.cassandra.core.mapping.Table

@Table("stats_snapshots")
data class StatsSnapshot(
    @PrimaryKey
    val id: String = GLOBAL_ID,
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
    @JsonIgnore
    @Column("tracked_users")
    @CassandraType(type = CassandraType.Name.SET, typeArguments = [CassandraType.Name.TEXT])
    val trackedUsers: Set<String> = emptySet(),
    @JsonIgnore
    @Version
    val version: Long? = null,
) {
    companion object {
        const val GLOBAL_ID = "global-stats"
    }
}