package com.redspace.wikistreamkotlin.consumer.serializer

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.mapper.ProtoWikiEventMapper
import org.apache.kafka.common.serialization.Deserializer
import org.slf4j.LoggerFactory

/**
 * Kafka deserializer for WikiEvent from protobuf format.
 * Converts protobuf binary format back to domain WikiEvent model.
 *
 * This is located in the consumer module as it's consumer-specific infrastructure.
 * It replaces StringDeserializer + manual ObjectMapper for type-safe deserialization.
 */
class ProtoWikiEventDeserializer : Deserializer<WikiEvent> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun deserialize(topic: String?, data: ByteArray?): WikiEvent? {
        if (data == null) {
            return null
        }
        return try {
            ProtoWikiEventMapper.deserializeWikiEvent(data)
        } catch (ex: Exception) {
            log.error("Error deserializing WikiEvent from protobuf format in topic: $topic", ex)
            throw ex
        }
    }

    override fun close() {
        // No resources to clean up
    }
}

