package com.redspace.wikistreamkotlin

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import com.redspace.wikistreamkotlin.testsupport.NoOpStatsSnapshotCassandraRepositoryConfig

@SpringBootTest(classes = [WikistreamkotlinApplication::class, NoOpStatsSnapshotCassandraRepositoryConfig::class])
class WikistreamkotlinApplicationTests {


	@Test
	fun contextLoads() {
	}

}
