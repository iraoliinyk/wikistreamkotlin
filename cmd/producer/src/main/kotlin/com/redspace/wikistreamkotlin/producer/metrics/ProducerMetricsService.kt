package com.redspace.wikistreamkotlin.producer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class ProducerMetricsService(meterRegistry: MeterRegistry) {
    private val eventsConsumedFromStream: Counter = Counter.builder("wikistream.producer.events.consumed.sse")
        .description("Number of events consumed from Wikipedia SSE stream")
        .tag("application", "producer")
        .tag("source", "wikipedia-sse")
        .register(meterRegistry)

    private val eventsPersistedToRedpanda: Counter = Counter.builder("wikistream.producer.events.published")
        .description("Number of events successfully persisted to Redpanda")
        .tag("application", "producer")
        .tag("destination", "redpanda")
        .register(meterRegistry)

    private val eventsFailedToPersist: Counter = Counter.builder("wikistream.producer.events.publish.failed")
        .description("Number of events that failed to persist to Redpanda")
        .tag("application", "producer")
        .tag("destination", "redpanda")
        .register(meterRegistry)

    // Counts how many times the producer had to re-open the long-lived Wikimedia SSE
    // connection. A steady non-zero rate is normal (Wikimedia rotates connections every
    // few minutes); a sudden spike means upstream instability.
    private val sseReconnects: Counter = Counter.builder("wikistream.producer.sse.reconnects")
        .description("Number of times the producer reconnected to the Wikimedia SSE stream")
        .tag("application", "producer")
        .tag("source", "wikipedia-sse")
        .register(meterRegistry)

    fun incrementEventsConsumedFromStream() {
        eventsConsumedFromStream.increment()
    }

    fun incrementEventsPersistedToRedpanda() {
        eventsPersistedToRedpanda.increment()
    }

    fun incrementEventsFailedToPersist() {
        eventsFailedToPersist.increment()
    }

    fun incrementSseReconnects() {
        sseReconnects.increment()
    }
}