package com.redspace.wikistreamkotlin.consumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
@ComponentScan(basePackages = ["com.redspace.wikistreamkotlin"])
class ConsumerApplication

fun main(args: Array<String>) {
    runApplication<ConsumerApplication>(*args)
}

