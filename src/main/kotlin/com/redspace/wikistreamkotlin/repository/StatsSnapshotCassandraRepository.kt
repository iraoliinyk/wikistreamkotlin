package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.StatsSnapshot
import org.springframework.data.cassandra.repository.CassandraRepository

interface StatsSnapshotCassandraRepository : CassandraRepository<StatsSnapshot, String>

