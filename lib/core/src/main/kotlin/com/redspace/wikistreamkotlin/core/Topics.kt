package com.redspace.wikistreamkotlin.core

object Topics {
    /**
     * Canonical Protobuf event stream for Wikipedia recent-change events.
     * Messages are serialized as [com.redspace.wikistreamkotlin.proto.WikiEvent]
     * protobuf binary.
     */
    const val PROTO = "wiki.recentchange.proto"

    /**
     * Dead-letter queue for records that could not be processed.
     * Two producers write here with a shared `dlq.*` error-header contract
     * (see `DlqPublisher` in `lib/kafka`): the RPCN pipeline forwards protobuf
     * decode/validation failures, and the consumer forwards Cassandra write
     * failures. Payload is the original unprocessable record, left untouched.
     */
    const val DLQ = "wiki.recentchange.dlq"

    /**
     * Protobuf events that passed Redpanda Connect decode/validation.
     * Written by the RPCN pipeline (rpcn/pipeline.yaml); consumed by cmd/consumer.
     * Payload is byte-identical to [PROTO] — RPCN validates, never re-serializes.
     */
    const val VALIDATED = "wiki.recentchange.validated"
}

