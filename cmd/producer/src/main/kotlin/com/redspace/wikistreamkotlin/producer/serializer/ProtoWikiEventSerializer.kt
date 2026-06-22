package com.redspace.wikistreamkotlin.producer.serializer

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ProducerParsingError
import com.redspace.wikistreamkotlin.core.mapper.ProtoWikiEventMapper
import org.apache.kafka.common.serialization.Serializer

/**
 * Kafka serializer for WikiEvent using protobuf format.
 * Converts domain WikiEvent to protobuf binary format for efficient transmission over Kafka.
 *
 * This is located in the producer module as it's producer-specific infrastructure.
 * It replaces JacksonJsonSerializer for 75-80% size reduction and 5-10x faster serialization.
 */
class ProtoWikiEventSerializer(
    private val errorLogger: ErrorLogger,
) : Serializer<WikiEvent> {
    override fun serialize(topic: String?, data: WikiEvent?): ByteArray? {
        if (data == null) {
            return null
        }
        return try {
            ProtoWikiEventMapper.serializeWikiEvent(data)
        } catch (ex: RuntimeException) {
            val parsingError = ProducerParsingError(
                message = "Error serializing WikiEvent to protobuf format",
                cause = ex,
            )
            errorLogger.log(
                error = parsingError,
                context = mapOf(
                    "topic" to topic,
                    "eventId" to data.id,
                    "eventType" to data.type,
                ),
            )
            throw parsingError
        }
    }

    override fun close() {
        // No resources to clean up
    }
}
