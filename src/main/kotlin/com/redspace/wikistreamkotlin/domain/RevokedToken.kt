package com.redspace.wikistreamkotlin.domain

import org.springframework.data.cassandra.core.mapping.Column
import org.springframework.data.cassandra.core.mapping.PrimaryKey
import org.springframework.data.cassandra.core.mapping.Table
import java.time.Instant

@Table("revoked_tokens")
data class RevokedToken(
    @PrimaryKey
    val jti: String,
    @Column("email")
    val email: String,
    @Column("expires_at")
    val expiresAt: Instant,
    @Column("revoked_at")
    val revokedAt: Instant = Instant.now()
)
