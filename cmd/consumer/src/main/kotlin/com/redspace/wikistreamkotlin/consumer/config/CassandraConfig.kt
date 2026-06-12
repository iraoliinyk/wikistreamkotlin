package com.redspace.wikistreamkotlin.consumer.config

import com.datastax.oss.driver.api.core.CqlSession
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.cassandra.core.CassandraTemplate
import org.springframework.data.cassandra.core.convert.CassandraConverter

@Configuration
@ConditionalOnBean(CqlSession::class)
class CassandraConfig {

    /**
     * Explicitly construct the blocking CassandraTemplate.
     * Spring Boot skips this by default in WebFlux apps, but our
     * blocking CassandraRepository interfaces require it.
     */
    @Bean
    fun cassandraTemplate(
        session: CqlSession,
        converter: CassandraConverter
    ): CassandraTemplate {
        return CassandraTemplate(session, converter)
    }
}