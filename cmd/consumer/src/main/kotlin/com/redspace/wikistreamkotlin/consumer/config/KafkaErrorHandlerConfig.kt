package com.redspace.wikistreamkotlin.consumer.config

import com.redspace.wikistreamkotlin.consumer.DlqPublisher
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.KafkaProcessingError
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
class KafkaErrorHandlerConfig(
    private val errorLogger: ErrorLogger,
    private val dlqPublisher: DlqPublisher,
) {
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler {
        return DefaultErrorHandler { record, exception ->
            @Suppress("UNCHECKED_CAST")
            dlqPublisher.send(record as ConsumerRecord<String, *>, exception as Exception)
            errorLogger.log(
                error = KafkaProcessingError(
                    message = "Kafka processing failed — sent to DLQ",
                    cause = exception,
                ),
                level = ErrorLogLevel.ERROR,
                context = mapOf(
                    "topic" to record.topic(),
                    "offset" to record.offset(),
                ),
            )
        }
    }
}