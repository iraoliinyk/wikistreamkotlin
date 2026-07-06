package com.redspace.wikistreamkotlin.consumer.metrics


import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ConsumerMetricsService(meterRegistry: MeterRegistry) {

    private val eventsConsumedFromStream: Counter = Counter.builder("wikistream.consumer.events.consumed")
        .description("Number of events consumed from Redpanda")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val eventsPersistedToCassandra: Counter = Counter.builder("wikistream.consumer.events.persisted.cassandra")
        .description("Number of events persisted to Cassandra")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val eventsFailedToPersist: Counter = Counter.builder("wikistream.consumer.events.persist.failed")
        .description("Number of events that failed to persist to Cassandra")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val batchesConsumedFromStream: Counter = Counter.builder("wikistream.consumer.batches.processed")
        .description("Number of batches of events consumed from Redpanda")
        .tag("application", "consumer")
        .register(meterRegistry)

    private val batchProcessingDuration: Timer = Timer.builder("wikistream.consumer.batch.duration")
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

    fun incrementEventsPersistedToCassandra(count: Double) {
        eventsPersistedToCassandra.increment(count)
    }
}