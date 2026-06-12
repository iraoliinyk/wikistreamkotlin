package com.redspace.wikistreamkotlin.consumer.metrics


import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

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