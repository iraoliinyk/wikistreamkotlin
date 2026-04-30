package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.RevokedToken
import org.springframework.data.cassandra.repository.CassandraRepository

interface RevokedTokenCassandraRepository : CassandraRepository<RevokedToken, String>

