package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.UserAccount
import org.springframework.data.cassandra.repository.CassandraRepository

interface UserAccountCassandraRepository : CassandraRepository<UserAccount, String>

