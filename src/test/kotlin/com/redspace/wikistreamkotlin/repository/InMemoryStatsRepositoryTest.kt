package com.redspace.wikistreamkotlin.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryStatsRepositoryTest {

    @Test
    fun `record keeps consistent counts under concurrent updates`() = runBlocking {
        val repository = InMemoryStatsRepository()
        val eventsPerProducer = 100

        coroutineScope {
            val jobs = listOf(
                launch(Dispatchers.Default) {
                    repeat(eventsPerProducer) {
                        repository.record(
                            WikiEventMockFactory.createWikiEvent(
                                id = it.toLong(),
                                user = "alice",
                                bot = false
                            )
                        )
                    }
                },
                launch(Dispatchers.Default) {
                    repeat(eventsPerProducer) {
                        repository.record(
                            WikiEventMockFactory.createWikiEvent(
                                id = (1_000 + it).toLong(),
                                user = "bot-user",
                                bot = true
                            )
                        )
                    }
                }
            )
            jobs.joinAll()
        }

        val snapshot = repository.snapshot()
        assertEquals(eventsPerProducer * 2, snapshot.totalMessages)
        assertEquals(2, snapshot.distinctUsers)
        assertEquals(eventsPerProducer, snapshot.botCount)
        assertEquals(eventsPerProducer, snapshot.nonBotCount)
        assertEquals(eventsPerProducer * 2, snapshot.countByServerUrl["https://en.wikipedia.org"])
    }
}

