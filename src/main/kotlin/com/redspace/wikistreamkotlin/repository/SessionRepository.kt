package com.redspace.wikistreamkotlin.repository

/**
 * Repository for managing active user sessions.
 *
 * Implementations can use Redis, in-memory store, or any other backend.
 */
interface SessionRepository {
    /**
     * Marks a user as logged in.
     *
     * @param email normalized user email.
     * @param sessionMetadata optional session metadata. Implementations may persist all or a subset of keys.
     * Recommended keys are declared in [SessionMetadataKeys]:
     * - [SessionMetadataKeys.JTI] JWT ID for logout/revocation tracing.
     * - [SessionMetadataKeys.LOGIN_AT] Login timestamp in ISO-8601 format.
     * - [SessionMetadataKeys.EXPIRES_AT] Token expiration timestamp in ISO-8601 format.
     * - [SessionMetadataKeys.INSTANCE_ID] Pod/container id for cross-instance debugging.
     * - [SessionMetadataKeys.CLIENT_IP] Client IP for security auditing.
     * - [SessionMetadataKeys.USER_AGENT] Client user agent for audit/fraud analysis.
     * - [SessionMetadataKeys.DEVICE_ID] Device identifier for multi-device sessions.
     * - [SessionMetadataKeys.SOURCE] Client source such as web/mobile/postman.
     */
    fun markLoggedIn(
        email: String,
        sessionMetadata: Map<String, String> = emptyMap(),
    )

    /**
     * Marks a user as logged out.
     *
     * @param email normalized user email.
     * @param sessionId optional session identifier (typically JWT `jti`). When present,
     * only that session should be removed. When absent, implementations may clear all
     * sessions for the email.
     */
    fun markLoggedOut(
        email: String?,
        sessionId: String? = null,
    )

    /**
     * Checks whether a user currently has an active session.
     */
    fun isActive(email: String): Boolean

    /**
     * Returns all active user emails.
     */
    fun listActiveEmails(): Set<String>

    /**
     * Canonical session metadata keys used across the authentication/session flow.
     */
    object SessionMetadataKeys {
        const val JTI = "jti"
        const val LOGIN_AT = "loginAt"
        const val EXPIRES_AT = "expiresAt"
        const val INSTANCE_ID = "instanceId"
        const val CLIENT_IP = "clientIp"
        const val USER_AGENT = "userAgent"
        const val DEVICE_ID = "deviceId"
        const val SOURCE = "source"
    }
}
