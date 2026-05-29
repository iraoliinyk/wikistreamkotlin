package com.redspace.wikistreamkotlin.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "astra.db")
data class AstraDbProperties(
    val enabled: Boolean = false,
    val secureConnectBundle: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val token: String? = null,
)
