package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent

interface StatsRepository {
    suspend fun record(event: WikiEvent)
    suspend fun snapshot(): StatsSnapshot
}