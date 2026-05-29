package com.redspace.wikistreamkotlin.producer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ProducerApplication

fun main(args: Array<String>) {
    runApplication<ProducerApplication>(*args)
}

