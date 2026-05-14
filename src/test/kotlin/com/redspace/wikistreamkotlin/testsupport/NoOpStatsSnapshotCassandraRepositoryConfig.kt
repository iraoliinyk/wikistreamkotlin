package com.redspace.wikistreamkotlin.testsupport

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.repository.StatsSnapshotCassandraRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Optional

@TestConfiguration
class NoOpStatsSnapshotCassandraRepositoryConfig {
    @Bean
    fun statsSnapshotCassandraRepository(): StatsSnapshotCassandraRepository {
        val proxy =
            Proxy.newProxyInstance(
                StatsSnapshotCassandraRepository::class.java.classLoader,
                arrayOf(StatsSnapshotCassandraRepository::class.java),
                NoOpInvocationHandler,
            )
        return proxy as StatsSnapshotCassandraRepository
    }

    private object NoOpInvocationHandler : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            when (method.name) {
                "toString" -> "NoOpStatsSnapshotCassandraRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "findById" -> Optional.empty<StatsSnapshot>()
                "save" -> args?.firstOrNull()
                "existsById" -> false
                "count" -> 0L
                "deleteAll", "deleteById", "delete", "flush" -> null
                else -> defaultValue(method.returnType)
            }

        private fun defaultValue(returnType: Class<*>): Any? =
            when (returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                Character.TYPE -> '\u0000'
                Void.TYPE -> null
                else -> null
            }
    }
}
