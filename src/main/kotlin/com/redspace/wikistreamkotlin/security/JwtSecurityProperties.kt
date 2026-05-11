package com.redspace.wikistreamkotlin.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.security.jwt")
data class JwtSecurityProperties(
    val issuer: String = "default-issuer",
    val secret: String = "default-secret",
    val accessTokenTtlSeconds: Long = 42L
)
