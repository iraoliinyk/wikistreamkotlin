package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent

interface StatsRepository {
    suspend fun recordForUser(
        userEmail: String,
        event: WikiEvent,
    )

    suspend fun snapshotForUser(userEmail: String): StatsSnapshot
}
