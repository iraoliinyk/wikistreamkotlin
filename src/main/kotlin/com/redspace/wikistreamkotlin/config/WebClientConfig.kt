package com.redspace.wikistreamkotlin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

@Configuration
class WebClientConfig {

    @Bean
    fun webClient(): WebClient = WebClient.create()

    @Bean fun objectMapper() = ObjectMapper()
}
