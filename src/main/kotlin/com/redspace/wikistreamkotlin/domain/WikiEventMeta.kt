package com.redspace.wikistreamkotlin.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class WikiEventMeta(
    val uri: String?,
    @JsonProperty("request_id") val requestId: String?,
    val id: String,
    val domain: String?,
    val stream: String?,
    val dt: String?,
    val topic: String?,
    val partition: String?,
    val offset: Long?,
)
