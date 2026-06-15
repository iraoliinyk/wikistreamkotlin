package com.redspace.wikistreamkotlin.consumer.config

import com.datastax.oss.driver.api.core.CqlSession
import com.redspace.wikistreamkotlin.consumer.repository.UserAccountAtomicRepositoryImpl
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Registers [com.redspace.wikistreamkotlin.consumer.repository.UserAccountAtomicRepositoryImpl] as a bean.
 *
 * Declared as an autoconfiguration (after [org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration]) 
 * to ensure the Cassandra session bean has already been registered.
 *
 * The bean creation depends on CqlSession being available as a constructor parameter, which will
 * fail if Cassandra is not configured, preventing the application from starting if Cassandra is required.
 */
@AutoConfiguration(after = [CassandraAutoConfiguration::class])
@ConditionalOnProperty(name = ["app.auth.enabled"], havingValue = "true", matchIfMissing = true)
class UserAccountAtomicRepositoryAutoConfiguration {
    @Bean
    fun userAccountAtomicRepository(cqlSession: CqlSession) = UserAccountAtomicRepositoryImpl(cqlSession)
}
