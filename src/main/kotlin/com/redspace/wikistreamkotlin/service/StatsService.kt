package com.redspace.wikistreamkotlin.service

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.repository.StatsRepository
import org.springframework.stereotype.Service

@Service
class StatsService(val statsRepository: StatsRepository) {
    fun record(event: WikiEvent) = statsRepository.record(event)

    fun getSnapshot(): StatsSnapshot = statsRepository.snapshot()
}