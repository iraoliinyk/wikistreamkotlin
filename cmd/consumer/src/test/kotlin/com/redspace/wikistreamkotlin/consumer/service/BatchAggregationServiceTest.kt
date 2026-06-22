package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BatchAggregationServiceTest {

    private val service = BatchAggregationService()

    // region helpers

    /**
     * Creates a minimal WikiEvent for testing.
     * Only the fields relevant to aggregation have non-null defaults;
     * all other protocol fields stay null to keep test intent clear.
     */
    private fun mockWikiEvent(
        user: String? = "alice",
        bot: Boolean? = false,
        serverUrl: String? = "https://en.wikipedia.org",
        id: Long = System.nanoTime()
    ): WikiEvent = WikiEvent(
        schema = null,
        meta = null,
        id = id,
        type = null,
        namespace = null,
        title = null,
        titleUrl = null,
        comment = null,
        timestamp = null,
        user = user,
        bot = bot,
        notifyUrl = null,
        serverUrl = serverUrl,
        serverName = null,
        serverScriptPath = null,
        wiki = null,
        parsedComment = null
    )

    // endregion

    @Test
    fun `groups events by user and computes correct deltas`() {
        val events = listOf(
            mockWikiEvent(user = "alice", bot = false, serverUrl = "https://en.wikipedia.org"),
            mockWikiEvent(user = "alice", bot = true,  serverUrl = "https://de.wikipedia.org"),
            mockWikiEvent(user = "bob",   bot = false, serverUrl = "https://en.wikipedia.org")
        )

        val result = service.aggregateBatch(events)

        assertThat(result).hasSize(2)

        val alice = result["alice"]!!
        assertThat(alice.totalMessages).isEqualTo(2)
        assertThat(alice.botCount).isEqualTo(1)
        assertThat(alice.nonBotCount).isEqualTo(1)
        assertThat(alice.serverUrls).containsExactlyInAnyOrder(
            "https://en.wikipedia.org",
            "https://de.wikipedia.org"
        )
        assertThat(alice.trackedUsers).containsExactly("alice")

        val bob = result["bob"]!!
        assertThat(bob.totalMessages).isEqualTo(1)
        assertThat(bob.botCount).isEqualTo(0)
        assertThat(bob.nonBotCount).isEqualTo(1)
    }

    @Test
    fun `maps null user to unknown`() {
        val events = listOf(mockWikiEvent(user = null))

        val result = service.aggregateBatch(events)

        assertThat(result).containsKey("unknown")
        assertThat(result["unknown"]!!.totalMessages).isEqualTo(1)
    }

    @Test
    fun `maps blank user to unknown`() {
        val events = listOf(mockWikiEvent(user = "   "))

        val result = service.aggregateBatch(events)

        assertThat(result).containsKey("unknown")
        assertThat(result.keys).doesNotContain("   ")
    }

    @Test
    fun `deduplicates tracked users within a batch`() {
        val events = listOf(
            mockWikiEvent(user = "alice"),
            mockWikiEvent(user = "alice")
        )

        val delta = service.aggregateBatch(events)["alice"]!!

        assertThat(delta.trackedUsers).hasSize(1).containsExactly("alice")
    }

    @Test
    fun `filters blank and null server urls`() {
        val events = listOf(
            mockWikiEvent(user = "alice", serverUrl = "  "),
            mockWikiEvent(user = "alice", serverUrl = null),
            mockWikiEvent(user = "alice", serverUrl = "https://en.wikipedia.org")
        )

        val delta = service.aggregateBatch(events)["alice"]!!

        assertThat(delta.serverUrls).containsExactly("https://en.wikipedia.org")
    }

    @Test
    fun `returns empty map for empty input`() {
        assertThat(service.aggregateBatch(emptyList())).isEmpty()
    }
}