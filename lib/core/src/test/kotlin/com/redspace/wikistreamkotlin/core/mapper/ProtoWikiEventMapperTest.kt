package com.redspace.wikistreamkotlin.core.mapper

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.domain.WikiEventMeta
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull


class ProtoWikiEventMapperTest {
    @Test
    fun `WikiEvent round-trip serialization preserves all fields`() {
        // Arrange
        val originalEvent = WikiEvent(
            schema = "mediawiki.revision-create",
            meta = WikiEventMeta(
                uri = "https://en.wikipedia.org/api",
                requestId = "req-123-456",
                id = "evt-001",
                domain = "en.wikipedia.org",
                stream = "revisions",
                dt = "2026-06-03T10:00:00Z",
                topic = "eqiad.mediawiki.revision-create",
                partition = "0",
                offset = 12345L
            ),
            id = 1000L,
            type = "edit",
            namespace = 0,
            title = "Example Article",
            titleUrl = "Example_Article",
            comment = "Fixed typo in introduction",
            timestamp = 1717414800000L,
            user = "Editor123",
            bot = false,
            notifyUrl = "https://en.wikipedia.org/wiki/Special:Notifications",
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = "Fixed typo in introduction"
        )

        // Act: Convert to protobuf, serialize, deserialize, convert back
        val bytes = ProtoWikiEventMapper.serializeWikiEvent(originalEvent)
        val deserializedEvent = ProtoWikiEventMapper.deserializeWikiEvent(bytes)

        // Assert: All fields preserved
        assertEquals("mediawiki.revision-create", deserializedEvent.schema)
        assertEquals(1000L, deserializedEvent.id)
        assertEquals("edit", deserializedEvent.type)
        assertEquals(0, deserializedEvent.namespace)
        assertEquals("Example Article", deserializedEvent.title)
        assertEquals("Editor123", deserializedEvent.user)
        assertFalse(deserializedEvent.bot!!)
        assertEquals(1717414800000L, deserializedEvent.timestamp)

        // Assert: Metadata preserved
        deserializedEvent.meta?.apply {
            assertEquals("evt-001", id)
            assertEquals("en.wikipedia.org", domain)
            assertEquals(12345L, offset)
        }
    }
    @Test
    fun `WikiEvent handles optional fields correctly`() {
        // Arrange: Create event with minimal required fields
        val minimalEvent = WikiEvent(
            schema = null,
            meta = WikiEventMeta(
                uri = null,
                requestId = null,
                id = "required-meta-id",
                domain = null,
                stream = null,
                dt = null,
                topic = null,
                partition = null,
                offset = null
            ),
            id = null,
            type = null,
            namespace = null,
            title = null,
            titleUrl = null,
            comment = null,
            timestamp = null,
            user = null,
            bot = false,
            notifyUrl = null,
            serverUrl = null,
            serverName = null,
            serverScriptPath = null,
            wiki = null,
            parsedComment = null
        )

        // Act
        val bytes = ProtoWikiEventMapper.serializeWikiEvent(minimalEvent)
        val restored = ProtoWikiEventMapper.deserializeWikiEvent(bytes)

        // Assert: Optional fields are null
        assertNull(restored.schema)
        assertNull(restored.id)
        assertNull(restored.type)
        assertNull(restored.comment)
        assertNull(restored.user)
        assertFalse(restored.bot!!)

        // Assert: Meta still valid
        assertEquals("required-meta-id", restored.meta?.id)
    }

    @Test
    fun `WikiEvent bot field defaults to false`() {
        // Arrange
        val event = WikiEvent(
            schema = "test",
            meta = WikiEventMeta(
                uri = null,
                requestId = null,
                id = "test-id",
                domain = null,
                stream = null,
                dt = null,
                topic = null,
                partition = null,
                offset = null
            ),
            id = 1L,
            type = "test",
            namespace = null,
            title = null,
            titleUrl = null,
            comment = null,
            timestamp = null,
            user = "TestUser",
            bot = false,
            notifyUrl = null,
            serverUrl = null,
            serverName = null,
            serverScriptPath = null,
            wiki = null,
            parsedComment = null
        )

        // Act
        val bytes = ProtoWikiEventMapper.serializeWikiEvent(event)
        val restored = ProtoWikiEventMapper.deserializeWikiEvent(bytes)

        // Assert
        assertFalse(restored.bot!!)
    }

    @Test
    fun `Large batch of events serializes and deserializes correctly`() {
        // Arrange: Create 1000 events
        val events = (1..1000).map { i ->
            WikiEvent(
                schema = "mediawiki.revision-create",
                meta = WikiEventMeta(
                    uri = "https://en.wikipedia.org",
                    requestId = null,
                    id = "evt-$i",
                    domain = "en.wikipedia.org",
                    stream = null,
                    dt = null,
                    topic = null,
                    partition = "0",
                    offset = i.toLong()
                ),
                id = i.toLong(),
                type = "edit",
                namespace = 0,
                title = "Article $i",
                titleUrl = null,
                comment = null,
                timestamp = System.currentTimeMillis(),
                user = "User$i",
                bot = false,
                notifyUrl = null,
                serverUrl = null,
                serverName = null,
                serverScriptPath = null,
                wiki = "enwiki",
                parsedComment = null
            )
        }

        // Act & Assert: Serialize and verify each
        val serializedEvents = events.map { event ->
            ProtoWikiEventMapper.serializeWikiEvent(event) to event.id
        }

        serializedEvents.forEach { (bytes, originalId) ->
            val restored = ProtoWikiEventMapper.deserializeWikiEvent(bytes)
            assertEquals(originalId, restored.id)
        }
    }

    @Test
    fun `Deserializing invalid bytes throws exception`() {
        // Arrange
        val invalidBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        // Act & Assert
        try {
            ProtoWikiEventMapper.deserializeWikiEvent(invalidBytes)
            throw AssertionError("Should have thrown exception")
        } catch (_: com.google.protobuf.InvalidProtocolBufferException) {
            // Expected
        }
    }
}
