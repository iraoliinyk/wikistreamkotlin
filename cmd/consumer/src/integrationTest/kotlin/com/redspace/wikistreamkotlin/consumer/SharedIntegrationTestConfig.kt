package com.redspace.wikistreamkotlin.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Shared test configuration for integration tests.
 * Prevents duplicate bean definitions across multiple test classes.
 */
@TestConfiguration
class SharedIntegrationTestConfig {
    @Bean
    @Primary
    fun testObjectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
}
