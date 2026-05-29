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
                .map { record ->
                    async(Dispatchers.Default) {
                        try {
                            val event = objectMapper.readValue(record.value(), WikiEvent::class.java)
                            statsService.recordForActiveUsers(event)
                        } catch (ex: Exception) {
                            // log and skip malformed records
                        }
                    }
                }.awaitAll()
        }
        ack.acknowledge()
    }
}

