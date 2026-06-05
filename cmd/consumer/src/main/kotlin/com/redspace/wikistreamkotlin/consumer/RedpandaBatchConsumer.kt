package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.service.StatsService
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.KafkaProcessingError
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
    private val dlqPublisher: DlqPublisher,
    private val errorLogger: ErrorLogger
) {

    @KafkaListener(topics = [Topics.RAW], containerFactory = "batchKafkaListenerContainerFactory")
    fun consume(records: List<ConsumerRecord<String, WikiEvent>>, ack: Acknowledgment) {
        runBlocking {
            records
                .map { record ->
                    async(Dispatchers.Default) {
                        processRecord(record)
                    }
                }
                .awaitAll()
        }
        ack.acknowledge()
    }

    private suspend fun processRecord(record: ConsumerRecord<String, WikiEvent>) {
        try {
            statsService.recordForActiveUsers(record.value())
        } catch (ex: Exception) {
            errorLogger.log(
                error = KafkaProcessingError(
                    message = "Kafka processing failed — sent to DLQ",
                    cause = ex,
                ),
                level = ErrorLogLevel.ERROR,
                context = mapOf(
                    "topic" to record.topic(),
                    "partition" to record.partition(),
                    "offset" to record.offset(),
                ),
            )
            dlqPublisher.send(record, ex)
        }
    }
}
