package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.springframework.stereotype.Service

/**
 * Maps a raw batch of WikiEvents to per-user deltas.
 */
@Service
class BatchAggregationService {

    fun aggregateBatch(events: List<WikiEvent>): Map<String, UserStatsDelta> =
        events
            .groupBy { it.user?.takeIf(String::isNotBlank) ?: UNKNOWN_USER }
            .mapValues { (_, userEvents) -> toDelta(userEvents) }

    fun aggregateBatchAsSingleDelta(events: List<WikiEvent>): UserStatsDelta =
        UserStatsDelta(
            totalMessages = events.size.toLong(),
            botCount      = events.count { it.bot == true }.toLong(),
            nonBotCount   = events.count { it.bot != true }.toLong(),
            serverUrls    = events.mapNotNull { it.serverUrl?.takeIf(String::isNotBlank) },
            trackedUsers  = events.mapNotNull { it.user?.takeIf(String::isNotBlank) }.toSet(),
        )

    private fun toDelta(events: List<WikiEvent>) = UserStatsDelta(
        totalMessages = events.size.toLong(),
        botCount = events.count { it.bot == true }.toLong(),
        nonBotCount = events.count { it.bot != true }.toLong(),
        serverUrls = events.mapNotNull { it.serverUrl?.takeIf(String::isNotBlank) },
        trackedUsers = events.mapNotNull { it.user?.takeIf(String::isNotBlank) }.toSet()
    )

    companion object {
        private const val UNKNOWN_USER = "unknown"
    }
}