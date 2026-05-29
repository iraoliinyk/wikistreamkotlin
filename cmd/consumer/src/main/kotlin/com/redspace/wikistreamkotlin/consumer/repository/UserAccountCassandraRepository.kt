package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.UserAccount
import org.springframework.data.cassandra.repository.CassandraRepository

interface UserAccountCassandraRepository : CassandraRepository<UserAccount, String>
