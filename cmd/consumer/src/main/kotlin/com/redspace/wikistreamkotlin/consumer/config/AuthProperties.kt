package com.redspace.wikistreamkotlin.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.auth")
data class AuthProperties(
    val enabled: Boolean = true,
)
