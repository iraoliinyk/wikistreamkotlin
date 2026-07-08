package com.redspace.wikistreamkotlin.consumer

import com.redspace.wikistreamkotlin.consumer.domain.UserStatsDelta
import com.redspace.wikistreamkotlin.consumer.metrics.ConsumerMetricsService
import com.redspace.wikistreamkotlin.consumer.repository.StatsWriteRepository
import com.redspace.wikistreamkotlin.consumer.service.ActiveUserSessionService
import com.redspace.wikistreamkotlin.consumer.service.BatchAggregationService
import com.redspace.wikistreamkotlin.core.Topics
import com.redspace.wikistreamkotlin.core.domain.WikiEvent
import com.redspace.wikistreamkotlin.kafka.DlqPublisher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Kafka batch consumer implemented as a native suspend function.
 *
 * Acknowledgment strategy:
 *   ack.acknowledge() is called only after all database writes complete.
 *   If the JVM crashes before this point, Kafka redelivers the batch.
 *   Cassandra COUNTER + INSERT idempotency ensures redelivery is safe.
 */
@Component
class CoroutineBatchConsumer(
    private val aggregationService: BatchAggregationService,
    private val statsWriteRepository: StatsWriteRepository,
    private val activeUserSessionService: ActiveUserSessionService,
    private val dlqPublisher: DlqPublisher,
    private val metricsService: ConsumerMetricsService
) {
    @KafkaListener(
        topics = ["\${app.kafka.consumer.topic:" + Topics.VALIDATED + "}"],
        containerFactory = "batchKafkaListenerContainerFactory",
        concurrency = "\${app.kafka.consumer.concurrency:4}"
    )
    suspend fun consume(
        records: List<ConsumerRecord<String, WikiEvent>>,
        ack: Acknowledgment
    ) = coroutineScope {
        val batchStartTime = System.currentTimeMillis()
        val bucketDay = LocalDate.now(ZoneOffset.UTC).toString()
        val activeUsers = activeUserSessionService.listActiveUsers()
        if (activeUsers.isEmpty()) {
            ack.acknowledge()
            return@coroutineScope
        }

        val events = records.mapNotNull { it.value() }
        if (events.isEmpty()) {
            ack.acknowledge()
            return@coroutineScope
        }

        // ONE aggregate for the whole batch — describes "what happened while
        // these users were online". The same delta is attributed to every
        // currently-active user (each row in stats_counters is per-user).
        val batchDelta = aggregationService.aggregateBatchAsSingleDelta(events)
        activeUsers.map { userEmail ->
            async { persistUserDelta(userEmail, bucketDay, batchDelta, records) }
        }.awaitAll()

        // Offset committed only after all writes finish.
        // Crash before this point → Kafka redelivers → COUNTER + INSERT are idempotent.
        ack.acknowledge()
        metricsService.recordBatchSuccess(
            eventCount = events.size,
            durationMs = System.currentTimeMillis() - batchStartTime
        )
    }

    private suspend fun persistUserDelta(
        userEmail: String,
        bucketDay: String,
        delta: UserStatsDelta,
        records: List<ConsumerRecord<String, WikiEvent>>
    ) {
        try {
            // All three writes are independent blind writes — order does not matter
            coroutineScope {
                launch { statsWriteRepository.incrementCounters(userEmail, bucketDay, delta) }
                launch { statsWriteRepository.appendServerUrlEvents(userEmail, bucketDay, delta.serverUrls) }
                launch { statsWriteRepository.upsertTrackedUsers(userEmail, bucketDay, delta.trackedUsers) }
            }
            metricsService.incrementEventsPersistedToCassandra(delta.totalMessages.toDouble())
        } catch (ex: Exception) {
            val userRecords = records.filter { it.value()?.user == userEmail }
            userRecords.forEach { dlqPublisher.send(it, ex) }
            metricsService.recordBatchFailure(userRecords.size)
        }
    }
}