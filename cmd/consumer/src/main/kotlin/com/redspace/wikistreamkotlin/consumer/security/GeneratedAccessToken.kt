package com.redspace.wikistreamkotlin.consumer.security

import com.redspace.wikistreamkotlin.consumer.controller.dto.TokenResponse


data class GeneratedAccessToken(
    val response: TokenResponse,
    val jti: String,
)
