package com.redspace.wikistreamkotlin.consumer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.consumer.serializer.ProtoWikiEventDeserializer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
class ConsumerKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers:localhost:19092}") private val bootstrapServers: String,
    @Value("\${spring.kafka.consumer.group-id:wiki-consumer}") private val groupId: String,
    @Value("\${spring.kafka.consumer.max-poll-records:50}") private val maxPollRecords: Int,
) {
    @Bean
    fun consumerFactory(): ConsumerFactory<String, WikiEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to maxPollRecords,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ProtoWikiEventDeserializer::class.java,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    /**
     * Batch listener container factory referenced by [RedpandaBatchConsumer].
     * Manual acknowledgement is required so offsets are committed only after
     * the entire batch has been processed successfully.
     */
    @Bean
    fun batchKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, WikiEvent>,
        kafkaErrorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, WikiEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, WikiEvent>()
        factory.setConsumerFactory(consumerFactory)
        factory.setBatchListener(true)
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
        factory.setCommonErrorHandler(kafkaErrorHandler)
        return factory
    }
}
