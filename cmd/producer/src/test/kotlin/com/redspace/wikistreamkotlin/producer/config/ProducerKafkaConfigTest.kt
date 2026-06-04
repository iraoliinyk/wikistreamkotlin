package com.redspace.wikistreamkotlin.producer.config

import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.producer.exception.ProducerErrorLogger
import com.redspace.wikistreamkotlin.producer.serializer.ProtoWikiEventSerializer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaProducerFactory

class ProducerKafkaConfigTest {
    private val config = ProducerKafkaConfig("localhost:19092", ProducerErrorLogger())

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
    fun `serializer setup uses StringSerializer and ProtoWikiEventSerializer`() {
        val producerFactory = config.wikiEventProducerFactory() as DefaultKafkaProducerFactory<String, WikiEvent>
        val props = producerFactory.configurationProperties

        assertNull(props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG])
        assertNull(props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG])
        assertEquals(StringSerializer::class.java, producerFactory.keySerializerSupplier!!.get()!!::class.java)
        assertEquals(ProtoWikiEventSerializer::class.java, producerFactory.valueSerializerSupplier!!.get()!!::class.java)
    }
}
