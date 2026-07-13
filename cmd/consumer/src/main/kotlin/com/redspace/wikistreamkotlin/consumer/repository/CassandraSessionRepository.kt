package com.redspace.wikistreamkotlin.consumer.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.redspace.wikistreamkotlin.consumer.security.JwtSecurityProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Cassandra-backed [SessionRepository] (replaces RedisSessionRepository).
 *
 * Storage model: single synthetic partition (bucket='all') in `active_sessions`,
 * one row per (email, session_id). Rows expire via per-INSERT `USING TTL` bound
 * to the JWT expiry, so there is no cleanup path — expiry IS the cleanup.
 *
 * listActiveEmails() reads the whole partition at LOCAL_ONE behind a short
 * in-process cache (app.session.cache-ttl-ms, 0 = disabled). All instances read
 * the same table, so the cache introduces at most cache-ttl of cross-instance
 * staleness while remaining multi-instance-correct.
 */
@Repository
class CassandraSessionRepository(
    private val cqlSession: CqlSession,
    private val jwtSecurityProperties: JwtSecurityProperties,
    @Value("\${app.session.cache-ttl-ms:1000}") private val cacheTtlMs: Long,
) : SessionRepository {

    private data class CachedEmails(val emails: Set<String>, val loadedAtMillis: Long)

    private val cachedEmails = AtomicReference<CachedEmails?>(null)

    private val insertSession: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            """
            INSERT INTO active_sessions
                (bucket, email, session_id, login_at, expires_at,
                 instance_id, client_ip, user_agent, device_id, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            USING TTL ?
            """.trimIndent(),
        )
    }

    private val deleteOneSession: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            "DELETE FROM active_sessions WHERE bucket = ? AND email = ? AND session_id = ?",
        )
    }

    private val deleteAllSessions: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            "DELETE FROM active_sessions WHERE bucket = ? AND email = ?",
        )
    }

    private val selectOneSession: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            "SELECT session_id FROM active_sessions WHERE bucket = ? AND email = ? LIMIT 1",
        )
    }

    private val selectAllEmails: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            "SELECT email FROM active_sessions WHERE bucket = ?",
        )
    }

    override fun markLoggedIn(
        email: String,
        sessionMetadata: Map<String, String>,
    ) {
        val now = Instant.now()
        val sessionId =
            sessionMetadata[SessionRepository.SessionMetadataKeys.JTI]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "$ANONYMOUS_SESSION_PREFIX${UUID.randomUUID()}"
        val expiresAt =
            sessionMetadata[SessionRepository.SessionMetadataKeys.EXPIRES_AT]
                ?.let(Instant::parse)
                ?: now.plusSeconds(jwtSecurityProperties.accessTokenTtlSeconds)
        val loginAt =
            sessionMetadata[SessionRepository.SessionMetadataKeys.LOGIN_AT]
                ?.let(Instant::parse)
                ?: now
        val ttlSeconds =
            Duration.between(now, expiresAt).seconds
                .coerceIn(1, Int.MAX_VALUE.toLong())
                .toInt()

        val bound =
            insertSession.boundStatementBuilder()
                .setString(0, BUCKET)
                .setString(1, email)
                .setString(2, sessionId)
                .setInstant(3, loginAt)
                .setInstant(4, expiresAt)
                .setString(5, sessionMetadata[SessionRepository.SessionMetadataKeys.INSTANCE_ID])
                .setString(6, sessionMetadata[SessionRepository.SessionMetadataKeys.CLIENT_IP])
                .setString(7, sessionMetadata[SessionRepository.SessionMetadataKeys.USER_AGENT])
                .setString(8, sessionMetadata[SessionRepository.SessionMetadataKeys.DEVICE_ID])
                .setString(9, sessionMetadata[SessionRepository.SessionMetadataKeys.SOURCE])
                .setInt(10, ttlSeconds)
                .build()
        cqlSession.execute(bound)
        cachedEmails.set(null)
    }

    override fun markLoggedOut(
        email: String?,
        sessionId: String?,
    ) {
        if (email.isNullOrBlank()) return
        val normalizedSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }

        val bound =
            if (normalizedSessionId == null) {
                deleteAllSessions.boundStatementBuilder()
                    .setString(0, BUCKET)
                    .setString(1, email)
                    .build()
            } else {
                deleteOneSession.boundStatementBuilder()
                    .setString(0, BUCKET)
                    .setString(1, email)
                    .setString(2, normalizedSessionId)
                    .build()
            }
        cqlSession.execute(bound)
        cachedEmails.set(null)
    }

    override fun isActive(email: String): Boolean {
        val bound =
            selectOneSession.boundStatementBuilder()
                .setString(0, BUCKET)
                .setString(1, email)
                .build()
        return cqlSession.execute(bound).one() != null
    }

    override fun listActiveEmails(): Set<String> {
        if (cacheTtlMs > 0) {
            cachedEmails.get()
                ?.takeIf { System.currentTimeMillis() - it.loadedAtMillis < cacheTtlMs }
                ?.let { return it.emails }
        }

        val bound =
            selectAllEmails.boundStatementBuilder()
                .setString(0, BUCKET)
                // Stale-tolerant snapshot read on the hot path: LOCAL_ONE removes
                // the read amplification of the keyspace default LOCAL_QUORUM.
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
                .build()

        // ResultSet is Iterable<Row> and pages transparently — this handles any
        // number of sessions without the SCAN-cursor logic Redis needed.
        val emails =
            cqlSession.execute(bound)
                .mapNotNull { it.getString("email") }
                .toSet()

        if (cacheTtlMs > 0) {
            cachedEmails.set(CachedEmails(emails, System.currentTimeMillis()))
        }
        return emails
    }

    companion object {
        private const val BUCKET = "all"
        private const val ANONYMOUS_SESSION_PREFIX = "anon-"
    }
}