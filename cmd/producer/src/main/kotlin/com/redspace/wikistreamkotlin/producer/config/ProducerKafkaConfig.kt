package com.redspace.wikistreamkotlin.producer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.producer.exception.ProducerErrorLogger
import com.redspace.wikistreamkotlin.producer.serializer.ProtoWikiEventSerializer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

@Configuration
class ProducerKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrapServers: String,
    private val errorLogger: ProducerErrorLogger,
) {
    private fun baseProducerProps(): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ProducerConfig.ACKS_CONFIG to "all",
        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
        ProducerConfig.COMPRESSION_TYPE_CONFIG to "zstd",
    )

    @Bean
    fun wikiEventProducerFactory(): ProducerFactory<String, WikiEvent> =
        DefaultKafkaProducerFactory(
            baseProducerProps(),
            StringSerializer(),
            ProtoWikiEventSerializer(errorLogger),
        )

    @Bean
    fun kafkaTemplate(wikiEventProducerFactory: ProducerFactory<String, WikiEvent>): KafkaTemplate<String, WikiEvent> =
        KafkaTemplate(wikiEventProducerFactory)
}
