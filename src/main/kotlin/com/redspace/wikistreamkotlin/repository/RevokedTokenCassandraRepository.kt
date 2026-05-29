package com.redspace.wikistreamkotlin.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.redspace.wikistreamkotlin.domain.RevokedToken
import org.springframework.data.cassandra.repository.CassandraRepository

interface RevokedTokenCassandraRepository :
    CassandraRepository<RevokedToken, String>,
    RevokedTokenTtlRepository

interface RevokedTokenTtlRepository {
    fun saveWithTtl(
        token: RevokedToken,
        ttlSeconds: Int,
    ): RevokedToken
}

class RevokedTokenTtlRepositoryImpl(
    private val cqlSession: CqlSession,
) : RevokedTokenTtlRepository {
    private val insertWithTtlStatement: PreparedStatement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        cqlSession.prepare(
            """
            INSERT INTO revoked_tokens (jti, email, expires_at, revoked_at)
            VALUES (?, ?, ?, ?)
            USING TTL ?
            """.trimIndent(),
        )
    }

    @Suppress("MagicNumber")
    override fun saveWithTtl(
        token: RevokedToken,
        ttlSeconds: Int,
    ): RevokedToken {
        val normalizedTtl = ttlSeconds.coerceAtLeast(1)
        val bound =
            insertWithTtlStatement
                .boundStatementBuilder()
                .setString(0, token.jti)
                .setString(1, token.email)
                .setInstant(2, token.expiresAt)
                .setInstant(3, token.revokedAt)
                .setInt(4, normalizedTtl)
                .build()
        cqlSession.execute(bound)
        return token
    }
}
