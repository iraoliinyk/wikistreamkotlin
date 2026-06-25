package com.redspace.wikistreamkotlin.consumer.metrics


import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ConsumerMetricsService(meterRegistry: MeterRegistry) {

    private val eventsConsumedFromStream: Counter = Counter.builder("wikistream.events.consumed.from.stream")
        .description("Number of events consumed from Redpanda")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val eventsPersistedToRedpanda: Counter = Counter.builder("wikistream.events.persisted.to.redpanda")
        .description("Number of events persisted to DB")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val eventsFailedToPersist: Counter = Counter.builder("wikistream.events.persist.failed")
        .description("Number of events that failed to persist to DB")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val batchesConsumedFromStream: Counter = Counter.builder("wikistream.events.batches.from.stream")
        .description("Number of batches of events consumed from Redpanda")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val batchProcessingDuration: Timer = Timer.builder("wikistream.batch.processing.duration")
        .description("Time taken to process a batch")
        .tag("application", "consumer")
        .publishPercentiles(0.5, 0.95, 0.99)  // Optional: track p50, p95, p99
        .register(meterRegistry)

    fun recordBatchSuccess(eventCount: Int, durationMs: Long) {
        eventsConsumedFromStream.increment(eventCount.toDouble())
        batchProcessingDuration.record(durationMs, TimeUnit.MILLISECONDS)
        batchesConsumedFromStream.increment()
    }

    fun recordBatchFailure(eventCount: Int) {
        eventsFailedToPersist.increment(eventCount.toDouble())
        batchesConsumedFromStream.increment()
    }

    fun incrementEventsPersistedToRedpanda(count: Double) {
        eventsPersistedToRedpanda.increment(count)
    }
}