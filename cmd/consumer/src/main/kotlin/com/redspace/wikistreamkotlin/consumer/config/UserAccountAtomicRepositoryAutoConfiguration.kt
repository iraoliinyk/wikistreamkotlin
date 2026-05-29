package com.redspace.wikistreamkotlin.consumer.config

import com.datastax.oss.driver.api.core.CqlSession
import com.redspace.wikistreamkotlin.consumer.repository.UserAccountAtomicRepositoryImpl
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Registers [com.redspace.wikistreamkotlin.consumer.repository.UserAccountAtomicRepositoryImpl] as a bean only when a [CqlSession] is available.
 *
 * Declared as an autoconfiguration (after [org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration]) so that the
 * [ConditionalOnBean] check on [CqlSession] is evaluated after the Cassandra session bean
 * has already been registered — something that is not guaranteed for component-scanned beans.
 *
 * This means:
 * - Tests that exclude Cassandra autoconfiguration (e.g. unit/smoke tests) get no bean,
 *   and the context loads without Cassandra.
 * - Integration tests and production, where [CqlSession] is present, get the bean automatically.
 */
@AutoConfiguration(after = [CassandraAutoConfiguration::class])
@ConditionalOnBean(CqlSession::class)
class UserAccountAtomicRepositoryAutoConfiguration {
    @Bean
    fun userAccountAtomicRepository(cqlSession: CqlSession) = UserAccountAtomicRepositoryImpl(cqlSession)
}
