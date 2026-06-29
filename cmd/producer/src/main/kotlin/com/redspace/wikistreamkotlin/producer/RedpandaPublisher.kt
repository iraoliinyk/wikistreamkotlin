package com.redspace.wikistreamkotlin.producer

import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class RedpandaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, WikiEvent>,
) {
    fun publish(event: WikiEvent) {
        kafkaTemplate.send(Topics.PROTO, event)
    }
}