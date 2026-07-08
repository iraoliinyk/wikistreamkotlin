# wikistreamkotlin

Spring Boot + Kotlin application that consumes the [Wikimedia recent-change stream](https://stream.wikimedia.org/v2/stream/recentchange), 
pipes events through **Redpanda** (Kafka-compatible broker) — with a declarative 
**Redpanda Connect (RPCN)** protobuf-validation stage sitting between the producer and the consumer — 
and aggregates per-user stats stored in Cassandra.

Events are recorded only while a user is logged in. Each authenticated user sees only their own `StatsView`. 
Stats pause on logout and resume on the next login.

---

## Table of Contents

- [Module Structure](#module-structure)
- [Tech Stack](#tech-stack)
- [Redpanda Connect (RPCN) Validation Stage](#redpanda-connect-rpcn--protobuf-validation-stage)
- [Library Versions](#library-versions)
- [Prerequisites](#prerequisites)
- [Project Setup](#project-setup)
- [Quick Start](#quick-start)
- [Monitoring with Prometheus & Grafana](#monitoring-with-prometheus--grafana)
- [Kubernetes Deployment (Minikube)](#kubernetes-deployment-minikube)
- [Configuration Reference](#configuration-reference)
- [API Reference](#api-reference)
- [Tests](#tests)
- [Code Quality](#code-quality)
- [Postman Collection](#postman-collection)
- [Further Reading](#further-reading)

---

## Module Structure

```
wikistreamkotlin/
├── cmd/
│   ├── producer/                  # Standalone Spring Boot app
│   │   └── src/main/kotlin/
│   │       └── producer/
│   │           ├── ProducerApplication.kt
│   │           ├── ProducerIngestionRunner.kt  # @EventListener — starts SSE loop
│   │           ├── WikiStreamClient.kt         # WebClient SSE intake → Flow<String>
│   │           ├── WikiEventParser.kt          # JSON → WikiEvent (nullsafe)
│   │           ├── RedpandaPublisher.kt        # KafkaTemplate → wiki.recentchange.proto
│   │           ├── config/
│   │           │   ├── ProducerKafkaConfig.kt  # ProducerFactory + KafkaTemplate beans
│   │           │   ├── WebClientConfig.kt
│   │           │   └── WikiStreamProperties.kt
│   │           └── serializer/
│   │               └── ProtoWikiEventSerializer.kt  # WikiEvent → protobuf binary
│   │
│   └── consumer/                  # Standalone Spring Boot app
│       └── src/main/kotlin/
│           └── consumer/
│               ├── ConsumerApplication.kt
│               ├── CoroutineBatchConsumer.kt   # suspend @KafkaListener batch — one delta per batch, per-user fan-out
│               ├── config/
│               │   ├── ConsumerKafkaConfig.kt           # ConsumerFactory + batchKafkaListenerContainerFactory
│               │   ├── DlqKafkaConfig.kt                # DLQ producer (ByteArray, acks=all)
│               │   ├── KafkaErrorHandlerConfig.kt       # DefaultErrorHandler → DLQ (framework-level)
│               │   ├── GracefulShutdownHandler.kt       # drain listener containers on shutdown
│               │   ├── RedisConfig.kt                   # Lettuce connection + RedisTemplate
│               │   ├── CassandraConfig.kt               # ReactiveCassandraTemplate wiring
│               │   ├── AstraDbConfig.kt                 # CqlSession customizer for Astra
│               │   ├── AstraDbProperties.kt
│               │   ├── AuthProperties.kt
│               │   └── JacksonConfig.kt
│               ├── controller/
│               │   ├── AuthController.kt       # /v1/auth/*
│               │   ├── StatsController.kt      # /v1/stats, /v1/status
│               │   └── dto/AuthDtos.kt
│               ├── domain/
│               │   ├── UserAccount.kt
│               │   ├── StatsView.kt            # read-side projection (assembled on demand)
│               │   ├── UserStatsDelta.kt       # per-batch aggregation delta
│               │   └── RevokedToken.kt
│               ├── repository/
│               │   ├── SessionRepository.kt                # Interface
│               │   ├── RedisSessionRepository.kt           # Redis-backed session storage
│               │   ├── UserAccountCassandraRepository.kt   # Spring Data
│               │   ├── StatsReadRepository.kt              # Read interface (ISP split)
│               │   ├── StatsWriteRepository.kt             # Write interface (ISP split)
│               │   ├── CassandraStatsReadRepository.kt     # Assembles StatsView from 3 tables
│               │   ├── CassandraStatsWriteRepository.kt    # COUNTER + idempotent INSERT writes
│               │   └── RevokedTokenCassandraRepository.kt
│               ├── security/
│               │   ├── SecurityConfig.kt          # JWT filter chain (auth enabled)
│               │   ├── NoAuthSecurityConfig.kt    # Permit-all (auth disabled)
│               │   ├── JwtTokenService.kt
│               │   ├── JwtSecurityProperties.kt
│               │   ├── RevokedTokenWebFilter.kt   # jti revocation check
│               │   └── GeneratedAccessToken.kt
│               ├── service/
│               │   ├── AuthService.kt
│               │   ├── StatsService.kt
│               │   ├── BatchAggregationService.kt   # batch → single UserStatsDelta
│               │   └── ActiveUserSessionService.kt
│               ├── metrics/
│               │   └── ConsumerMetricsService.kt
│               ├── exception/
│               │   ├── GlobalErrorHandler.kt
│               │   └── ConsumerErrorLogger.kt
│               └── serializer/
│                   └── ProtoWikiEventDeserializer.kt  # protobuf binary → WikiEvent
│
└── lib/
    ├── core/                      # Shared contracts — no Spring Boot dependency
    │   ├── src/main/proto/
    │   │   └── wikievent.proto           # Protobuf schema for WikiEvent message
    │   └── src/main/kotlin/
    │       └── core/
    │           ├── Topics.kt                  # Topic name constants (PROTO, VALIDATED, DLQ)
    │           ├── EventEnvelope.kt
    │           ├── domain/
    │           │   ├── WikiEvent.kt
    │           │   └── WikiEventMeta.kt
    │           ├── mapper/
    │           │   └── ProtoWikiEventMapper.kt   # Protobuf serialization ↔ domain model
    │           └── exception/
    │               ├── AppError.kt              # error hierarchy
    │               └── ErrorLogger.kt
    └── kafka/                     # Shared Kafka utilities — no Spring Boot dependency
        └── src/main/kotlin/
            └── kafka/
                └── DlqPublisher.kt   # forwards failed records to wiki.recentchange.dlq
```

### Ownership matrix

| Concern | `cmd/producer` | `cmd/consumer` | `lib/core` |
|---------|:--------------:|:--------------:|:----------:|
| Wikimedia SSE intake | ✅ | ❌ | ❌ |
| Publish to Redpanda | ✅ | ❌ | ❌ |
| Consume from Redpanda | ❌ | ✅ | ❌ |
| Cassandra persistence | ❌ | ✅ | ❌ |
| REST API + Auth | ❌ | ✅ | ❌ |
| Event DTOs / topic names | ❌ | ❌ | ✅ |
| Error hierarchy | ❌ | ❌ | ✅ |

---

## Tech Stack

### Language & Runtime
| Component | Version |
|-----------|---------|
| Kotlin | 2.3.0 |
| Java | 25 (Eclipse Temurin) |
| Gradle | 9.x (Kotlin DSL) |

### Framework
| Component | Version | Role |
|-----------|---------|------|
| Spring Boot | 4.0.5 | Application framework |
| Spring WebFlux | (Boot-managed) | Reactive HTTP server (Netty) |
| Spring Kafka | 4.0.4 | Kafka protocol layer for Redpanda |
| Spring Data Cassandra | (Boot-managed) | Cassandra ORM + repositories |
| Spring Data Redis | (Boot-managed) | Redis session storage (Lettuce) |
| Spring Security | (Boot-managed) | Auth filter chain |
| Spring Security OAuth2 Resource Server + Jose | (Boot-managed) | JWT decode & validation |
| Kotlinx Coroutines + Reactor bridge | (Boot-managed) | Coroutine ↔ Reactor interop |

### Monitoring & Observability
| Component | Version | Role |
|-----------|---------|------|
| **Micrometer Prometheus** | (Boot-managed) | Metrics collection and exposition for Prometheus |
| **Spring Boot Actuator** | (Boot-managed) | Metrics endpoints (`/actuator/prometheus`, `/actuator/health`) |
| **Prometheus** | v2.48.0 | Metrics storage and time-series database |
| **Grafana** | v10.2.2 | Metrics visualization and dashboarding |

### Messaging
| Component | Version | Role |
|-----------|---------|------|
| **Redpanda** | v24.3.14 | Kafka-compatible message broker; decouples producer from consumer via pub-sub topics |
| **Redpanda Connect (RPCN)** | `redpandadata/connect:4.99.0` | Declarative YAML stream processor; runs the protobuf-validation stage between producer and consumer (see [Redpanda Connect Validation Stage](#redpanda-connect-rpcn--protobuf-validation-stage)) |
| **Apache Kafka / Spring Kafka** | 4.0.4 | Protocol layer + client libraries for Redpanda integration |
| **Protocol Buffers (Protobuf)** | proto3 | Binary serialization for `WikiEvent` messages; 75% smaller and 5-10x faster than JSON |

### Event Streams
| Topic | Partitions | Replicas | Format | Retention | Purpose | Notes |
|-------|-----------|----------|--------|-----------|---------|-------|
| `wiki.recentchange.proto` | 6 | 1 | Protobuf binary | 7 days | **Producer output / RPCN input** — all Wikipedia recent-change events encoded as protobuf `WikiEvent` | Compressed with zstd. The producer writes here; the RPCN validation stage reads here. |
| `wiki.recentchange.validated` | 6 | 1 | Protobuf binary | 7 days | **RPCN output / consumer input** — records that passed RPCN protobuf decode/validation | **Byte-identical** to `proto` (RPCN validates, never re-serializes). This is the topic the Spring consumer reads. |
| `wiki.recentchange.dlq` | 1 | 1 | Protobuf binary | default (7 days) | Dead-letter queue for unprocessable records | Two producers share it: RPCN (decode/validation failures) and the consumer's `DlqPublisher` (Cassandra write failures), via one `dlq.*` header contract. |

### Dead Letter Queue (DLQ) Handling

`wiki.recentchange.dlq` now has **two producers** (see [Redpanda Connect Validation Stage](#redpanda-connect-rpcn--protobuf-validation-stage)):

- **RPCN** forwards protobuf **decode/validation failures** before they ever reach the consumer.
- The **consumer** forwards **Cassandra write failures** — when it fails to process a validated record, the message is **not lost**; it is forwarded to `wiki.recentchange.dlq` with full error context for debugging and replay.

Both use the same `dlq.*` header contract, so a single DLQ consumer can read either kind. The rest of this section describes the **consumer-side** (write-failure) path.

**Flow:**

```
wiki.recentchange.validated   (RPCN output — already protobuf-validated)
    ↓
CoroutineBatchConsumer (batch @KafkaListener)
    ↓
per-user Cassandra write (COUNTER + idempotent INSERT)
    ├─ Success → stats recorded, next record
    └─ Exception thrown
        ↓
    DlqPublisher.send(record, exception)
        ↓
    wiki.recentchange.dlq (original bytes + error headers)
```

**Two-layer error handling:**

| Layer | Trigger | Handler | Scope |
|-------|---------|---------|-------|
| **Application-level** | Cassandra write failure while persisting a batch delta | `CoroutineBatchConsumer.persistUserDelta()` catches per-user and calls `DlqPublisher.send()` | Per-user within a batch — other users' writes in the same batch still complete |
| **Framework-level** | Deserialization failure or unhandled exception before batch processing | `KafkaErrorHandlerConfig` → Spring's `DefaultErrorHandler` routes to `DlqPublisher` | Entire record rejected before reaching business logic |

**What gets written to DLQ:**

| Component | Content |
|-----------|---------|
| **Key** | Original record key (Wikipedia username) |
| **Value** | Original protobuf bytes (re-serialized via `ProtoWikiEventMapper` if already deserialized) |
| **Header `dlq.error.message`** | Exception message (e.g., `"Cassandra write timeout"`) |
| **Header `dlq.error.class`** | Fully-qualified exception class (e.g., `com.datastax.oss.driver.api.core.AllNodesFailedException`) |
| **Header `dlq.source.topic`** | Source topic name (`wiki.recentchange.validated` for consumer write failures; `wiki.recentchange.proto` for RPCN decode failures) |
| **Header `dlq.source.partition`** | Partition number where the record originated |
| **Header `dlq.source.offset`** | Offset of the failed record in the source partition |

**DLQ producer configuration (`DlqKafkaConfig`):**

| Setting | Value | Reason |
|---------|-------|--------|
| Value serializer | `ByteArraySerializer` | Preserves original bytes exactly as received — no re-encoding |
| Acks | `all` | DLQ messages must not be lost; wait for full broker acknowledgment |
| Key serializer | `StringSerializer` | Retains original Kafka key for correlation |

**Batch acknowledgment behavior:**

The consumer uses **manual acknowledgment** (`AckMode.MANUAL`). A failed write does NOT block the batch:

1. Batch of N records arrives; `BatchAggregationService` computes **one** `UserStatsDelta` for the whole batch
2. The delta is attributed to every currently-active user concurrently via `async { }.awaitAll()`
3. If a user's Cassandra write fails → caught in `persistUserDelta()`, that user's records sent to DLQ, logged as WARN
4. All other users' writes in the batch continue
5. After all writes complete → `ack.acknowledge()` commits the entire batch offset

This means: **a single failing write does not stall the consumer group** — and because writes are COUNTER/idempotent, redelivery is safe.

**Logging:**

| Outcome | Log Level | Message |
|---------|-----------|---------|
| Record sent to DLQ successfully | `WARN` | `"Record sent to DLQ due to processing failure"` + source context |
| DLQ publish itself fails | `ERROR` | `"Failed to send record to DLQ"` + source context |
| Processing failure (before DLQ send) | `ERROR` | `"Kafka processing failed — sent to DLQ"` + topic/partition/offset |

**Inspecting DLQ messages:**

```bash
# List DLQ messages via rpk
docker exec wikistream-redpanda rpk topic consume wiki.recentchange.dlq --num 5

# Check message headers (error context)
docker exec wikistream-redpanda rpk topic consume wiki.recentchange.dlq \
  --num 1 --print-headers

# Count DLQ messages (monitor for spikes)
docker exec wikistream-redpanda rpk topic describe wiki.recentchange.dlq
```

**Replaying DLQ messages** (manual recovery):

```bash
# Consume from DLQ and re-publish to proto topic for reprocessing
docker exec wikistream-redpanda rpk topic consume wiki.recentchange.dlq \
  --num 10 | rpk topic produce wiki.recentchange.proto
```

> **Note:** DLQ messages use Redpanda default retention (~7 days). Monitor DLQ depth — a spike indicates a systemic issue (e.g., Cassandra down, schema mismatch).

### Storage
| Store | Version | Usage |
|-------|---------|-------|
| Apache Cassandra | 5.0 | User accounts, stats counters (COUNTER + append-only tables), revoked tokens |
| Redis | 7 | Active session tracking (login/logout state + TTL) |
| DataStax Astra | cloud | Managed Cassandra (optional Astra profile) |

### Auth
| Mechanism | Details |
|-----------|---------|
| JWT (HS256) | Signed with app secret, TTL-based expiry |
| Bearer scheme | `Authorization: Bearer <token>` on protected routes |
| Token revocation | `revoked_tokens` Cassandra table + `jti` lookup on every request |
| Session state | Redis hash per email, TTL mirrors JWT expiry |

### Code Quality
| Tool | Version | Role |
|------|---------|------|
| detekt | 2.0.0-alpha.2 | Static analysis |
| ktlint (plugin) | 12.1.1 | Code formatting |
| ktlint (engine) | 1.5.0 | Formatting rules engine |

### Testing
| Library | Version | Role |
|---------|---------|------|
| JUnit 5 | (Boot-managed) | Test runner |
| Mockito-Kotlin | 5.4.0 | Mocking |
| Spring Boot Test | (Boot-managed) | Context loading + test slices |
| Testcontainers | 2.0.4 | Real Cassandra + Redis in integration tests |
| Reactor Test | (Boot-managed) | Reactive stream assertions |

---

## Redpanda Connect (RPCN) — Protobuf Validation Stage

**Redpanda Connect (RPCN)** is a declarative, YAML-configured stream processor that 
sits **between the producer and the Spring consumer**. 
It decodes every protobuf record purely as a **validation check**, forwards the ones 
that parse to a clean topic, and diverts the ones that don't to the DLQ — so malformed records never reach the consumer.

```
Wikimedia SSE → producer → wiki.recentchange.proto (6 partitions)
                                   │
                                   ▼
                  ┌─ Redpanda Connect (rpcn/pipeline.yaml) ──────────┐
                  │  protobuf decode = VALIDATION ONLY               │
                  │  ├─ valid   → wiki.recentchange.validated        │
                  │  │            (original bytes, byte-identical)   │
                  │  └─ invalid → wiki.recentchange.dlq              │
                  │               (DlqPublisher-compatible headers)  │
                  │  Prometheus metrics on :4195/metrics             │
                  └──────────────────────────────────────────────────┘
                                   │
                                   ▼
                  Spring Boot consumer  (reads wiki.recentchange.validated)
                     ├─ Redis: enumerate currently-active users
                     ├─ BatchAggregationService → one delta per batch
                     ├─ per-user Cassandra writes (COUNTER + idempotent INSERT)
                     ├─ write failures → wiki.recentchange.dlq (DlqPublisher)
                     └─ ack after all writes
```

### How it works

- **Pass-through, not re-serialization.** Decode succeeds → the *original* protobuf bytes are republished to `wiki.recentchange.validated` **byte-identical** (the decoded JSON is discarded; only a `validated: "true"` metadata flag is set). This keeps the consumer's `ProtoWikiEventDeserializer` working unchanged and avoids any proto3 round-trip edge cases.
- **Validation failures → DLQ.** Undecodable records go to `wiki.recentchange.dlq` with the **same five `dlq.*` headers** the consumer's `DlqPublisher` uses (`dlq.error.message`, `dlq.error.class`, `dlq.source.topic`, `dlq.source.partition`, `dlq.source.offset`). RPCN emits the stable discriminator `dlq.error.class = RpcnProtobufValidationError`; the Spring `DlqPublisher` emits real JVM exception class names. One topic, one header schema, two producers.
- **Consumer target is env-overridable.** `CoroutineBatchConsumer` listens on `${app.kafka.consumer.topic:wiki.recentchange.validated}` (`Topics.VALIDATED`) with consumer group `wiki-consumer`; RPCN uses a distinct group `rpcn-validator`. Rollback is a topic-name flip back to `wiki.recentchange.proto` — no rebuild.
- **Observability.** RPCN exposes native Prometheus metrics on `:4195/metrics` (scrape job `rpcn` in `config/prometheus.yml` / `config/prometheus-dev.yml`); the connector dashboard lives at `config/grafana/dashboards/rpcn_connector.json`.

**Where it lives:** pipeline `rpcn/pipeline.yaml` (+ native unit tests `rpcn/pipeline_benthos_test.yaml`); compose service `rpcn` in `docker-compose.yml` / `docker-compose.dev.yml`; Kubernetes manifest `k8s/rpcn.yaml` (+ ServiceMonitor in `k8s/servicemonitors.yaml`). Full design rationale: [STREAMING_INSTRUCTIONS.md](STREAMING_INSTRUCTIONS.md) and the trade study in [RedpandaConnect.md](RedpandaConnect.md).

### Why RPCN was added

The consumer was doing **two structurally different jobs** in one runtime, and only one of them is RPCN's sweet spot:

1. **Stateless stream plumbing** — decode protobuf, validate it, route bad records to a DLQ with diagnostic metadata, emit throughput/latency metrics. *Exactly* what Redpanda Connect is built for.
2. **Stateful business aggregation** — look up the dynamic set of currently-logged-in users from Redis, compute one batch delta, fan it out to N idempotent Cassandra COUNTER/INSERT writes, and ack only when all succeed. Genuinely stateful, session-aware, dynamic-fan-out logic.

RPCN was introduced to **peel off only job #1** into a declarative YAML stage, while the risky stateful path stays in the well-tested Kotlin/Spring consumer. The wins: protobuf validation as a decoupled concern (malformed records never reach the consumer), a standardized DLQ (one header schema for both decode and write failures), and per-stage observability with a dedicated connector dashboard.

### Why it is *not* used for 100% of the pipeline (and the Spring consumer stays)

A full "convert everything to RPCN" would lean hardest on the one component that is weakest — the `cassandra` output:

- **Immature sink.** RPCN's `cassandra` output is **Community-tier / "not production-ready"** per Redpanda's own component tiering, has **no COUNTER-column support**, and its logged-`BATCH` write model is an **anti-pattern for counter columns**. Betting the money-like counter path on it was not worth it.
- **No-Go constraint.** The dynamic, Redis-driven fan-out (writing the batch delta to *whoever is logged in right now*) has no stock RPCN processor — it would require **custom Go plugins**, which this project deliberately avoids.
- **Kafka Streams doesn't fit either.** The aggregation is **not keyed on event data** — it's "everything in this poll batch, attributed to whoever is active," the session state lives in **Redis** (external to any stream), and the sink is **Cassandra** (not Kafka). Streams would force a single-task aggregation bottleneck, auth rework or external side-calls, and RocksDB/changelog operational baggage — for the *same* at-least-once + idempotent-writes guarantee the consumer already provides.
- **Keeps its safety net.** The stateful path retains its integration-test suite (redelivery idempotency, offset-commit timing, multi-consumer concurrency, graceful shutdown), plus the JWT-authenticated REST API and the Redis session *write* path — none of which RPCN has a framework for.

**In short:** RPCN owns the stateless validation/DLQ/observability slice where it is production-grade; the Spring consumer keeps the stateful, session-aware Cassandra aggregation where RPCN (and Kafka Streams) would add cost and risk without any correctness gain. See [STREAMING_INSTRUCTIONS.md §3 and §8](STREAMING_INSTRUCTIONS.md) for the full analysis.

---

## Library Versions

> All Spring ecosystem libraries without an explicit version are managed by the Spring Boot 4.0.5 BOM.

| Library | Version |
|---------|---------|
| `kotlin` | 2.3.0 |
| `spring-boot` | 4.0.5 |
| `spring-kafka` | 4.0.4 |
| `protobuf-java` | (BOM) |
| `com.google.protobuf` (plugin) | 0.9.4 |
| `kotlinx-coroutines-reactor` | (BOM) |
| `jackson-module-kotlin` | (BOM) |
| `jackson-databind` | (BOM) |
| `netty` | 4.2.12.Final |
| `testcontainers-bom` | 2.0.4 |
| `mockito-kotlin` | 5.4.0 |
| `kotlin-logging-jvm` | 2.0.11 |
| `detekt` (plugin) | 2.0.0-alpha.2 |
| `ktlint` (plugin) | 12.1.1 |
| `ktlint` (engine) | 1.5.0 |

---

## Prerequisites

| Tool | Minimum version | Notes |
|------|----------------|-------|
| JDK | 25 | Eclipse Temurin recommended |
| Docker | 24+ | Required for compose and integration tests |
| Docker Compose | v2 | `docker compose` (not `docker-compose`) |
| IntelliJ IDEA | 2024+ | Kotlin plugin bundled |

---

## Project Setup

### 1. Clone

```bash
git clone https://github.com/your-org/wikistreamkotlin.git
cd wikistreamkotlin
```

### 2. Copy secrets templates

```bash
cp config/auth-secrets.properties.template     config/auth-secrets.properties
cp config/cassandra-secrets.properties.template config/cassandra-secrets.properties
```

**`config/auth-secrets.properties`** — set a strong JWT signing secret (≥ 32 chars):

```properties
app.security.jwt.issuer=wikistreamkotlin
app.security.jwt.secret=your-long-random-secret-here-at-least-32-chars
app.security.jwt.access-token-ttl-seconds=3600
```

**`config/cassandra-secrets.properties`** — only needed for the Astra profile (leave blank for local Cassandra).

### 3. Choose session backend

Edit `.env` (repository root):

```dotenv
# Redis is required for session storage
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
```

### 4. Build all modules

```bash
./gradlew build -x test
```

The root project is an aggregator for shared build/lint tasks. Runnable Spring Boot artifacts are built from module tasks:

```bash
./gradlew :cmd:producer:bootJar
./gradlew :cmd:consumer:bootJar
```

### 5. Run unit tests

```bash
./gradlew :cmd:producer:test :cmd:consumer:test :lib:core:test
```

---

## Quick Start

This guide covers two deployment modes:
- **Option A** — Local apps with Docker infrastructure (recommended for development)
- **Option B** — Full Docker stack (production-like with Astra)

### Port Reference

All service ports for local development and Docker deployment:

| Service | Local (IDE/Gradle) | Docker (Container) | Docker (Host) | Notes |
|---------|-------------------|-------------------|---------------|-------|
| **Consumer** | `7000` | `7000` | `7001` (default) | Local uses `7000`; Docker host port configurable via `CONSUMER_PORT` |
| **Producer** | `7002` | `7002` | N/A | Metrics endpoint at `/actuator/prometheus` |
| **Redpanda (Kafka)** | `19092` | `9092` (internal)<br>`19092` (external) | `19092` | External port for local apps |
| **Redpanda Console** | `8080` | `8080` | `8080` | Web UI for Kafka topics |
| **Cassandra (dev only)** | `19042` | `9042` (each of `cassandra-1/2/3`) | `19042` (cassandra-1 only) | Dev: 3-node cluster on `wikistream-net`; only node-1 published. Prod uses Astra — no container. |
| **Redis** | `6379` | `6379` | `6379` | Required in both stacks |
| **Prometheus** | `9090` | `9090` | `9090` | Monitoring (when enabled) |
| **Grafana** | `3000` | `3000` | `3000` | Dashboards (when enabled) |

**Key conventions:**
- **Local development:** Consumer runs on `7000`, Producer on `7002`
- **Docker stack:** Consumer container internal `7000`, host `7001` (configurable)
- Redpanda uses `9092` for internal Docker network, `19092` for external/host access
- All infrastructure ports are standard (Cassandra `9042`→`19042`, Redis `6379`, etc.)

---

### Option A — Local IDEs + Docker Infra (Recommended for Development)

Perfect for debugging with IDE breakpoints and local hot-reload. Apps run locally via Gradle/IDE, infrastructure runs in Docker on the shared `wikistream-net` bridge.

**What you'll run:**
- **Docker containers:** Redpanda + topic init, **3-node Cassandra cluster** (`cassandra-1/2/3`) + schema init, Redis, Redpanda Console, optional Prometheus + Grafana.
- **Local Gradle:** Consumer app, Producer app.

The Cassandra topology mirrors the production Astra layout — `NetworkTopologyStrategy { datacenter1: 3 }` with `LOCAL_QUORUM` reads/writes — so the dev keyspace can use the exact same `schema.cql` as production. The Datastax driver only needs to reach **one** node from the host (`cassandra-1` published on `127.0.0.1:19042`); that node acts as coordinator and reaches the other two replicas over the internal Docker network, so quorum is always satisfiable even though only one port is exposed.

---

**1) Start Docker infrastructure:**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

# Start infrastructure: Redpanda + topics, 3-node Cassandra + schema, Redis, Console
docker compose -f docker-compose.dev.yml up -d

# With monitoring (Prometheus + Grafana)
docker compose -f docker-compose.dev.yml -f config/docker-compose-monitoring.yml up -d
```

First boot takes ~2–3 minutes — `cassandra-1` → `cassandra-2` → `cassandra-3` start serially, and `cassandra-1` only reports healthy once `nodetool status` shows three `UN` nodes. The `cassandra-init` job then applies `schema.cql` against the fully-formed cluster.

**What starts by default:**
- `redpanda` on `localhost:19092` (Kafka-compatible broker) — topics created by `redpanda-init`
- `cassandra-1` on `localhost:19042` (only one node is host-published; the cluster has 3 nodes internally)
- `cassandra-init` (one-shot) applies `cmd/consumer/src/main/resources/db/cassandra/schema.cql` once all 3 nodes are `UN`
- `redis` on `localhost:6379` (session storage — required)
- `redpanda-console` on `localhost:8080` (Kafka topic browser)

**Optional profiles:**
- `monitoring` → `prometheus` on `localhost:9090`, `grafana` on `localhost:3000`

**Wait for the cluster to be ready before running the app:**

```bash
# Should print exactly three UN rows (Up / Normal)
docker exec wikistream-cassandra-1 nodetool status | grep '^UN' | wc -l   # → 3

# Confirm the schema-init job exited 0 (DDL applied)
docker logs wikistream-cassandra-init --tail=20
```

> **Astra alternative.** To point dev at DataStax Astra instead of the local cluster, skip the Cassandra containers (`docker compose -f docker-compose.dev.yml up -d redpanda redpanda-init redis redpanda-console`) and follow Option B's Astra setup. The app will activate the `astra` profile and ignore `spring.cassandra.contact-points`.

---

**2) Run Consumer app locally:**

Open a new terminal and run:

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

APP_JWT_ISSUER=wikistream-local \
APP_JWT_SECRET=local-jwt-secret-at-least-32-characters-long \
APP_JWT_ACCESS_TOKEN_TTL_SECONDS=3600 \
APP_AUTH_ENABLED=true \
SPRING_DATA_REDIS_HOST=localhost \
SPRING_DATA_REDIS_PORT=6379 \
SERVER_PORT=7001 \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=19042 \
CASSANDRA_KEYSPACE_NAME=wikistream \
CASSANDRA_LOCAL_DATACENTER=datacenter1 \
CASSANDRA_SCHEMA_ACTION=NONE \
./gradlew :cmd:consumer:bootRun
```

Consumer will start on **http://localhost:7001**.

Notes:
- `CASSANDRA_SCHEMA_ACTION=NONE` is the app default now — DDL is owned by the `cassandra-init` job (dev) or Astra Web UI (prod). Set it to `CREATE_IF_NOT_EXISTS` only if you're pointing at an empty keyspace with no init job.
- You may see driver log lines like `Cannot reach node cassandra-2/172.x.x.x:9042` from the host — that's expected (Docker bridge IPs aren't routable from macOS). Queries still succeed via the `cassandra-1` coordinator at `LOCAL_QUORUM`.

---

**3) Run Producer app locally:**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :cmd:producer:bootRun
```

Producer will start on **http://localhost:7002** with metrics at `/actuator/prometheus`.

---

**4) Verify everything is running (incl. the auth regression fix):**

```bash
# Consumer health (when running locally, port is 7001 per SERVER_PORT above)
curl http://localhost:7001/v1/status

# Producer health
curl http://localhost:7002/actuator/health

# Auth round-trip — these used to return 500 with the single-node Cassandra setup
curl -sS -X POST http://localhost:7001/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"password123"}'
# → 201 Created

curl -sS -X POST http://localhost:7001/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"password123"}'
# → 200 OK + JWT

# Redpanda Console (Kafka UI)
open http://localhost:8080
```

---

**5) Access monitoring (if started with `--profile monitoring`):**

```bash
# Grafana dashboard
open http://localhost:3000   # admin / admin

# Prometheus UI
open http://localhost:9090
```

---

**6) Stop everything:**

```bash
# Stop Gradle apps (Ctrl+C in each terminal), or:
bash scripts/stop-local-apps.sh

# Tear down infra + wipe volumes (Cassandra data, Redpanda data, etc.)
docker compose -f docker-compose.dev.yml --profile monitoring down -v --remove-orphans
```

Manual alternative:

```bash
# Kill apps by gradle process pattern
pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun'
pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun'

# OR kill by port
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true  # Consumer (SERVER_PORT above)
kill $(lsof -tiTCP:7002 -sTCP:LISTEN) 2>/dev/null || true  # Producer

docker compose -f docker-compose.dev.yml --profile monitoring down -v --remove-orphans
```

---

### Option B — Full Docker Stack (Production-like, Astra-backed)

All app services containerized — Redpanda, Redis, Consumer, Producer — on the shared `wikistream-net`. The datastore is **DataStax Astra** (managed cloud Cassandra), not a local Cassandra cluster: the production stack is intentionally stateless on the local machine so `docker compose down -v` can never destroy user data, and so the runtime topology matches a real multi-region Cassandra deployment.

**1) Prepare Astra credentials (first time only):**

```bash
cp config/auth-secrets.properties.template            config/auth-secrets.properties
cp config/cassandra-astra-secrets.properties.template config/cassandra-astra-secrets.properties
```

Edit `config/auth-secrets.properties` and set a 32+ character JWT secret.

Edit `config/cassandra-astra-secrets.properties`:

```properties
spring.profiles.active=astra
astra.db.secure-connect-bundle=./config/secure-connect-<your-db>.zip
astra.db.token=AstraCS:...your-token...
ASTRA_DB_KEYSPACE=wikistream
ASTRA_DB_LOCAL_DATACENTER=<your-astra-region>     # e.g. us-east-2
```

Place the matching `secure-connect-*.zip` bundle in `config/`. Both files are gitignored.

**2) Create schema in Astra (one-time):**

In Astra Web UI → **CQL Console** (or **Data Explorer**) → run the DDL from `cmd/consumer/src/main/resources/db/cassandra/schema.cql`. Skip the `CREATE KEYSPACE` and `USE` statements — Astra creates the keyspace for you when you provision the database, and replication is managed at the Astra level (multi-replica, multi-AZ).

**3) Start the stack:**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

docker compose up --build -d

# Override the host port for the consumer if needed
CONSUMER_PORT=8000 docker compose up --build -d
```

The consumer container mounts `./config:/app/config:ro`, picks up `cassandra-astra-secrets.properties`, activates the `astra` profile via `spring.profiles.active=astra`, and connects to Astra via the secure-connect bundle. `spring.cassandra.contact-points` is ignored on this profile.

**4) Verify:**

```bash
docker compose ps

# Consumer (default host port 7001 → container 7000)
curl http://localhost:7001/v1/status

# Auth round-trip against Astra
curl -sS -X POST http://localhost:7001/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'

# Kafka topic listing + UI
docker exec wikistream-redpanda rpk topic list
open http://localhost:8080
```

**5) Astra warm-up (if the DB has hibernated):**

```bash
bash scripts/astra-warmup-check.sh
```

**6) Stop:**

```bash
docker compose down                       # keep Redpanda volumes
docker compose down -v --remove-orphans   # wipe local volumes; Astra data is untouched
```

> **Why no local Cassandra in this stack?** Production parity with the real datastore — see [Astra notes above](#option-b--full-docker-stack-production-like-astra-backed). For a fully self-hosted prod-equivalent topology, use `docker-compose.dev.yml`, which runs a 3-node `NetworkTopologyStrategy` cluster with the same RF=3 / `LOCAL_QUORUM` semantics Astra provides.

---

## Monitoring with Prometheus & Grafana

### Overview

The project includes optional monitoring with **Prometheus** (metrics collection) and **Grafana** (visualization dashboards). Both Producer and Consumer expose metrics via Spring Boot Actuator.

**Metrics Endpoints:**
- Producer: `http://localhost:7002/actuator/prometheus`
- Consumer: `http://localhost:7001/actuator/prometheus` (or `:7000` when running locally)

### Quick Start with Monitoring

**Start dev infrastructure with monitoring:**

```bash
docker compose -f docker-compose.dev.yml --profile monitoring up -d
```

**Access monitoring tools:**
- **Grafana**: `http://localhost:3000` (admin / admin)
- **Prometheus**: `http://localhost:9090`

### Pre-configured Dashboard

The project ships one ready-to-use Grafana dashboard — **"Wikistream Pipeline"** (uid `wikistream-pipeline`) — at:
```
config/grafana/dashboards/rpcn_connector.json
```

It is a single end-to-end board with three rows following the data flow:

- **Producer — Wikimedia SSE → Redpanda:** events consumed from SSE, published to Redpanda, publish failures, SSE reconnects (`wikistream_producer_*`).
- **RPCN — Protobuf validation:** events in (`input_received`), events out by destination (`output_sent` — `validated_out` vs the DLQ case), validation/DLQ rate, and decode latency — from RPCN's native metrics (scraped via the `rpcn` job in `config/prometheus.yml` / `config/prometheus-dev.yml`, target `rpcn:4195`). See the [Redpanda Connect validation stage](#redpanda-connect-rpcn--protobuf-validation-stage).
- **Consumer — persistence:** events consumed from `validated`, persisted to Cassandra, persist failures, batches processed, and batch duration (`wikistream_consumer_*`).

Together the rows give an end-to-end **flow-conservation** view: `producer published ≈ rpcn input_received ≈ rpcn validated_out ≈ consumer events_consumed`.

### Loading the Dashboard

#### Option 1: Auto-provisioning (Recommended)

When you start Grafana with docker-compose, the dashboard loads automatically:

```bash
# Start with monitoring profile
docker compose -f docker-compose.dev.yml --profile monitoring up -d

# Open Grafana
open http://localhost:3000
# Login: admin / admin

# Dashboard is already loaded!
# Go to: Dashboards → Wikistream Pipeline
```

#### Option 2: Manual Import

If running Grafana separately or want to import a modified version:

1. **Open Grafana**: `http://localhost:3000`
2. **Login**: admin / admin (change password on first login)
3. **Navigate**: Click **☰** menu → **Dashboards** → **Import**
4. **Upload JSON**:
   - Click **Upload JSON file**
   - Select: `config/grafana/dashboards/rpcn_connector.json`
   - Or paste JSON content directly
5. **Configure**:
   - Select **Prometheus** as the data source
   - Click **Import**

**Dashboard will appear immediately with live metrics!**

### Viewing Metrics

**Prometheus UI** (`http://localhost:9090`):

```promql
# Producer: Events from Wikipedia SSE (per second)
rate(wikistream_producer_events_consumed_sse_total{application="producer"}[1m])

# Producer: Events published to Redpanda (per second)
rate(wikistream_producer_events_published_total{application="producer"}[1m])

# Producer: Publish failures (per second)
rate(wikistream_producer_events_publish_failed_total{application="producer"}[1m])

# Producer: SSE reconnects (per 5m window)
increase(wikistream_producer_sse_reconnects_total{application="producer"}[5m])

# Consumer: Events consumed from Redpanda (per second)
rate(wikistream_consumer_events_consumed_total{application="consumer"}[1m])

# Consumer: Events persisted to Cassandra (per second)
rate(wikistream_consumer_events_persisted_cassandra_total{application="consumer"}[1m])

# Consumer: Batches processed (per second)
rate(wikistream_consumer_batches_processed_total{application="consumer"}[1m])

# Consumer: Batch processing duration p99 (seconds)
histogram_quantile(0.99, rate(wikistream_consumer_batch_duration_seconds_bucket{application="consumer"}[5m]))

# Consumer: Persist-failure rate percentage
100 * (
  rate(wikistream_consumer_events_persist_failed_total{application="consumer"}[5m])
  /
  (rate(wikistream_consumer_events_consumed_total{application="consumer"}[5m])
   + rate(wikistream_consumer_events_persist_failed_total{application="consumer"}[5m]))
)
```

### Available Metrics

> Metric names are namespaced by application (`wikistream.producer.*` / `wikistream.consumer.*`); the Prometheus-exposed form replaces dots with underscores and appends `_total` for counters (e.g. `wikistream_consumer_events_consumed_total`). All series retain the `application` tag.

**Producer Metrics:**
| Metric | Type | Description |
|--------|------|-------------|
| `wikistream_producer_events_consumed_sse_total` | Counter | Events consumed from Wikipedia SSE stream |
| `wikistream_producer_events_published_total` | Counter | Events successfully published to Redpanda |
| `wikistream_producer_events_publish_failed_total` | Counter | Events that failed to publish to Redpanda |
| `wikistream_producer_sse_reconnects_total` | Counter | Times the producer reconnected to the Wikimedia SSE stream |

**Consumer Metrics:**
| Metric | Type | Description |
|--------|------|-------------|
| `wikistream_consumer_events_consumed_total` | Counter | Events consumed from Redpanda |
| `wikistream_consumer_events_persisted_cassandra_total` | Counter | Events persisted to Cassandra |
| `wikistream_consumer_events_persist_failed_total` | Counter | Events that failed to persist to Cassandra |
| `wikistream_consumer_batches_processed_total` | Counter | Batches of events consumed from Redpanda |
| `wikistream_consumer_batch_duration_seconds` | Timer | Batch processing duration (p50/p95/p99) |

**Spring Boot Actuator Metrics (automatic):**
- `jvm_memory_used_bytes` - JVM memory usage
- `jvm_gc_pause_seconds` - Garbage collection metrics
- `system_cpu_usage` - System CPU usage
- `process_cpu_usage` - Process CPU usage
- `http_server_requests_seconds` - HTTP request metrics

### Customizing Dashboards

**Export modified dashboards:**

1. **Edit in Grafana UI** - make your changes
2. **Click ⚙️ (Settings)** → **JSON Model**
3. **Copy the JSON**
4. **Save to file**: `config/grafana/dashboards/my-custom-dashboard.json`
5. **Restart Grafana** - new dashboard auto-loads

### Monitoring Configuration Files

```
config/
├── prometheus.yml                    # Prometheus scrape config (full stack; incl. rpcn:4195)
├── prometheus-dev.yml                # Prometheus scrape config (dev stack; incl. rpcn:4195)
└── grafana/
    ├── datasources/
    │   └── prometheus.yml            # Prometheus datasource config
    ├── dashboards.yml                # Dashboard provider config
    └── dashboards/
        └── rpcn_connector.json       # "Wikistream Pipeline" dashboard (producer → RPCN → consumer)
```

### Troubleshooting

**Dashboard shows "No data":**
- Ensure Producer and Consumer are running
- Check Prometheus targets: `http://localhost:9090/targets`
- Verify metrics endpoints are accessible:
  ```bash
  curl http://localhost:7002/actuator/prometheus  # Producer
  curl http://localhost:7001/actuator/prometheus  # Consumer
  ```

**Prometheus can't scrape local apps:**
- If running apps locally (not in Docker), update `config/prometheus.yml`:
  ```yaml
  - targets: ['host.docker.internal:7002']  # Producer
  - targets: ['host.docker.internal:7001']  # Consumer
  ```
---

## Kubernetes Deployment (Minikube)

Deploys the full stack to a local Minikube cluster in dependency order: cluster +
namespace + locally-built images → stateful deps (Redpanda, Redis, 3-node Cassandra)
→ monitoring (kube-prometheus-stack) → apps (producer, consumer).

Manifests live in `k8s/`. The consumer/producer images are built **inside Minikube's
Docker daemon** (`imagePullPolicy: Never`), so no registry is required.

### Prerequisites

- `minikube`, `kubectl`, `helm`, and `docker` on your `PATH`
- Docker Desktop with enough memory allocated (Settings → Resources): the script
  defaults to a **4 CPU / 12 GiB** Minikube node, and Docker's own VM must be larger
  than that. 12 GiB fits the whole stack — 3-node Cassandra (~4.5 GiB) + Redpanda +
  Redis + kube-prometheus-stack (~2.5 GiB) + producer + 2 consumers (measured peaking
  at ~9.2 GiB). A node too small **OOM-kills the Cassandra pods** — they then come back
  with new IPs, lose quorum, and every request fails with a 500 (see Troubleshooting).
  Stop the local Docker Compose dev stack first
  (`docker compose -f docker-compose.dev.yml down`) so the two don't contend for
  memory on the same Docker VM.
- **Resizing the node requires a recreate** — `minikube start --memory` is a no-op on
  an already-created cluster. The deploy script handles this: if the running node is
  smaller than `MINIKUBE_MEMORY`, it deletes and recreates it automatically.

### Run

```bash
bash scripts/k8s-deploy.sh
```

Tunable via env vars (all optional):

```bash
# e.g. give the node more room and turn on metrics-server
MINIKUBE_MEMORY=12288 MINIKUBE_CPUS=6 K8S_VERSION=v1.33.1 \
  ENABLE_METRICS_SERVER=true bash scripts/k8s-deploy.sh
```

> Don't set `MINIKUBE_MEMORY` below ~8192 — the 3-node Cassandra StatefulSet will
> OOM-kill and the rollout will time out.

The script is **idempotent and self-healing**:

- Preflight-checks that `minikube/kubectl/helm/docker` exist.
- If an existing cluster's API server is **not** Running (a wedged/half-initialised
  cluster), it deletes it for a clean start; if the API server is still unhealthy
  after start, it resets once and retries. Health is judged by `/healthz`, not by
  `minikube start`'s exit code (addon warnings alone don't count as failure).
- Pins the Kubernetes version (`--kubernetes-version`) and starts with `--wait=all`.
- Waits for the API server `/healthz` to return `ok` **before** applying anything.
- Enables `storage-provisioner` and `default-storageclass` **after** the API server
  is confirmed healthy.
- Keeps **`metrics-server` out of the `minikube start` bootstrap** (it's opt-in via
  `ENABLE_METRICS_SERVER=true`). Enabling it during bootstrap registers the
  `metrics.k8s.io` APIService before its pod is ready, which corrupts the API
  server's aggregated `/openapi/v2` and makes every other addon apply fail with
  `failed to download openapi`. When opted in, it is enabled only after the cluster
  is healthy and its rollout is awaited.
- Applies the **full `schema.cql` (keyspace + all tables)** to Cassandra once the
  StatefulSet is ready — not just the keyspace. Creating only the keyspace leaves the
  app's tables missing and `GET /v1/stats` returns 500 (`table ... does not exist`).
  The dev-only leading `DROP`s are filtered out and every `CREATE` is `IF NOT EXISTS`,
  so it's idempotent; the script then verifies `stats_counters` exists before moving on.

### Validate

```bash
# 0. Manifests are structurally valid (needs a running cluster context).
#    k8s/servicemonitors.yaml is validated separately — its ServiceMonitor CRD is
#    installed by the monitoring step, so it won't resolve on a bare cluster.
kubectl apply --dry-run=client \
  -f k8s/cassandra.yaml -f k8s/redpanda.yaml -f k8s/redpanda-init-job.yaml \
  -f k8s/redis.yaml -f k8s/producer.yaml -f k8s/consumer.yaml
kubectl apply --dry-run=client -f k8s/servicemonitors.yaml   # only after Step 2 (monitoring)

# 1. Everything is scheduled and healthy
kubectl config set-context --current --namespace=wikistream
kubectl get pods -o wide
kubectl get statefulset,deployment,svc

# 2. Rollouts completed
kubectl rollout status statefulset/redpanda
kubectl rollout status statefulset/cassandra --timeout=600s
kubectl rollout status deployment/consumer
kubectl rollout status deployment/producer

# 3. Cassandra cluster formed (expect three UN rows)
kubectl exec -it cassandra-0 -- nodetool status

# 4. App health via port-forward
kubectl port-forward deploy/consumer 7001:7000 &
curl -s localhost:7001/actuator/health/liveness

# 5. Node/pod resource metrics — only if deployed with ENABLE_METRICS_SERVER=true
kubectl top nodes
kubectl top pods

# 6. Grafana (kube-prometheus-stack; admin / admin)
kubectl port-forward svc/monitoring-grafana 3000:80 &
# open http://localhost:3000
```

### Stop and re-run from scratch

Stop the running script with **`Ctrl+C`** — it runs in the foreground, so this
interrupts it. Note that `Ctrl+C` only stops the *script*; the Minikube cluster and
whatever it already applied keep running.

Re-run at the level of "scratch" you need:

```bash
# A) Full from scratch — destroy the cluster (and images built inside it, and PVC
#    data), then rebuild everything. Use after an interrupted/half-done start.
minikube delete
bash scripts/k8s-deploy.sh

# B) Redeploy the app stack only — keep the cluster and the already-built images
#    (much faster). Wipes Redpanda/Redis/Cassandra/monitoring/apps in the namespace.
kubectl delete namespace wikistream
bash scripts/k8s-deploy.sh
```

The script is idempotent, so re-running is safe: `helm upgrade --install` reconciles
the monitoring release, the `redpanda-init` Job is deleted before re-apply, and
namespace creation is a no-op if it exists. If you interrupted `minikube start` and
left a wedged cluster (`minikube status` shows `apiserver: Stopped`), just re-run —
the script deletes and recreates it automatically.

### Teardown

```bash
kubectl delete namespace wikistream        # remove the app stack
minikube delete                            # destroy the whole cluster
```

### Troubleshooting

**Addons fail during `minikube start` with `failed to download openapi ... dial tcp
...:8443: connect: connection refused`** (e.g. `Enabling 'metrics-server' returned an
error`, `storage-provisioner`, `default-storageclass`, and `Enabled addons:` ends up
empty):

There are two distinct causes:

1. **`metrics-server` was enabled in the minikube profile and got re-applied during
   bootstrap.** Its `metrics.k8s.io` APIService is registered before the pod is ready,
   which breaks the API server's aggregated `/openapi/v2`; every other addon's
   client-side validation then fails to download the schema. This is the most common
   cause of a *repeat* failure after a previously-working cluster. Fix — keep it out
   of bootstrap and enable it later on a healthy cluster:
   ```bash
   minikube addons disable metrics-server
   minikube stop && minikube start --wait=all      # now bootstraps clean
   # optional, once the cluster is up:
   minikube addons enable metrics-server
   kubectl -n kube-system rollout status deployment/metrics-server
   ```
   The deploy script keeps `metrics-server` out of bootstrap by default and disables
   any stale copy before starting, so a plain re-run is normally enough. Only opt in
   with `ENABLE_METRICS_SERVER=true` (it's enabled post-bootstrap, after `/healthz`).

2. **A corrupted/wedged cluster** — the API server genuinely never came up, so nothing
   could be applied. Often a version drift (a cluster created by an older Minikube)
   leaving a kubelet that crash-loops on a missing
   `/etc/kubernetes/bootstrap-kubelet.conf`. Confirm and fix:
   ```bash
   minikube status                   # apiserver: Stopped while host: Running ⇒ wedged
   minikube logs | grep -i kubelet   # crash-looping kubelet / missing bootstrap conf
   minikube delete && bash scripts/k8s-deploy.sh   # clean recreate
   ```

The deploy script detects and recovers from both — disabling stale `metrics-server`
and deleting a wedged cluster — so a re-run is normally enough.

**Cassandra rollout times out — `Waiting for N pods to be ready...` flaps up and down,
then `error: timed out waiting for the condition`:**

The Cassandra pods are being **OOM-killed**. Confirm:

```bash
kubectl get pods -l app=cassandra          # look for STATUS OOMKilled and rising RESTARTS
kubectl describe pod cassandra-2 | grep -iE 'OOMKilled|Last State'
```

Cause: the Minikube node is too small for the stack. Each Cassandra pod uses ~1.3–1.8
GiB and there are three of them, plus Redpanda/Redis — a 6 GiB node can't hold it. The
subtlety is that the kubelet reports the **whole Docker VM** (e.g. 16 GiB) as node
capacity, so the scheduler places all three pods; they then blow past the Minikube
container's real cgroup limit and the kernel OOM-kills a Cassandra JVM.

Fix — give the node enough memory (and remember resizing needs a recreate):

```bash
minikube delete
MINIKUBE_MEMORY=12288 bash scripts/k8s-deploy.sh
```

The script now recreates the node automatically when the running one is smaller than
`MINIKUBE_MEMORY` (default 12 GiB), and the Cassandra manifest uses a constrained
768M heap so three replicas fit comfortably.

**Producer rollout hangs — `Waiting for deployment "producer" rollout to finish: 0 of 1
updated replicas are available...`:**

The pod is `Running` (check `kubectl get pods -l app=producer`) but never becomes
`Ready`, so the Deployment stays `Available: False` and `rollout status` blocks. Cause:
the producer is a background SSE→Kafka pump driven by a blocking `CommandLineRunner`,
so Spring's `ApplicationReadyEvent` never fires and `/actuator/health/readiness` returns
**503** forever. Its readiness probe must therefore gate on **liveness**, not the
readiness group — the producer serves no inbound traffic, so "up and serving the
management port" is the right readiness signal. This is already configured in
`k8s/producer.yaml`; if you see the hang, confirm the probe:

```bash
kubectl get pod -l app=producer -o jsonpath='{.items[0].spec.containers[0].readinessProbe.httpGet.path}'
# expected: /actuator/health/liveness   (NOT /actuator/health/readiness)
```

**`GET /v1/stats` returns 500 `stats_snapshot_error` / `Failed to fetch stats view`:**

Check the consumer logs for the real cause — it's one of two Cassandra issues:

```bash
kubectl logs -l app=consumer -n wikistream --tail=50 | grep -iE 'does not exist|UnavailableException|LOCAL_QUORUM'
```

- **`InvalidQueryException: table stats_counters does not exist`** — the keyspace was
  created but the tables weren't. The deploy script now applies the full `schema.cql`;
  if you hit this on an older cluster, apply it manually (dev-only `DROP`s filtered so
  it's non-destructive):
  ```bash
  grep -viE '^[[:space:]]*DROP ' cmd/consumer/src/main/resources/db/cassandra/schema.cql \
    | kubectl exec -i -n wikistream cassandra-0 -- cqlsh
  kubectl exec -n wikistream cassandra-0 -- cqlsh \
    -e "SELECT table_name FROM system_schema.tables WHERE keyspace_name='wikistream';"
  ```
- **`UnavailableException: Not enough replicas available ... LOCAL_QUORUM (2 required
  but only 1 alive)`** — Cassandra lost quorum. Usually a consequence of OOM: pods were
  killed (`kubectl get pods -l app=cassandra` shows restarts / `exitCode 137`), came
  back with new IPs, and gossip still lists the old IPs as `DN`
  (`kubectl exec cassandra-0 -- nodetool status`). Give the node more memory
  (`MINIKUBE_MEMORY=12288`, requires a recreate) and reform the cluster:
  ```bash
  kubectl delete statefulset cassandra -n wikistream --cascade=foreground
  kubectl delete pvc -l app=cassandra -n wikistream        # dev data only
  kubectl apply -f k8s/cassandra.yaml
  kubectl rollout status statefulset/cassandra --timeout=600s
  # re-apply schema (above), then restart consumers so they reconnect:
  kubectl rollout restart deployment/consumer -n wikistream
  ```

Note this is **not** a port problem — the consumer reaches Cassandra fine; the failure
is missing tables or lost quorum.

---

## Configuration Reference

### Supported Environments

| Variable | Default | Where | Notes |
|----------|---------|-------|-------|
| `APP_AUTH_ENABLED` | `true` | Both | Enable `/v1/auth/*` endpoints |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Both | Redis host (set to `redis` in Docker) |
| `SPRING_DATA_REDIS_PORT` | `6379` | Both | Redis port |
| `CONSUMER_PORT` | `7001` | Full stack only | Host port for consumer (container always 7000) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | Dev only | Redpanda broker endpoint |
| `CASSANDRA_CONTACT_POINTS` | `127.0.0.1` | Dev only | Cassandra host |
| `CASSANDRA_PORT` | `19042` | Dev only | Cassandra port (19042 in Docker) |

### Secrets Files

| File | Template | Used by | Purpose |
|------|----------|---------|---------|
| `config/auth-secrets.properties` | `*.template` | Both | JWT secret, issuer, TTL |
| `config/cassandra-astra-secrets.properties` | `*.template` | Full stack | Astra credentials + bundle path |

---

## Running with Local Cassandra (Optional)

If you prefer local Homebrew Cassandra instead of Docker:

```bash
# Install
brew services start cassandra
brew services start kafka  # if using Kafka instead of Redpanda

# Initialize schema once
cqlsh 127.0.0.1 9042 -f cmd/consumer/src/main/resources/db/cassandra/schema.cql

# Run consumer with local endpoints
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=9042 \
./gradlew :cmd:consumer:bootRun
```

---

## Troubleshooting

### Dev Stack Issues

- **Consumer hangs at startup:**
  - Ensure `docker compose -f docker-compose.dev.yml up -d` succeeded
  - Check: `docker compose -f docker-compose.dev.yml ps`
  - Wait ~60s for Cassandra health checks to pass

- **Port already in use:**

```bash
# Consumer on 7001
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true

# Redpanda on 19092
kill $(lsof -tiTCP:19092 -sTCP:LISTEN) 2>/dev/null || true
```

- **Kill local gradle apps (consumer and producer):**

When running `./gradlew :cmd:consumer:bootRun` and `./gradlew :cmd:producer:bootRun` locally, use:

```bash
# Easiest: use the helper script
bash scripts/stop-local-apps.sh

# OR by gradle process pattern
pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun'
pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun'

# OR by listening port
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true    # Consumer on 7001
```

- **Test with wrong JWT secret:**
  - Old tokens signed with different secret will fail
  - Login again to get new token

- **Clean up Redpanda topics:**

You can delete and purge topics using the Redpanda Console UI or CLI:

**Option 1: Using Redpanda Console UI (Easiest)**

1. Open http://localhost:8080 (Redpanda Console)
2. Navigate to **Topics** tab
3. Select the topic you want to delete (e.g., `wiki.recentchange.proto` or `wiki.recentchange.dlq`)
4. Click the **Delete** button on the topic details page
5. Confirm deletion

**Option 2: Using the rpk CLI (docker exec)**

```bash
# List all topics
docker exec wikistream-redpanda rpk topic list

# Delete a specific topic
docker exec wikistream-redpanda rpk topic delete wiki.recentchange.proto

# Delete multiple topics at once
docker exec wikistream-redpanda rpk topic delete wiki.recentchange.proto wiki.recentchange.dlq

# Verify deletion
docker exec wikistream-redpanda rpk topic list
```

**Option 3: Purge topic data (keep topic, clear messages)**

If you want to keep the topic but remove all messages:

```bash
# Purge all messages from a topic (data only, schema intact)
docker exec wikistream-redpanda rpk topic delete-records wiki.recentchange.proto --before-timestamp 0
```

**After cleanup, reinitialize topics:**

```bash
# If you deleted the topics, recreate them
docker exec wikistream-redpanda rpk topic create wiki.recentchange.proto --partitions 6 --replicas 1 || true
docker exec wikistream-redpanda rpk topic create wiki.recentchange.dlq --partitions 1 --replicas 1 || true

# Verify
docker exec wikistream-redpanda rpk topic list
```

### Full Stack Issues

- **Containers fail to start:**

```bash
docker compose logs consumer    # Check app logs
docker compose logs producer
docker compose logs redpanda
```

- **Astra connection refused:**
  - Verify bundle path in `config/cassandra-astra-secrets.properties`
  - Verify token format: `AstraCS:...`
  - Try warm-up: `bash scripts/astra-warmup-check.sh`

---

## API Reference

### Health

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/v1/status` | ❌ | Returns `{"status":"ok"}` |

### Auth

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/v1/auth/register` | ❌ | Register `{ "email", "password" }` → `201` |
| `POST` | `/v1/auth/login` | ❌ | Login → `{ "accessToken": "..." }` |
| `POST` | `/v1/auth/logout` | ✅ Bearer | Revoke current token → `204` |

### Stats

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/v1/stats` | ✅ Bearer | Returns authenticated user's `StatsView` |

`StatsView` is a read-side projection assembled on demand from the Cassandra COUNTER + append-only tables (it is never written directly). Fields:

| Field | Type | Description |
|-------|------|-------------|
| `userEmail` | `String` | Authenticated user the view belongs to |
| `bucketDay` | `String` | Day bucket the stats are aggregated under (`YYYY-MM-DD`) |
| `totalMessages` | `Long` | Messages received while logged in |
| `distinctUsers` | `Int` | Distinct Wikipedia usernames seen |
| `botCount` | `Long` | Bot-authored edits |
| `nonBotCount` | `Long` | Human-authored edits |
| `countByServerUrl` | `Map<String,Long>` | Edits per Wikipedia server |

### Quick flow

```bash
# Register
curl -X POST http://localhost:7000/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"StrongPass#123"}'

# Login — copy accessToken from response
curl -X POST http://localhost:7000/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"StrongPass#123"}'

# Stats
curl http://localhost:7000/v1/stats \
  -H "Authorization: Bearer <accessToken>"
```

---

## Application Configuration Details

### Producer Application

**File:** `cmd/producer/src/main/resources/application.properties`

| Property | Default | Env override | Notes |
|----------|---------|--------------|-------|
| `spring.kafka.bootstrap-servers` | `localhost:19092` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Redpanda broker |
| `wiki.stream.url` | `https://stream.wikimedia.org/v2/stream/recentchange` | — | Wikimedia SSE endpoint |
| `wiki.stream.user-agent` | `wikistream-producer/local` | — | User-Agent header for SSE |

### Consumer Application

**File:** `cmd/consumer/src/main/resources/application.properties`

| Property | Default | Env override | Notes |
|----------|---------|--------------|-------|
| `spring.kafka.bootstrap-servers` | `localhost:19092` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Redpanda broker |
| `spring.kafka.consumer.group-id` | `wiki-consumer` | — | Kafka consumer group |
| `app.kafka.consumer.topic` | `wiki.recentchange.validated` | `APP_KAFKA_CONSUMER_TOPIC` | Topic the consumer reads (RPCN output). Flip to `wiki.recentchange.proto` to bypass RPCN — see [rollback](#redpanda-connect-rpcn--protobuf-validation-stage) |
| `app.kafka.consumer.concurrency` | `4` | `APP_KAFKA_CONSUMER_CONCURRENCY` | Number of concurrent batch listener containers |
| `spring.kafka.consumer.max-poll-records` | `500` | — | Batch size |
| `spring.cassandra.contact-points` | `localhost` | `CASSANDRA_CONTACT_POINTS` | Local: `127.0.0.1` or `cassandra` (Docker) |
| `spring.cassandra.port` | `9042` | `CASSANDRA_PORT` | Docker maps container 9042 → host 19042 |
| `spring.cassandra.keyspace-name` | `wikistream` | `CASSANDRA_KEYSPACE_NAME` | Keyspace name |
| `spring.cassandra.local-datacenter` | `datacenter1` | `CASSANDRA_LOCAL_DATACENTER` | Required for driver |
| `spring.data.redis.host` | `localhost` | `SPRING_DATA_REDIS_HOST` | Redis host (set to `redis` in Docker) |
| `spring.data.redis.port` | `6379` | `SPRING_DATA_REDIS_PORT` | Redis port (required for session storage) |
| `server.port` | `7000` | `SERVER_PORT` | HTTP server port |
| `app.auth.enabled` | `true` | `APP_AUTH_ENABLED` | Enable/disable auth endpoints |
| `app.security.jwt.issuer` | _(required)_ | `APP_JWT_ISSUER` | JWT issuer claim |
| `app.security.jwt.secret` | _(required)_ | `APP_JWT_SECRET` | JWT signing secret (≥32 chars) |
| `app.security.jwt.access-token-ttl-seconds` | `3600` | `APP_JWT_ACCESS_TOKEN_TTL_SECONDS` | Token expiry (seconds) |

### Secrets Files (git-ignored)

| File | Template | When needed | Purpose |
|------|----------|-------------|---------|
| `config/auth-secrets.properties` | `*.template` | Always | JWT secrets, issuer, TTL — for both dev and production |
| `config/cassandra-astra-secrets.properties` | `*.template` | Astra only | Astra credentials: token, bundle path, keyspace, datacenter |

---

## Tests

### Quick Reference

```bash
# Run all unit tests (no Docker required)
./gradlew test

# Run all integration tests (Docker required - Testcontainers)
./gradlew integrationTest

# Run everything (CI pipeline)
./gradlew ciTest
```

### Unit tests (no Docker required)

Unit tests mock external dependencies and run in-memory:

```bash
# Core library tests
./gradlew :lib:core:test

# Producer tests (parser, publisher, SSE client, metrics)
./gradlew :cmd:producer:test

# Consumer tests (batch consumer, services, security, auth)
./gradlew :cmd:consumer:test
```

Or all at once via the root aggregator:

```bash
./gradlew test
```

### Integration tests (Docker required)

Integration tests spin up real Cassandra, Redis, and Redpanda containers via Testcontainers — no `docker compose up` needed beforehand.

**Consumer Integration Tests:**
```bash
./gradlew :cmd:consumer:integrationTest
```

Tests real Cassandra repositories, Redis sessions, and AuthService with actual containers.

**Producer Integration Tests:**
```bash
./gradlew :cmd:producer:integrationTest
```

Tests the complete producer pipeline:
- Real Wikipedia SSE stream connection
- Event parsing and validation
- Redpanda topic publishing (via Testcontainers)
- Metrics collection

**Run all integration tests:**
```bash
./gradlew integrationTest
```

### Coverage

| Suite | Tests | Scope |
|-------|-------|-------|
| `lib:core:test` | 6 | TopicsTest (proto, dlq, validated), ProtoWikiEventMapper serialization/deserialization |
| `cmd:producer:test` | 12 | Parser, publisher, SSE client, configuration, ingestion runner, metrics service |
| `cmd:producer:integrationTest` | 1 | End-to-end producer pipeline with real Redpanda (Testcontainers) |
| `cmd:consumer:test` | 45 | Batch consumer (proto topic), services, security, auth, error handling |
| `cmd:consumer:integrationTest` | 28 | Cassandra repos, Redis sessions, AuthService (with real containers) |
| **Total** | **92** | Comprehensive coverage: protobuf serialization, Redpanda topics, DLQ, JWT auth, session management, metrics |

### Test Structure

**Unit Tests:**
- Fast execution (< 30 seconds total)
- No external dependencies
- Mocked Kafka, Cassandra, Redis
- Focus: business logic, parsing, serialization

**Integration Tests:**
- Slower execution (Testcontainers startup overhead)
- Real external services (Cassandra, Redis, Redpanda)
- Focus: end-to-end flows, data persistence, message publishing

### Running Tests in CI

```bash
# Full CI pipeline (unit + integration + linting)
./gradlew ciTest lintKotlin

# With stacktrace for debugging
./gradlew ciTest --stacktrace
```

---

## Code Quality

```bash
# detekt (static analysis) + ktlint (formatting)
./gradlew lintKotlin

# With full stacktrace for CI logs
./gradlew lintKotlin --stacktrace
```

detekt config: `config/detekt/detekt.yml`

---


## Postman Collection

`postman_collection.json` covers the complete auth + stats flow with automatic token extraction.

**Import:** Postman → **Import** → select `postman_collection.json`

| Variable | Default                   | Description |
|----------|---------------------------|-------------|
| `BASE_URL` | `http://localhost:7001`   | App base URL |
| `TEST_EMAIL` | `local-test-@example.com` | Auto-replaced with timestamped email on first run |
| `TEST_PASSWORD` | `StrongPass#123`          | Used for register and login |
| `ACCESS_TOKEN` | _(set by Login request)_  | Bearer token, extracted automatically |

**Request order:**
1. Health Check
2. Register User
3. Login (saves `ACCESS_TOKEN`)
4. Fetch Stats
5. Logout
6. Verify Revoked Token → expects `401`

---

## Further Reading

| Document | Description |
|----------|-------------|
| [`JWT_BEARER_SCHEME.md`](JWT_BEARER_SCHEME.md) | Token structure, signing, validation, revocation lifecycle |
| [`ACTIVE_USER_SESSIONS.md`](ACTIVE_USER_SESSIONS.md) | Session tracking via Redis, metadata schema, configuration |
| [`DOCKER_SETUP.md`](DOCKER_SETUP.md) | Docker Compose configurations, profiles, environment variables |
