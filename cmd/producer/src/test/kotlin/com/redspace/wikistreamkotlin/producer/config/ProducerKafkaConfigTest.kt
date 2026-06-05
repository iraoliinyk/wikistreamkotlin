package com.redspace.wikistreamkotlin.producer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

class ProducerKafkaConfigTest {
    private val config = ProducerKafkaConfig("localhost:19092")

    @Test
    fun `producer props include idempotence retries and compression`() {
        val producerFactory = config.wikiEventProducerFactory() as DefaultKafkaProducerFactory<String, WikiEvent>
        val props = producerFactory.configurationProperties

        assertEquals("localhost:19092", props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG])
        assertEquals("all", props[ProducerConfig.ACKS_CONFIG])
        assertEquals(true, props[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG])
        assertEquals(Int.MAX_VALUE, props[ProducerConfig.RETRIES_CONFIG])
        assertEquals("zstd", props[ProducerConfig.COMPRESSION_TYPE_CONFIG])
    }

    @Test
    fun `serializer setup uses StringSerializer and JacksonJsonSerializer`() {
        val producerFactory = config.wikiEventProducerFactory() as DefaultKafkaProducerFactory<String, WikiEvent>
        val props = producerFactory.configurationProperties

        assertEquals(StringSerializer::class.java, props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG])
        assertEquals(JacksonJsonSerializer::class.java, props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG])
    }

    @Test
    fun `type headers are disabled`() {
        val producerFactory = config.wikiEventProducerFactory() as DefaultKafkaProducerFactory<String, WikiEvent>
        val props = producerFactory.configurationProperties

        assertFalse(props[JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS] as Boolean)
    }
}
