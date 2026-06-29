package com.redspace.wikistreamkotlin.consumer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.consumer.serializer.ProtoWikiEventDeserializer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
class ConsumerKafkaConfig(
    @Value("\${spring.kafka.bootstrap-servers:localhost:19092}") private val bootstrapServers: String,
    @Value("\${spring.kafka.consumer.group-id:wiki-consumer}") private val groupId: String,
    @Value("\${spring.kafka.consumer.max-poll-records:500}") private val maxPollRecords: Int,
    @Value("\${app.kafka.consumer.concurrency:4}") private val concurrency: Int
) {

    @Bean
    fun kafkaAdmin(): KafkaAdmin {
        return KafkaAdmin(mapOf(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers
        ))
    }

    /**
     * Create topic with replication factor 3 for production resilience.
     */
    @Bean
    fun protoTopic(): NewTopic {
        return NewTopic(
            "proto",
            6,  // Number of partitions (tune based on throughput needs)
            3   // Replication factor 3 for high availability
        ).apply {
            configs(mapOf(
                "min.insync.replicas" to "2",        // Require 2 replicas to acknowledge
                "unclean.leader.election.enable" to "false", // Prevent data loss
                "retention.ms" to "604800000",        // 7 days retention
                "compression.type" to "snappy"        // Compression for efficiency
            ))
        }
    }

    @Bean
    fun consumerFactory(): ConsumerFactory<String, WikiEvent> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to groupId,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to maxPollRecords,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 30_0000, // 5 minutes
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