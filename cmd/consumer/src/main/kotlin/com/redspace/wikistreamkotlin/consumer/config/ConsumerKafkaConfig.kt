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
    @Value("\${spring.kafka.consumer.max-poll-records:500}") private val maxPollRecords: Int,
    @Value("\${app.kafka.consumer.concurrency:4}") private val concurrency: Int
) {

    // Topic creation is owned by `redpanda-init` (docker compose) / `redpanda-init-job.yaml`
    // (k8s), not by the consumer application. The previous `protoTopic()` / `kafkaAdmin()`
    // beans created a stray "proto" topic with RF=3 (unsatisfiable on single-broker Redpanda)
    // and have been removed accordingly.

    @Bean
    fun consumerFactory(): ConsumerFactory<String, WikiEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to maxPollRecords,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 300_000, // 5 minutes
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to 30_000,    // 30 seconds
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 10_000, // 10 seconds
            ConsumerConfig.FETCH_MIN_BYTES_CONFIG to 1024,        // 1KB min fetch
            ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to 500,       // Max wait 500ms
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ProtoWikiEventDeserializer::class.java,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    fun batchKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, WikiEvent>,
        kafkaErrorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<String, WikiEvent> {
        return ConcurrentKafkaListenerContainerFactory<String, WikiEvent>().apply {
            setConsumerFactory(consumerFactory)
            setBatchListener(true)
            setConcurrency(concurrency) // Multiple consumer threads

            containerProperties.apply {
                ackMode = ContainerProperties.AckMode.MANUAL
            }

            setCommonErrorHandler(kafkaErrorHandler)
        }
    }
}