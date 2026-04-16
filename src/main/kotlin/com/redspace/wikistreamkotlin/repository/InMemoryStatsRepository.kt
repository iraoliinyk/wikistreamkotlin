package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.domain.WikiEvent
import mu.KLogging
import org.springframework.stereotype.Repository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

@Repository
class InMemoryStatsRepository : StatsRepository {

    companion object : KLogging() {
        private const val SNAPSHOT_ID_PREFIX = "snapshot-"
    }

    private val totalMessages = AtomicInteger(0)
    private val botCount = AtomicInteger(0)
    private val nonBotCount = AtomicInteger(0)
    private val distinctUsers: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val serverUrlCounts = ConcurrentHashMap<String, AtomicInteger>()

    override fun record(event: WikiEvent) {
        logger.info("InMemoryStatsRepository record: $event")
        totalMessages.incrementAndGet()
        event.user?.let { distinctUsers.add(it) }
        if (event.bot == true) botCount.incrementAndGet() else nonBotCount.incrementAndGet()
        event.serverUrl?.let { serverUrlCounts.getOrPut(it) { AtomicInteger(0) }.incrementAndGet() }
    }

    override fun snapshot(): StatsSnapshot {
        return StatsSnapshot(
            id = "$SNAPSHOT_ID_PREFIX${System.currentTimeMillis()}",
            totalMessages = totalMessages.get(),
            distinctUsers = distinctUsers.size,
            botCount = botCount.get(),
            nonBotCount = nonBotCount.get(),
            countByServerUrl = serverUrlCounts.mapValues { it.value.get() }
        )
    }

}