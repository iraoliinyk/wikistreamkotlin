package com.redspace.wikistreamkotlin

import com.redspace.wikistreamkotlin.testsupport.NoOpStatsSnapshotCassandraRepositoryConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [WikistreamkotlinApplication::class, NoOpStatsSnapshotCassandraRepositoryConfig::class])
class WikistreamkotlinApplicationTests {
    @Test
    @Suppress("EmptyFunctionBlock")
    fun contextLoads() {
    }
}
