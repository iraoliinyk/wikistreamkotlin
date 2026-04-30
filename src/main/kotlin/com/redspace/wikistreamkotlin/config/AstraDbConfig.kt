package com.redspace.wikistreamkotlin.config

import org.springframework.boot.cassandra.autoconfigure.CqlSessionBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
class AstraDbConfig {

    @Bean
    fun astraCqlSessionBuilderCustomizer(properties: AstraDbProperties): CqlSessionBuilderCustomizer {
        return CqlSessionBuilderCustomizer { builder ->
            if (!properties.enabled) {
                return@CqlSessionBuilderCustomizer
            }

            val secureConnectBundle = properties.secureConnectBundle
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: throw IllegalStateException(
                    "astra.db.secure-connect-bundle must be configured when the astra profile is enabled"
                )

            builder.withCloudSecureConnectBundle(secureConnectBundle)

            when {
                !properties.token.isNullOrBlank() -> builder.withAuthCredentials("token", properties.token)
                !properties.clientId.isNullOrBlank() && !properties.clientSecret.isNullOrBlank() -> {
                    builder.withAuthCredentials(properties.clientId, properties.clientSecret)
                }
                else -> throw IllegalStateException(
                    "Provide either astra.db.token or astra.db.client-id + astra.db.client-secret"
                )
            }
        }
    }
}

