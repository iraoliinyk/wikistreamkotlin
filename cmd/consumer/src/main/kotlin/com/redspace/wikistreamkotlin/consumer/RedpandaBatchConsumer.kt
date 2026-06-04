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

@Component
class RedpandaBatchConsumer(
    private val statsService: StatsService,
) {
    @KafkaListener(topics = [Topics.RAW], containerFactory = "batchKafkaListenerContainerFactory")
    fun consume(records: List<ConsumerRecord<String, WikiEvent>>, ack: Acknowledgment) {
        runBlocking {
            records
                .map { record ->
                    async(Dispatchers.Default) {
                        statsService.recordForActiveUsers(record.value())
                    }
                }
                .awaitAll()
        }
        ack.acknowledge()
    }
}
