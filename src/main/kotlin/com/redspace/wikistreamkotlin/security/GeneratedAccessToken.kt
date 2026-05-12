package com.redspace.wikistreamkotlin.security

import com.redspace.wikistreamkotlin.controller.dto.TokenResponse

data class GeneratedAccessToken(
    val response: TokenResponse,
    val jti: String,
)

