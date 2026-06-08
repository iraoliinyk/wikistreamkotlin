package com.redspace.wikistreamkotlin.core

object Topics {
    /**
     * @deprecated JSON-based raw event stream. Kept for backward-compatibility only.
     * Migrate producers and consumers to [PROTO].
     */
    @Deprecated(
        message = "JSON-based topic. Use PROTO for the Protobuf-encoded event stream.",
        replaceWith = ReplaceWith("Topics.PROTO"),
        level = DeprecationLevel.WARNING,
    )
    const val RAW = "wiki.recentchange.raw"

    /**
     * Canonical Protobuf event stream for Wikipedia recent-change events.
     * Supersedes [RAW]. Messages are serialized as [com.redspace.wikistreamkotlin.proto.WikiEvent]
     * protobuf binary.
     */
    const val PROTO = "wiki.recentchange.proto"

    const val DLQ = "wiki.recentchange.dlq"
}

