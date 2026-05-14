package com.redspace.wikistreamkotlin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WikistreamkotlinApplication

fun main(args: Array<String>) {
    runApplication<WikistreamkotlinApplication>(*args)
}
