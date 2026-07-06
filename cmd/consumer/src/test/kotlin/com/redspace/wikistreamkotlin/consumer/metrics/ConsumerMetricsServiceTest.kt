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
    fun `recordBatchSuccess increments events consumed and batches counters`() {
        service.recordBatchSuccess(eventCount = 10, durationMs = 100)

        val eventsConsumedCounter = registry.find("wikistream.consumer.events.consumed")
            .tag("application", "consumer")
            .counter()

        val batchesCounter = registry.find("wikistream.consumer.batches.processed")
            .tag("application", "consumer")
            .counter()

        assertEquals(10.0, eventsConsumedCounter?.count())
        assertEquals(1.0, batchesCounter?.count())
    }

    @Test
    fun `recordBatchSuccess records batch processing duration`() {
        service.recordBatchSuccess(eventCount = 5, durationMs = 150)

        val timer = registry.find("wikistream.consumer.batch.duration")
            .tag("application", "consumer")
            .timer()

        assertEquals(1, timer?.count())
        assertEquals(150.0, timer?.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `recordBatchFailure increments failed persist and batches counters`() {
        service.recordBatchFailure(eventCount = 3)

        val failedCounter = registry.find("wikistream.consumer.events.persist.failed")
            .tag("application", "consumer")
            .counter()

        val batchesCounter = registry.find("wikistream.consumer.batches.processed")
            .tag("application", "consumer")
            .counter()

        assertEquals(3.0, failedCounter?.count())
        assertEquals(1.0, batchesCounter?.count())
    }

    @Test
    fun `incrementEventsPersistedToCassandra increments the correct counter`() {
        service.incrementEventsPersistedToCassandra(5.0)

        val counter = registry.find("wikistream.consumer.events.persisted.cassandra")
            .tag("application", "consumer")
            .counter()

        assertEquals(5.0, counter?.count())
    }

    @Test
    fun `incrementEventsPersistedToCassandra can be called multiple times`() {
        service.incrementEventsPersistedToCassandra(3.0)
        service.incrementEventsPersistedToCassandra(2.0)

        val counter = registry.find("wikistream.consumer.events.persisted.cassandra")
            .tag("application", "consumer")
            .counter()

        assertEquals(5.0, counter?.count())
    }

    @Test
    fun `multiple batches are tracked correctly`() {
        service.recordBatchSuccess(eventCount = 10, durationMs = 100)
        service.recordBatchSuccess(eventCount = 15, durationMs = 150)

        val eventsConsumedCounter = registry.find("wikistream.consumer.events.consumed")
            .tag("application", "consumer")
            .counter()

        val batchesCounter = registry.find("wikistream.consumer.batches.processed")
            .tag("application", "consumer")
            .counter()

        assertEquals(25.0, eventsConsumedCounter?.count())
        assertEquals(2.0, batchesCounter?.count())
    }

    @Test
    fun `unrelated counters are not incremented by recordBatchSuccess`() {
        service.recordBatchSuccess(eventCount = 10, durationMs = 100)

        val persisted = registry.find("wikistream.consumer.events.persisted.cassandra").counter()
        val failed = registry.find("wikistream.consumer.events.persist.failed").counter()

        assertEquals(0.0, persisted?.count() ?: 0.0)
        assertEquals(0.0, failed?.count() ?: 0.0)
    }
}