package com.redspace.wikistreamkotlin.consumer.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Kafka producer configuration for the Dead Letter Queue (DLQ).
 *
 * Publishes raw bytes (the original record value) to the DLQ topic
 * so that failed messages are preserved exactly as received.
 */
@Configuration
class DlqKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers:localhost:19092}") private val bootstrapServers: String,
) {
    @Bean
    fun dlqProducerFactory(): ProducerFactory<String, ByteArray> {
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
        )
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun dlqKafkaTemplate(dlqProducerFactory: ProducerFactory<String, ByteArray>): KafkaTemplate<String, ByteArray> {
        return KafkaTemplate(dlqProducerFactory)
    }
}

