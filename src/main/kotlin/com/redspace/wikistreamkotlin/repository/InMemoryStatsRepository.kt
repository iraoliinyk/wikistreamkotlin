package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import mu.KLogging
import org.springframework.stereotype.Repository

@Repository
class InMemoryStatsRepository: StatsRepository {

    companion object : KLogging()


    override fun record(event: WikiEvent) {
        // logs for now, the memory impl will be later
        logger.info(" InMemoryStatsRepository record: $event")
    }

    override fun snapshot(): StatsSnapshot {
        return StatsSnapshot(id = "-42")
    }

}