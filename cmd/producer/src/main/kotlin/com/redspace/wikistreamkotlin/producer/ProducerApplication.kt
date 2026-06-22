package com.redspace.wikistreamkotlin.producer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackages = ["com.redspace.wikistreamkotlin"])
class ProducerApplication

fun main(args: Array<String>) {
    runApplication<ProducerApplication>(*args)
}

