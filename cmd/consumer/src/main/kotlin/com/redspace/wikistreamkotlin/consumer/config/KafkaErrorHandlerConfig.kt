package com.redspace.wikistreamkotlin.consumer.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.redspace.wikistreamkotlin.consumer.DlqPublisher
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.KafkaProcessingError
import com.redspace.wikistreamkotlin.core.exception.MalformedKafkaRecordError
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
            // Route all unrecoverable errors to DLQ
            @Suppress("UNCHECKED_CAST")
            dlqPublisher.send(record as ConsumerRecord<String, *>, exception as Exception)

            when (exception) {
                is JsonProcessingException -> {
                    errorLogger.log(
                        error = MalformedKafkaRecordError(
                            message = "Failed to parse Kafka record — sent to DLQ",
                            cause = exception,
                        ),
                        level = ErrorLogLevel.WARN,
                        context = mapOf(
                            "topic" to record.topic(),
                            "partition" to record.partition(),
                            "offset" to record.offset(),
                        ),
                    )
                }
                else -> {
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
        }.apply {
            // Don't retry JSON parsing errors — go directly to DLQ
            addNotRetryableExceptions(JsonProcessingException::class.java)
        }
    }
}