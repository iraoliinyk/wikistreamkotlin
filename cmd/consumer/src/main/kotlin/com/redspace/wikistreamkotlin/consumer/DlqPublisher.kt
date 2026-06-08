package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.KafkaProcessingError
import com.redspace.wikistreamkotlin.core.mapper.ProtoWikiEventMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Publishes failed records to the Dead Letter Queue (DLQ) topic.
 *
 * Failed messages are forwarded with their original key and value (raw bytes),
 * plus headers containing error metadata for debugging and reprocessing.
 *
 * Headers added:
 * - `dlq.error.message` — exception message
 * - `dlq.error.class` — exception class name
 * - `dlq.source.topic` — original topic
 * - `dlq.source.partition` — original partition
 * - `dlq.source.offset` — original offset
 */
@Component
class DlqPublisher(
    private val dlqKafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val errorLogger: ErrorLogger,
) {
    /**
     * Send a failed record to the DLQ with error context headers.
     *
     * @param record The original consumer record that failed processing
     * @param exception The exception that caused the failure
     */
    fun send(record: ConsumerRecord<String, *>, exception: Exception) {
        val rawValue = extractRawBytes(record)

        val dlqRecord = ProducerRecord(
            Topics.DLQ,
            null, // partition (let Kafka decide)
            record.key(),
            rawValue,
            listOf(
                RecordHeader("dlq.error.message", (exception.message ?: "unknown").toByteArray()),
                RecordHeader("dlq.error.class", exception.javaClass.name.toByteArray()),
                RecordHeader("dlq.source.topic", record.topic().toByteArray()),
                RecordHeader("dlq.source.partition", record.partition().toString().toByteArray()),
                RecordHeader("dlq.source.offset", record.offset().toString().toByteArray()),
            ),
        )

        dlqKafkaTemplate.send(dlqRecord)
            .whenComplete { _, ex ->
                if (ex != null) {
                    errorLogger.log(
                        error = KafkaProcessingError(
                            message = "Failed to send record to DLQ",
                            cause = ex,
                        ),
                        level = ErrorLogLevel.ERROR,
                        context = mapOf(
                            "topic" to record.topic(),
                            "partition" to record.partition(),
                            "offset" to record.offset(),
                        ),
                    )
                } else {
                    errorLogger.log(
                        error = KafkaProcessingError(
                            message = "Record sent to DLQ due to processing failure",
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
            }
    }

    private fun extractRawBytes(record: ConsumerRecord<String, *>): ByteArray {
        return when (val value = record.value()) {
            is ByteArray -> value
            is WikiEvent -> ProtoWikiEventMapper.serializeWikiEvent(value)
            else -> value?.toString()?.toByteArray() ?: ByteArray(0)
        }
    }
}

