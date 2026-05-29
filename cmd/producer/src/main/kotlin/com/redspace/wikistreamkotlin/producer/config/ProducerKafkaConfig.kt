package com.redspace.wikistreamkotlin.producer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

@Configuration
class ProducerKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
) {
    private fun baseProducerProps(): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
        ProducerConfig.COMPRESSION_TYPE_CONFIG to "zstd",
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JacksonJsonSerializer::class.java,
        // Keep payload clean and stable across consumers without Spring type headers.
        JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS to false,
    )

    @Bean
    fun wikiEventProducerFactory(): ProducerFactory<String, WikiEvent> =
        DefaultKafkaProducerFactory(baseProducerProps())

    @Bean
    fun kafkaTemplate(wikiEventProducerFactory: ProducerFactory<String, WikiEvent>): KafkaTemplate<String, WikiEvent> =
        KafkaTemplate(wikiEventProducerFactory)
}

