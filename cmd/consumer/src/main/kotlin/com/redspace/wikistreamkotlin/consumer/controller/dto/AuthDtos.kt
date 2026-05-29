package com.redspace.wikistreamkotlin.consumer.controller.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class RegisterRequest(
    @field:Email
    @field:NotBlank
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
)

data class RegisterResponse(
    val email: String,
    val createdAt: Instant,
)

data class LoginRequest(
    @field:Email
    @field:NotBlank
    val email: String,
    @field:NotBlank
    val password: String,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

data class LogoutResponse(
    val message: String,
)
