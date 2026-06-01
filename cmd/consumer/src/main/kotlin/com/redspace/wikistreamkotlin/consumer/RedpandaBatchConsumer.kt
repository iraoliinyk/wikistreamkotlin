package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.consumer.service.StatsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import com.fasterxml.jackson.databind.ObjectMapper

@Component
class RedpandaBatchConsumer(
    private val statsService: StatsService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.RAW], containerFactory = "batchKafkaListenerContainerFactory")
    fun consume(records: List<ConsumerRecord<String, String>>, ack: Acknowledgment) {
        runBlocking {
            records
                .mapNotNull { record ->
                    try {
                        objectMapper.readValue(record.value(), WikiEvent::class.java)
                    } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
                        // Parsing errors are handled by KafkaErrorHandler
                        // We skip them here to continue processing valid records
                        null
                    }
                }
                .map { event ->
                    async(Dispatchers.Default) {
                        statsService.recordForActiveUsers(event)
                    }
                }
                .awaitAll()
        }
        ack.acknowledge()
    }
}

