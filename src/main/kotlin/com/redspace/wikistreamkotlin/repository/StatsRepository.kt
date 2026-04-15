package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent

interface StatsRepository {
    fun record(event: WikiEvent)
    fun snapshot(): StatsSnapshot
}