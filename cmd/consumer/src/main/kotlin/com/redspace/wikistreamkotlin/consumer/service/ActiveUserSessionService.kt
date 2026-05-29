package com.redspace.wikistreamkotlin.consumer.service

import com.redspace.wikistreamkotlin.consumer.repository.SessionRepository
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ActiveUserSessionService(
    private val sessionRepository: SessionRepository,
    private val jwtSecurityProperties: JwtSecurityProperties,
    @Value($$"${app.instance-id:${HOSTNAME:unknown}}") private val instanceId: String,
) {
    fun markLoggedIn(
        email: String,
        sessionMetadata: Map<String, String> = emptyMap(),
    ) {
        sessionRepository.markLoggedIn(email, buildSessionMetadata(sessionMetadata))
    }

    fun markLoggedOut(
        email: String?,
        sessionId: String? = null,
    ) {
        if (email.isNullOrBlank()) {
            return
        }
        sessionRepository.markLoggedOut(email, sessionId?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun listActiveUsers(): Set<String> = sessionRepository.listActiveEmails()

    private fun buildSessionMetadata(additionalMetadata: Map<String, String>): Map<String, String> {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(jwtSecurityProperties.accessTokenTtlSeconds)

        val baseMetadata =
            mapOf(
                SessionRepository.SessionMetadataKeys.LOGIN_AT to now.toString(),
                SessionRepository.SessionMetadataKeys.EXPIRES_AT to expiresAt.toString(),
                SessionRepository.SessionMetadataKeys.INSTANCE_ID to instanceId,
                SessionRepository.SessionMetadataKeys.SOURCE to DEFAULT_SOURCE,
            )

        return baseMetadata +
            additionalMetadata
                .filterKeys { it in SUPPORTED_SESSION_METADATA_KEYS }
    }

    companion object {
        private const val DEFAULT_SOURCE = "backend"

        private val SUPPORTED_SESSION_METADATA_KEYS =
            setOf(
                SessionRepository.SessionMetadataKeys.JTI,
                SessionRepository.SessionMetadataKeys.LOGIN_AT,
                SessionRepository.SessionMetadataKeys.EXPIRES_AT,
                SessionRepository.SessionMetadataKeys.INSTANCE_ID,
                SessionRepository.SessionMetadataKeys.CLIENT_IP,
                SessionRepository.SessionMetadataKeys.USER_AGENT,
                SessionRepository.SessionMetadataKeys.DEVICE_ID,
                SessionRepository.SessionMetadataKeys.SOURCE,
            )
    }
}