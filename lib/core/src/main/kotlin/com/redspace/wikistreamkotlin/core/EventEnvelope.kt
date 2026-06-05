package com.redspace.wikistreamkotlin.core

data class EventEnvelope<T>(
    val schemaVersion: Int,
    val eventType: String,
    val payload: T,
)

