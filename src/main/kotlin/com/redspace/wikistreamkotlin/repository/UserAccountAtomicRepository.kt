package com.redspace.wikistreamkotlin.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.redspace.wikistreamkotlin.domain.UserAccount

interface UserAccountAtomicRepository {
    fun insertIfNotExists(account: UserAccount): Boolean
}

class UserAccountAtomicRepositoryImpl(
    private val cqlSession: CqlSession
) : UserAccountAtomicRepository {
    private val insertIfNotExistsStmt: PreparedStatement = cqlSession.prepare(
        """        INSERT INTO user_accounts (email, password_hash, created_at, updated_at, active)        VALUES (?, ?, ?, ?, ?)        IF NOT EXISTS        """.trimIndent()
    )

    override fun insertIfNotExists(account: UserAccount): Boolean    {
        val bound = insertIfNotExistsStmt.boundStatementBuilder()
            .setString(0, account.email)
            .setString(1, account.passwordHash)
            .setInstant(2, account.createdAt)
            .setInstant(3, account.updatedAt)
            .setBoolean(4, account.active)
            .build()

        val row = cqlSession.execute(bound).one()
            ?: error("LWT insert did not return a row with [applied]")

        return row.getBoolean("[applied]")
    }

}