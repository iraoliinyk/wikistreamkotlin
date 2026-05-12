package com.redspace.wikistreamkotlin.controller

import com.redspace.wikistreamkotlin.controller.dto.StandaloneStatsSeedRequest
import com.redspace.wikistreamkotlin.controller.dto.StandaloneStatsSeedResponse
import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.repository.SessionRepository
import com.redspace.wikistreamkotlin.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.service.StatsService
import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/v1/local")
@Profile("standalone")
class StandaloneLocalStatsController(
    private val activeUserSessionService: ActiveUserSessionService,
    private val statsService: StatsService,
) {

    @PostMapping("/stats/seed")
    suspend fun seedStats(@Valid @RequestBody request: StandaloneStatsSeedRequest): StandaloneStatsSeedResponse {
        val metadata = mapOf(
            SessionRepository.SessionMetadataKeys.JTI to "standalone-local-${request.email}-${Instant.now().epochSecond}",
            SessionRepository.SessionMetadataKeys.USER_AGENT to "Postman/Standalone",
            SessionRepository.SessionMetadataKeys.DEVICE_ID to "standalone-local-device",
            SessionRepository.SessionMetadataKeys.CLIENT_IP to "127.0.0.1",
            SessionRepository.SessionMetadataKeys.SOURCE to "standalone",
        )

        activeUserSessionService.markLoggedIn(request.email, metadata)

        repeat(request.eventsCount) {
            statsService.recordForActiveUsers(
                WikiEvent(
                    schema = "https://schema.example/wiki",
                    meta = null,
                    id = Instant.now().toEpochMilli(),
                    type = "edit",
                    namespace = 0,
                    title = "Standalone local stats seed",
                    titleUrl = request.serverUrl,
                    comment = "Seeded locally for /v1/stats testing",
                    timestamp = Instant.now().epochSecond,
                    user = request.user,
                    bot = request.bot,
                    notifyUrl = null,
                    serverUrl = request.serverUrl,
                    serverName = "local",
                    serverScriptPath = "/wiki",
                    wiki = "localwiki",
                    parsedComment = "Seeded locally for /v1/stats testing",
                )
            )
        }

        val snapshot = statsService.getSnapshotForUser(request.email)
        return StandaloneStatsSeedResponse(
            email = request.email,
            eventsCount = request.eventsCount,
            snapshotId = snapshot.id,
            totalMessages = snapshot.totalMessages,
            distinctUsers = snapshot.distinctUsers,
            botCount = snapshot.botCount,
            nonBotCount = snapshot.nonBotCount,
            countByServerUrl = snapshot.countByServerUrl,
        )
    }
}

