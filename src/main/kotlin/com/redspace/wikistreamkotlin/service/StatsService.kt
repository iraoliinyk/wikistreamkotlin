package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.repository.StatsRepository
import org.springframework.stereotype.Service

@Service
class StatsService(private val statsRepository: StatsRepository) {
    suspend fun record(event: WikiEvent) {
        statsRepository.record(event)
    }

    suspend fun getSnapshot(): StatsSnapshot {
        return statsRepository.snapshot()
    }
}