package com.redspace.wikistreamkotlin.controller

import com.redspace.wikistreamkotlin.controller.dto.*
import com.redspace.wikistreamkotlin.service.AuthService
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auth")
@Validated
@ConditionalOnProperty(name = ["app.auth.enabled"], havingValue = "true", matchIfMissing = true)
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun register(@Valid @RequestBody request: RegisterRequest): RegisterResponse =
        authService.register(request)

    @PostMapping("/login")
    suspend fun login(@Valid @RequestBody request: LoginRequest): TokenResponse = authService.login(request)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.OK)
    suspend fun logout(@AuthenticationPrincipal jwt: Jwt): LogoutResponse {
        authService.logout(jwt)
        return LogoutResponse(message = "Logged out successfully")
    }
}
