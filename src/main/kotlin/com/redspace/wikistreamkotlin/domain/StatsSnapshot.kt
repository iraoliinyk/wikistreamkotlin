package com.redspace.wikistreamkotlin.domain

data class StatsSnapshot(
    val id: String,
    val totalMessages: Int = 0,
    val distinctUsers: Int = 0,
    val botCount: Int = 0,
    val nonBotCount: Int = 0,
    val countByServerUrl: Map<String, Int> = emptyMap()
)
