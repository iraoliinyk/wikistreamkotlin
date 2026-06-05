package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.core.KafkaTemplate

class RedpandaPublisherTest {
    private val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, WikiEvent>
    private val publisher = RedpandaPublisher(kafkaTemplate)

    @Test
    fun `uses event user as Kafka key when present`() {
        val event = wikiEvent(user = "Alice")

        publisher.publish(event)

        Mockito.verify(kafkaTemplate).send(Topics.RAW, "Alice", event)
    }

    @Test
    fun `uses fallback key unknown when event user is null`() {
        val event = wikiEvent(user = null)

        publisher.publish(event)

        Mockito.verify(kafkaTemplate).send(Topics.RAW, "unknown", event)
    }

    @Test
    fun `sends events to raw topic`() {
        val event = wikiEvent(user = "Bob")

        publisher.publish(event)

        Mockito.verify(kafkaTemplate).send(Mockito.eq(Topics.RAW), Mockito.eq("Bob"), Mockito.same(event))
    }

    private fun wikiEvent(user: String?): WikiEvent =
        WikiEvent(
            schema = "mediawiki/recentchange/1.0.0",
            meta = null,
            id = 42L,
            type = "edit",
            namespace = 0,
            title = "Main Page",
            titleUrl = null,
            comment = null,
            timestamp = 1L,
            user = user,
            bot = false,
            notifyUrl = null,
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = null,
        )
}
