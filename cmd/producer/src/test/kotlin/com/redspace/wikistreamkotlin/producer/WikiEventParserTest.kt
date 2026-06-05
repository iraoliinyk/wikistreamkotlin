package com.redspace.wikistreamkotlin.producer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class WikiEventParserTest {
    private val parser =
        WikiEventParser(
            objectMapper = ObjectMapper().registerKotlinModule(),
        )

    @Test
    fun `parseEvent parses a valid wikimedia payload`() {
        val payload =
            """
            {
              "schema": "mediawiki/recentchange/1.0.0",
              "meta": {
                "uri": "https://en.wikipedia.org/wiki/Main_Page",
                "request_id": "request-1",
                "id": "meta-1",
                "dt": "2026-04-17T00:00:00Z",
                "domain": "en.wikipedia.org",
                "stream": "mediawiki.recentchange",
                "topic": "eqiad.mediawiki.recentchange",
                "partition": "0",
                "offset": 1
              },
              "id": 42,
              "type": "edit",
              "namespace": 0,
              "title": "Main Page",
              "title_url": "https://en.wikipedia.org/wiki/Main_Page",
              "comment": "updated",
              "timestamp": 1713312000,
              "user": "TestUser",
              "bot": false,
              "notify_url": "https://en.wikipedia.org/w/index.php?diff=1&oldid=0",
              "server_url": "https://en.wikipedia.org",
              "server_name": "en.wikipedia.org",
              "server_script_path": "/w",
              "wiki": "enwiki",
              "parsedcomment": "updated"
            }
            """.trimIndent()

        val event = parser.parseEvent(payload)

        assertNotNull(event)
        assertEquals(42L, event?.id)
        assertEquals("TestUser", event?.user)
        assertEquals("https://en.wikipedia.org", event?.serverUrl)
    }

    @Test
    fun `parseEvent returns null for malformed payload`() {
        val event = parser.parseEvent("not-json")

        assertNull(event)
    }
}
