package com.redspace.wikistreamkotlin.core.mapper

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.domain.WikiEventMeta
import com.redspace.wikistreamkotlin.proto.wikiEvent
import com.redspace.wikistreamkotlin.proto.wikiEventMeta
import com.redspace.wikistreamkotlin.proto.WikiEvent as ProtoWikiEvent
import com.redspace.wikistreamkotlin.proto.WikiEventMeta as ProtoWikiEventMeta

/**
 * Converts between domain models and protobuf messages.
 *
 * This mapper ensures safe round-trip serialization of WikiEvent objects
 * while handling null/optional field conversions correctly.
 */
object ProtoWikiEventMapper {
    /**
     * Convert domain WikiEvent to protobuf format.
     *
     * @param event Domain model to convert
     * @return Protobuf message ready for serialization
     */
    fun toDomainProtoWikiEvent(event: WikiEvent): ProtoWikiEvent {
        return wikiEvent {
            event.schema?.let { schema = it }
            event.meta?.let { meta = toProtoWikiEventMeta(it) }
            event.id?.let { id = it }
            event.type?.let { type = it }
            event.namespace?.let { namespace = it }
            event.title?.let { title = it }
            event.titleUrl?.let { titleUrl = it }
            event.comment?.let { comment = it }
            event.timestamp?.let { timestamp = it }
            event.user?.let { user = it }
            bot = event.bot ?: false
            event.notifyUrl?.let { notifyUrl = it }
            event.serverUrl?.let { serverUrl = it }
            event.serverName?.let { serverName = it }
            event.serverScriptPath?.let { serverScriptPath = it }
            event.wiki?.let { wiki = it }
            event.parsedComment?.let { parsedcomment = it }
        }
    }

    /**
     * Convert protobuf WikiEvent to domain format.
     *
     * @param protoEvent Protobuf message to convert
     * @return Domain model instance
     */
    fun toWikiEvent(protoEvent: ProtoWikiEvent): WikiEvent {
        return WikiEvent(
            schema = protoEvent.schema.ifEmpty { null },
            meta = if (protoEvent.hasMeta()) toDomainWikiEventMeta(protoEvent.meta) else null,
            id = if (protoEvent.hasId()) protoEvent.id else null,
            type = protoEvent.type.ifEmpty { null },
            namespace = if (protoEvent.hasNamespace()) protoEvent.namespace else null,
            title = protoEvent.title.ifEmpty { null },
            titleUrl = protoEvent.titleUrl.ifEmpty { null },
            comment = protoEvent.comment.ifEmpty { null },
            timestamp = if (protoEvent.hasTimestamp()) protoEvent.timestamp else null,
            user = protoEvent.user.ifEmpty { null },
            bot = protoEvent.bot,
            notifyUrl = protoEvent.notifyUrl.ifEmpty { null },
            serverUrl = protoEvent.serverUrl.ifEmpty { null },
            serverName = protoEvent.serverName.ifEmpty { null },
            serverScriptPath = protoEvent.serverScriptPath.ifEmpty { null },
            wiki = protoEvent.wiki.ifEmpty { null },
            parsedComment = protoEvent.parsedcomment.ifEmpty { null }
        )
    }

    /**
     * Convert domain WikiEventMeta to protobuf format.
     */
    fun toProtoWikiEventMeta(meta: WikiEventMeta): ProtoWikiEventMeta {
        return wikiEventMeta {
            meta.uri?.let { uri = it }
            meta.requestId?.let { requestId = it }
            id = meta.id // required field
            meta.domain?.let { domain = it }
            meta.stream?.let { stream = it }
            meta.dt?.let { dt = it }
            meta.topic?.let { topic = it }
            meta.partition?.let { partition = it }
            meta.offset?.let { offset = it }
        }
    }

    /**
     * Convert protobuf WikiEventMeta to domain format.
     */
    fun toDomainWikiEventMeta(protoMeta: ProtoWikiEventMeta): WikiEventMeta {
        return WikiEventMeta(
            uri = protoMeta.uri.ifEmpty { null },
            requestId = protoMeta.requestId.ifEmpty { null },
            id = protoMeta.id,
            domain = protoMeta.domain.ifEmpty { null },
            stream = protoMeta.stream.ifEmpty { null },
            dt = protoMeta.dt.ifEmpty { null },
            topic = protoMeta.topic.ifEmpty { null },
            partition = protoMeta.partition.ifEmpty { null },
            offset = if (protoMeta.hasOffset()) protoMeta.offset else null
        )
    }


    /**
     * Serialize domain WikiEvent to bytes.
     *
     * @param event Domain model to serialize
     * @return Binary protobuf message
     */
    fun serializeWikiEvent(event: WikiEvent): ByteArray {
        val protoEvent = toDomainProtoWikiEvent(event)
        return protoEvent.toByteArray()
    }

    /**
     * Deserialize bytes to domain WikiEvent.
     *
     * @param bytes Binary protobuf message
     * @return Deserialized domain model
     * @throws com.google.protobuf.InvalidProtocolBufferException if bytes are invalid
     */
    fun deserializeWikiEvent(bytes: ByteArray): WikiEvent {
        val protoEvent = ProtoWikiEvent.parseFrom(bytes)
        return toWikiEvent(protoEvent)
    }

}