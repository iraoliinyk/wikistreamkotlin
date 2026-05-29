package com.redspace.wikistreamkotlin.producer.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "wiki.stream")
data class WikiStreamProperties(
    val url: String,
    val userAgent: String,
)