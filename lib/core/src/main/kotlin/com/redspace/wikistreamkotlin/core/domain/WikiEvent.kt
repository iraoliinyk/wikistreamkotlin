package com.redspace.wikistreamkotlin.core.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class WikiEvent(
    val schema: String?,
    val meta: WikiEventMeta?,
    val id: Long?,
    val type: String?,
    val namespace: Int?,
    val title: String?,
    @JsonProperty("title_url") val titleUrl: String?,
    val comment: String?,
    val timestamp: Long?,
    val user: String?,
    val bot: Boolean?,
    @JsonProperty("notify_url") val notifyUrl: String?,
    @JsonProperty("server_url") val serverUrl: String?,
    @JsonProperty("server_name") val serverName: String?,
    @JsonProperty("server_script_path") val serverScriptPath: String?,
    val wiki: String?,
    @JsonProperty("parsedcomment") val parsedComment: String?,
)
