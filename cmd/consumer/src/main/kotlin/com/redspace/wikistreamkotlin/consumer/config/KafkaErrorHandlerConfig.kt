package com.redspace.wikistreamkotlin.consumer.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.redspace.wikistreamkotlin.consumer.exception.ConsumerErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.KafkaProcessingError
import com.redspace.wikistreamkotlin.core.exception.MalformedKafkaRecordError
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
class KafkaErrorHandlerConfig(
    private val consumerErrorLogger: ConsumerErrorLogger,
) {
    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler {
        return DefaultErrorHandler { record, exception ->
            when (exception) {
                is JsonProcessingException -> {
                    consumerErrorLogger.log(
                        error = MalformedKafkaRecordError(
                            message = "Failed to parse Kafka record in error handler",
                            cause = exception,
                        ),
                        level = ErrorLogLevel.WARN,
                        context = mapOf(
                            "topic" to record.topic(),
                            "partition" to record.partition(),
                            "offset" to record.offset(),
                        ),
                    )
                    // Don't retry - skip this record
                }
                else -> {
                    consumerErrorLogger.log(
                        error = KafkaProcessingError(
                            message = "Kafka processing failed",
                            cause = exception,
                        ),
                        level = ErrorLogLevel.ERROR,
                        context = mapOf(
                            "topic" to record.topic(),
                            "offset" to record.offset(),
                        ),
                    )
                    // Will retry based on configuration
                }
            }
        }.apply {
            // Don't retry JSON parsing errors
            addNotRetryableExceptions(JsonProcessingException::class.java)
        }
    }
}