# Redpanda Practical Implementation Guide

This guide turns the migration strategy into an actionable build plan for the Kotlin/Spring application.

## Goal
- [ ] Split the current application into a producer and a consumer.
- [ ] Use Redpanda as the durable message backbone between them.
- [ ] Keep the implementation incremental so you can verify each stage before the next.

---

## 1) Confirm the target architecture
- [ ] Producer reads Wikimedia stream events.
- [ ] Producer publishes normalized events to Redpanda.
- [ ] Consumer reads only from Redpanda.
- [ ] Consumer processes records in batches and persists results.
- [ ] Shared contracts live in one core module.

### Ownership matrix
| Area | Producer | Consumer | Shared Core | Infra |
|---|---|---|---|---|
| Wikimedia stream intake | ✅ | ❌ | ❌ | ❌ |
| Publish to Redpanda | ✅ | ❌ | ❌ | ❌ |
| Consume from Redpanda | ❌ | ✅ | ❌ | ❌ |
| Batch processing | ❌ | ✅ | ❌ | ❌ |
| Persistence | ❌ | ✅ | ❌ | ❌ |
| DTOs / event envelopes | ❌ | ❌ | ✅ | ❌ |
| Docker Compose / brokers | ❌ | ❌ | ❌ | ✅ |

### Acceptance criteria
- [ ] No consumer code reads Wikimedia directly.
- [ ] No producer code writes to Cassandra.
- [ ] Topic names and event schema are centralized.

---

## 2) Restructure the repository
- [ ] Convert the project into a multi-module Gradle build.
- [ ] Create `cmd/producer` for the producer application.
- [ ] Create `cmd/consumer` for the consumer application.
- [ ] Create `lib/core` for shared contracts and helpers.

### Suggested tree
```text
settings.gradle.kts
build.gradle.kts
cmd/
  producer/
    build.gradle.kts
    src/main/kotlin/...
  consumer/
    build.gradle.kts
    src/main/kotlin/...
lib/
  core/
    build.gradle.kts
    src/main/kotlin/...
```

### `settings.gradle.kts`
```kotlin
rootProject.name = "wikistreamkotlin"

include(
    ":cmd:producer",
    ":cmd:consumer",
    ":lib:core",
)
```

### Acceptance criteria
- [ ] `./gradlew projects` shows all three modules.
- [ ] Each module can compile independently.

---

## 3) Define shared contracts in `lib/core`
- [ ] Add event DTOs.
- [ ] Add topic constants.
- [ ] Add schema/version envelope.
- [ ] Add common serialization helpers.

### Example topic constants
```kotlin
object Topics {
    const val RAW = "wiki.recentchange.raw"
    const val DLQ = "wiki.recentchange.dlq"
}
```

### Example event envelope
```kotlin
data class EventEnvelope<T>(
    val schemaVersion: Int,
    val eventType: String,
    val payload: T,
)
```

### Acceptance criteria
- [ ] Producer and consumer both compile against the same shared contract.
- [ ] Schema changes happen in one place.

---

## 4) Add Redpanda-compatible Kotlin messaging support
- [ ] Use `spring-kafka` for the Kafka protocol layer.
- [ ] Point `bootstrap.servers` to Redpanda.
- [ ] Use Apache Kafka client only if low-level control is needed.

### Producer dependency example
```kotlin
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
}
```

### Consumer dependency example
```kotlin
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
}
```

### Acceptance criteria
- [ ] Producer can send to Redpanda in a local environment.
- [ ] Consumer can read from Redpanda in a local environment.

---

## 5) Implement the producer
- [ ] Read Wikimedia stream events.
- [ ] Parse and normalize incoming records.
- [ ] Publish to Redpanda.
- [ ] Add retry and idempotence settings.
- [ ] Flush cleanly on shutdown.

### Suggested producer flow
1. [ ] Connect to Wikimedia stream.
2. [ ] Parse raw event.
3. [ ] Map to shared event model.
4. [ ] Serialize payload.
5. [ ] Publish to `wiki.recentchange.raw`.
6. [ ] Flush and close on shutdown.

### Example producer config
```kotlin
@Configuration
class ProducerKafkaConfig {
    @Bean
    fun producerFactory(): ProducerFactory<String, String> {
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "redpanda:9092",
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
            ProducerConfig.COMPRESSION_TYPE_CONFIG to "zstd",
        )
        return DefaultKafkaProducerFactory(props)
    }
}
```

### Example producer service
```kotlin
class WikiEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    fun publish(key: String, payload: String) {
        kafkaTemplate.send(Topics.RAW, key, payload)
    }
}
```

### Acceptance criteria
- [ ] A test message from the producer appears in Redpanda.
- [ ] Producer shutdown flushes in-flight records.

---

## 6) Implement the consumer
- [ ] Read only from Redpanda.
- [ ] Process multiple records per batch.
- [ ] Persist results after successful batch processing.
- [ ] Commit offsets only after the batch succeeds.
- [ ] Send poison messages to DLQ.

### Suggested consumer flow
1. [ ] Poll a batch of records.
2. [ ] Process the entire batch.
3. [ ] Persist the derived state.
4. [ ] Commit offsets only if processing succeeds.
5. [ ] Send failed records to `wiki.recentchange.dlq` if unrecoverable.

### Example batch listener config
```kotlin
@Configuration
class ConsumerKafkaConfig {
    @Bean
    fun consumerFactory(): ConsumerFactory<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "redpanda:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "wiki-consumer",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 50,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        return DefaultKafkaConsumerFactory(props)
    }
}
```

### Example batch listener
```kotlin
@KafkaListener(topics = [Topics.RAW], containerFactory = "batchKafkaListenerContainerFactory")
fun consume(records: List<ConsumerRecord<String, String>>, ack: Acknowledgment) {
    try {
        processBatch(records)
        ack.acknowledge()
    } catch (ex: Exception) {
        throw ex
    }
}
```

### Acceptance criteria
- [ ] Consumer processes a batch and commits only on success.
- [ ] Failed batch does not advance offsets.
- [ ] Poison records can be routed to DLQ.

---

## 7) Add graceful shutdown
- [ ] Stop reading new Wikimedia events in the producer.
- [ ] Flush producer buffers before exit.
- [ ] Stop polling in the consumer.
- [ ] Complete or abort current batch within a timeout.
- [ ] Close network clients cleanly.

### Example shutdown hook
```kotlin
@Component
class ShutdownHook(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val registry: KafkaListenerEndpointRegistry,
) {
    @PreDestroy
    fun shutdown() {
        registry.listenerContainers.forEach { it.stop() }
        kafkaTemplate.flush()
    }
}
```

### Acceptance criteria
- [ ] SIGTERM does not lose in-flight messages.
- [ ] Consumer can restart from the last committed offset.

---

## 8) Update Docker Compose
- [ ] Run Redpanda in Docker.
- [ ] Run producer and consumer in Docker.
- [ ] Run Cassandra and Redis in Docker if they are still required.
- [ ] Wire all services through internal DNS names.
- [ ] Add health checks and startup dependencies.

### Example Redpanda service
```yaml
services:
  redpanda:
    image: redpandadata/redpanda:latest
    command:
      - redpanda
      - start
      - --smp
      - "1"
      - --memory
      - 1G
      - --overprovisioned
      - --node-id
      - "0"
      - --check=false
      - --kafka-addr
      - internal://0.0.0.0:9092,external://0.0.0.0:19092
      - --advertise-kafka-addr
      - internal://redpanda:9092,external://localhost:19092
```

### Example app environment
```yaml
environment:
  SPRING_KAFKA_BOOTSTRAP_SERVERS: redpanda:9092
  SPRING_PROFILES_ACTIVE: docker
```

### Acceptance criteria
- [ ] `docker compose up` starts all required services.
- [ ] Producer and consumer can resolve Redpanda via Docker DNS.

---

## 9) Add tests
- [ ] Add unit tests for parsing and transformation.
- [ ] Add unit tests for batch commit behavior.
- [ ] Add unit tests for DLQ routing.
- [ ] Add integration tests with Redpanda.

### Producer unit test example
```kotlin
@Test
fun `maps raw event into shared contract`() {
    val raw = """{"id":"1","title":"Kotlin"}"""
    val result = mapper.map(raw)

    assertEquals("Kotlin", result.title)
}
```

### Consumer unit test example
```kotlin
@Test
fun `acks only after batch processing succeeds`() {
    consumer.consume(records, ack)

    verify(ack).acknowledge()
}
```

### Integration test direction
```kotlin
@Testcontainers
class RedpandaIntegrationTest {
    companion object {
        @Container
        val redpanda = KafkaContainer(DockerImageName.parse("redpandadata/redpanda:latest"))
    }
}
```

### Acceptance criteria
- [ ] Tests cover producer flow.
- [ ] Tests cover consumer batch and ack flow.
- [ ] Integration tests exercise Redpanda.

---

## 10) Update CI
- [ ] Split CI into producer and consumer jobs.
- [ ] Run unit tests for both modules.
- [ ] Run integration tests for both modules.
- [ ] Add a compose-level smoke test.

### Example commands
```bash
./gradlew :cmd:producer:test
./gradlew :cmd:consumer:test
./gradlew :cmd:producer:integrationTest
./gradlew :cmd:consumer:integrationTest
```

### Acceptance criteria
- [ ] CI blocks merges when either service fails.
- [ ] CI runs are reproducible in Docker.

---

## 11) Roll out in phases
- [ ] Phase 1: add producer and publish to Redpanda while existing flow still runs.
- [ ] Phase 2: add Redpanda consumer in shadow mode.
- [ ] Phase 3: switch canonical reads to the new consumer.
- [ ] Phase 4: remove the legacy direct flow.
- [ ] Phase 5: tune lag, retries, and alerting.

### Acceptance criteria
- [ ] Each phase has a measurable success signal.
- [ ] Rollback is possible between phases.

---

## 12) File-by-file migration order (`src/main/kotlin`)

Use this order to keep the app running while you migrate. For each line: move the file, fix imports, run module tests, then continue.

### Phase A - Shared contracts first (`lib/core`)
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/domain/WikiEvent.kt` -> `lib/core/src/main/kotlin/com/redspace/wikistreamkotlin/core/domain/WikiEvent.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/domain/WikiEventMeta.kt` -> `lib/core/src/main/kotlin/com/redspace/wikistreamkotlin/core/domain/WikiEventMeta.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/exception/AppError.kt` -> `lib/core/src/main/kotlin/com/redspace/wikistreamkotlin/core/exception/AppError.kt`

### Phase B - Producer ingestion path (`cmd/producer`)
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/WebClientConfig.kt` -> `cmd/producer/src/main/kotlin/com/redspace/wikistreamkotlin/producer/config/WebClientConfig.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/WikiStreamProperties.kt` -> `cmd/producer/src/main/kotlin/com/redspace/wikistreamkotlin/producer/config/WikiStreamProperties.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/consumer/WikiStreamClient.kt` -> `cmd/producer/src/main/kotlin/com/redspace/wikistreamkotlin/producer/ingest/WikiStreamClient.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/consumer/WikiEventParser.kt` -> `cmd/producer/src/main/kotlin/com/redspace/wikistreamkotlin/producer/ingest/WikiEventParser.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/consumer/WikiStreamConsumer.kt` -> split into producer publish flow in `cmd/producer` (ingest + map + publish)

### Phase B.1 - Refactor `WikiStreamConsumer.kt` into producer-owned pipeline

Use these exact steps to split the old loop safely.

#### 1) Ingest stays in producer (`WikiStreamClient`)
- [ ] Keep SSE/WebClient connectivity only in `cmd/producer`.
- [ ] Return a `Flow<String>` (raw SSE payload) or `Flow<WikiEvent>` depending on where you want parsing.

```kotlin
@Component
class WikiStreamClient(
    private val webClient: WebClient,
    private val properties: WikiStreamProperties,
) {
    fun streamRawEvents(): Flow<String> =
        webClient
            .get()
            .uri(properties.url)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.USER_AGENT, properties.userAgent)
            .retrieve()
            .bodyToFlux<String>()
            .asFlow()
}
```

#### 2) Map/parse stays in producer (`WikiEventParser`)
- [ ] Parse payload in producer and emit shared model from `lib/core`.
- [ ] Keep parse failures non-fatal and log + skip malformed records.

```kotlin
@Component
class WikiEventParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseEvent(raw: String): WikiEvent? =
        runCatching { objectMapper.readValue(raw, WikiEvent::class.java) }
            .getOrNull()
}
```

#### 3) Publish from producer (`RedpandaPublisher`)
- [ ] Add `spring-kafka` to `cmd/producer`.
- [ ] Publish parsed events to `wiki.recentchange.raw` via `KafkaTemplate`.

```kotlin
@Component
class RedpandaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    fun publish(event: WikiEvent) {
        val key = event.user ?: "unknown"
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(Topics.RAW, key, payload)
    }
}
```

#### 4) Replace old `WikiStreamConsumer` loop with producer runner
- [ ] Move the start/retry loop from root `src/main` into `cmd/producer`.
- [ ] New loop does: `stream -> parse -> publish`.

```kotlin
@Component
class ProducerIngestionRunner(
    private val streamClient: WikiStreamClient,
    private val parser: WikiEventParser,
    private val publisher: RedpandaPublisher,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            streamClient.streamRawEvents().collect { raw ->
                parser.parseEvent(raw)?.let { publisher.publish(it) }
            }
        }
    }
}
```

#### 5) Consumer reads Redpanda only (no Wikimedia calls)
- [ ] Remove `WikiStreamClient` and any `WebClient` Wikimedia dependency from `cmd/consumer`.
- [ ] Add Redpanda/Kafka batch listener only.

```kotlin
@Component
class RedpandaBatchConsumer(
    private val statsService: StatsService,
) {
    @KafkaListener(topics = [Topics.RAW], containerFactory = "batchKafkaListenerContainerFactory")
    fun consume(records: List<ConsumerRecord<String, String>>, ack: Acknowledgment) {
        records.forEach { record ->
            val event = decode(record.value())
            statsService.recordForActiveUsers(event)
        }
        ack.acknowledge()
    }
}
```

#### 6) Retire old loop in root `src/main`
- [ ] Keep legacy `src/main/.../WikiStreamConsumer.kt` during shadow validation only.
- [ ] Disable old loop once producer -> Redpanda -> consumer path is verified.
- [ ] Remove old class after cutover and green tests.

#### Phase B.1 verification checklist
- [ ] `:cmd:producer` contains `WikiStreamClient`, `WikiEventParser`, and `RedpandaPublisher`.
- [ ] `:cmd:consumer` contains no Wikimedia URL, `WebClient`, or SSE intake logic.
- [ ] Producer writes records to `wiki.recentchange.raw`.
- [ ] Consumer receives from Redpanda and updates stats.

### Phase C - Consumer processing path (`cmd/consumer`)
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/domain/StatsSnapshot.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/domain/StatsSnapshot.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/domain/UserAccount.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/domain/UserAccount.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/domain/RevokedToken.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/domain/RevokedToken.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/StatsRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/StatsRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/CassandraStatsRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/CassandraStatsRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/StatsSnapshotCassandraRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/StatsSnapshotCassandraRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/UserAccountAtomicRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/UserAccountAtomicRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/UserAccountCassandraRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/UserAccountCassandraRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/SessionRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/SessionRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/InMemorySessionRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/InMemorySessionRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/RedisSessionRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/RedisSessionRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/repository/RevokedTokenCassandraRepository.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/repository/RevokedTokenCassandraRepository.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/service/StatsService.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/service/StatsService.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/service/ActiveUserSessionService.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/service/ActiveUserSessionService.kt`
- [ ] Replace direct stream wiring with Redpanda batch listener in `cmd/consumer` (new file, for example `RedpandaBatchConsumer.kt`)

### Phase D - API and security layer (keep in `cmd/consumer` for now)
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/controller/StatsController.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/controller/StatsController.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/controller/AuthController.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/controller/AuthController.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/controller/dto/AuthDtos.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/controller/dto/AuthDtos.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/service/AuthService.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/service/AuthService.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/JwtSecurityProperties.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/JwtSecurityProperties.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/SecurityConfig.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/SecurityConfig.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/NoAuthSecurityConfig.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/NoAuthSecurityConfig.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/RevokedTokenWebFilter.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/RevokedTokenWebFilter.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/JwtTokenService.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/JwtTokenService.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/security/GeneratedAccessToken.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/security/GeneratedAccessToken.kt`

### Phase E - Infrastructure/config and error handling
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/AstraDbProperties.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/config/AstraDbProperties.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/AstraDbConfig.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/config/AstraDbConfig.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/RedisConfig.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/config/RedisConfig.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/AuthProperties.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/config/AuthProperties.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/config/UserAccountAtomicRepositoryAutoConfiguration.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/config/UserAccountAtomicRepositoryAutoConfiguration.kt`
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/exception/AppErrorLogger.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/exception/AppErrorLogger.kt` (or `lib/core` if shared)
- [ ] `src/main/kotlin/com/redspace/wikistreamkotlin/exception/GlobalErrorHandler.kt` -> `cmd/consumer/src/main/kotlin/com/redspace/wikistreamkotlin/consumer/exception/GlobalErrorHandler.kt`

### Phase F - App entrypoint cleanup and removal
- [ ] Keep `src/main/kotlin/com/redspace/wikistreamkotlin/WikistreamkotlinApplication.kt` unchanged until producer and consumer both run end-to-end.
- [ ] After cutover, retire `src/main/kotlin/com/redspace/wikistreamkotlin/WikistreamkotlinApplication.kt` and remove legacy root `src/main/kotlin/com/redspace/wikistreamkotlin/consumer/*`.
- [ ] Remove any now-unused root `src/main/kotlin` files only after `:cmd:producer:test`, `:cmd:consumer:test`, and integration tests pass.

### Per-step verification checklist (run after each phase)
- [ ] `./gradlew :lib:core:test`
- [ ] `./gradlew :cmd:producer:test`
- [ ] `./gradlew :cmd:consumer:test`
- [ ] `./gradlew test` (legacy root, while still in transition)

---

## 13) Final done checklist
- [ ] Producer and consumer run independently.
- [ ] Consumer reads only from Redpanda.
- [ ] Manual acknowledgements are in place.
- [ ] Batch processing is tested.
- [ ] Graceful shutdown is verified.
- [ ] Docker Compose works end-to-end.
- [ ] CI passes for both modules.
- [ ] Documentation is updated.

---

## 14) Recommended implementation order
1. [ ] Create the multi-module Gradle structure.
2. [ ] Add shared contracts in `lib/core`.
3. [ ] Build the producer publishing path.
4. [ ] Add Redpanda to Docker Compose.
5. [ ] Build the consumer batch path.
6. [ ] Add graceful shutdown handling.
7. [ ] Add tests.
8. [ ] Update CI.
9. [ ] Run shadow validation.
10. [ ] Cut over and remove the legacy path.

