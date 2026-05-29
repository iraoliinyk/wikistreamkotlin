package com.redspace.wikistreamkotlin.consumer.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.cassandra.core.mapping.Column
import org.springframework.data.cassandra.core.mapping.PrimaryKey
import org.springframework.data.cassandra.core.mapping.Table
import java.time.Instant

@Table("user_accounts")
data class UserAccount(
    @PrimaryKey
    val email: String,
    @JsonIgnore
    @Column("password_hash")
    val passwordHash: String,
    @Column("created_at")
    val createdAt: Instant = Instant.now(),
    @Column("updated_at")
    val updatedAt: Instant = Instant.now(),
    @Column("active")
    val active: Boolean = true,
)
