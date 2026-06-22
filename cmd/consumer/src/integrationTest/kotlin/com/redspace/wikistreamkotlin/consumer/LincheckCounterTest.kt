package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.jupiter.api.Test

/**
 * Lincheck stress test verifying that [BatchAggregationService.aggregateBatch] is race-free
 * under concurrent invocation. The service is stateless by design; this test guards against
 * regressions that might introduce hidden shared mutable state.
 *
 * Each invocation aggregates a single-event batch for user "alice" and returns the resulting
 * delta. Because every call operates on identical input, every concurrent execution must
 * return an identical, deterministic [UserStatsDelta] — any race would surface as a
 * non-linearizable history.
 */
class LincheckCounterTest {

    private val aggregator = BatchAggregationService()

    private val event = WikiEvent(
        schema = "/mediawiki/recentchange/1.0.0",
        meta = null,
        id = 1L,
        type = "edit",
        namespace = 0,
        title = "Sample",
        titleUrl = "https://en.wikipedia.org/wiki/Sample",
        comment = "test edit",
        timestamp = 1_700_000_000L,
        user = "alice",
        bot = false,
        notifyUrl = null,
        serverUrl = "https://en.wikipedia.org",
        serverName = "en.wikipedia.org",
        serverScriptPath = "/w",
        wiki = "enwiki",
        parsedComment = null,
    )

    @Operation
    fun increment(): UserStatsDelta? = aggregator.aggregateBatch(listOf(event))["alice"]

    @Test
    fun runTest() = StressOptions().check(this::class)
}