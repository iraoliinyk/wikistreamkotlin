package com.redspace.wikistreamkotlin.controller

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.service.StatsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/")
class StatsController(private val statsService: StatsService) {
    @GetMapping("/stats")
    suspend fun getStats(): StatsSnapshot = statsService.getSnapshot()

    @GetMapping("/status")
    fun getStatus() = mapOf("status" to "ok")
}