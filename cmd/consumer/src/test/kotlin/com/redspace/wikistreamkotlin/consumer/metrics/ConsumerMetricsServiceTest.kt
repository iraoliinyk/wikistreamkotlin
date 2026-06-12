package com.redspace.wikistreamkotlin.consumer.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConsumerMetricsServiceTest {

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var service: ConsumerMetricsService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        service = ConsumerMetricsService(registry)
    }

    @Test
    fun `incrementEventsConsumedFromStream increments the correct counter`() {
        service.incrementEventsConsumedFromStream()
        service.incrementEventsConsumedFromStream()

        val counter = registry.find("wikistream.events.consumed.from.stream")
            .tag("application", "consumer")
            .counter()

        assertEquals(2.0, counter?.count())
    }

    @Test
    fun `incrementEventsPersistedToRedpanda increments the correct counter`() {
        service.incrementEventsPersistedToRedpanda()

        val counter = registry.find("wikistream.events.persisted.to.redpanda")
            .tag("application", "consumer")
            .counter()

        assertEquals(1.0, counter?.count())
    }

    @Test
    fun `incrementEventsFailedToPersist increments the correct counter`() {
        service.incrementEventsFailedToPersist()
        service.incrementEventsFailedToPersist()
        service.incrementEventsFailedToPersist()

        val counter = registry.find("wikistream.events.persist.failed")
            .tag("application", "consumer")
            .counter()

        assertEquals(3.0, counter?.count())
    }

    @Test
    fun `unrelated counters are not incremented`() {
        service.incrementEventsConsumedFromStream()

        val persisted = registry.find("wikistream.events.persisted.to.redpanda").counter()
        val failed    = registry.find("wikistream.events.persist.failed").counter()

        assertEquals(0.0, persisted?.count() ?: 0.0)
        assertEquals(0.0, failed?.count()    ?: 0.0)
    }
}