package com.redspace.wikistreamkotlin.consumer.repository

import com.redspace.wikistreamkotlin.consumer.domain.StatsSnapshot
import org.springframework.data.cassandra.repository.CassandraRepository

interface StatsSnapshotCassandraRepository : CassandraRepository<StatsSnapshot, String>