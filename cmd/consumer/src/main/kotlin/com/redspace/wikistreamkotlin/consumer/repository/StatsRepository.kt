package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.core.domain.WikiEvent

interface StatsRepository {
    suspend fun recordForUser(
        userEmail: String,
        event: WikiEvent,
    )

    suspend fun snapshotForUser(userEmail: String): StatsSnapshot
}