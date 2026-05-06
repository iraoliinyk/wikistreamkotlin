package com.redspace.wikistreamkotlin.controller

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import com.redspace.wikistreamkotlin.exception.AuthValidationError
import com.redspace.wikistreamkotlin.exception.InvalidCredentialsError
import com.redspace.wikistreamkotlin.service.StatsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class StatsController(private val statsService: StatsService) {
    @GetMapping("/stats")
    suspend fun getStats(@AuthenticationPrincipal jwt: Jwt): StatsSnapshot {
        val userEmail = jwt.subject?.takeIf { it.isNotBlank() }
            ?: throw InvalidCredentialsError("JWT subject is missing")
        return statsService.getSnapshotForUser(userEmail)
    }

    @GetMapping("/status")
    fun getStatus() = mapOf("status" to "ok")
}