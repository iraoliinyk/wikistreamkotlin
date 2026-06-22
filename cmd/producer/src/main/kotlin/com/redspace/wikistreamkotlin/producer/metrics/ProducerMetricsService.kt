package com.redspace.wikistreamkotlin.producer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class ProducerMetricsService(meterRegistry: MeterRegistry) {
    private val eventsConsumedFromStream: Counter = Counter.builder("wikistream.events.consumed.from.stream")
        .description("Number of events consumed from Wikipedia SSE stream")
        .tag("application", "producer")
        .tag("source", "wikipedia-sse")
        .register(meterRegistry)

    private val eventsPersistedToRedpanda: Counter = Counter.builder("wikistream.events.persisted.to.redpanda")
        .description("Number of events successfully persisted to Redpanda")
        .tag("application", "producer")
        .tag("destination", "redpanda")
        .register(meterRegistry)

    private val eventsFailedToPersist: Counter = Counter.builder("wikistream.events.persist.failed")
        .description("Number of events that failed to persist to Redpanda")
        .tag("application", "producer")
        .tag("destination", "redpanda")
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
}