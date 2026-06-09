# wikistreamkotlin

Spring Boot + Kotlin application that consumes the [Wikimedia recent-change stream](https://stream.wikimedia.org/v2/stream/recentchange), pipes events through **Redpanda** (Kafka-compatible broker), and aggregates per-user stats stored in Cassandra.

Events are recorded only while a user is logged in. Each authenticated user sees only their own `StatsSnapshot`. Stats pause on logout and resume on the next login.

---

## Table of Contents

- [Module Structure](#module-structure)
- [Tech Stack](#tech-stack)
- [Library Versions](#library-versions)
- [Prerequisites](#prerequisites)
- [Project Setup](#project-setup)
- [Quick Start](#quick-start)
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
│               ├── RedpandaBatchConsumer.kt    # @KafkaListener batch — processes events
│               ├── config/
│               │   ├── ConsumerKafkaConfig.kt           # ConsumerFactory + batchKafkaListenerContainerFactory
│               │   ├── RedisConfig.kt                   # Lettuce connection + RedisTemplate
│               │   ├── AstraDbConfig.kt                 # CqlSession customizer for Astra
│               │   ├── AstraDbProperties.kt
│               │   ├── AuthProperties.kt
│               │   └── UserAccountAtomicRepositoryAutoConfiguration.kt
│               ├── controller/
│               │   ├── AuthController.kt       # /v1/auth/*
│               │   ├── StatsController.kt      # /v1/stats, /v1/status
│               │   └── dto/AuthDtos.kt
│               ├── domain/
│               │   ├── UserAccount.kt
│               │   ├── StatsSnapshot.kt
│               │   └── RevokedToken.kt
│               ├── repository/
│               │   ├── SessionRepository.kt              # Interface
│               │   ├── RedisSessionRepository.kt         # Redis-backed (default)
│               │   ├── InMemorySessionRepository.kt      # In-memory fallback
│               │   ├── UserAccountAtomicRepository.kt    # Interface (CAS insert)
│               │   ├── UserAccountCassandraRepository.kt # Spring Data
│               │   ├── StatsRepository.kt                # Interface
│               │   ├── CassandraStatsRepository.kt       # Optimistic-lock retry loop
│               │   ├── StatsSnapshotCassandraRepository.kt
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
│               │   └── ActiveUserSessionService.kt
│               └── exception/
│                   ├── GlobalErrorHandler.kt
│                   ├── ConsumerErrorLogger.kt
│                   └── DlqPublisher.kt
│               └── serializer/
│                   └── ProtoWikiEventDeserializer.kt  # protobuf binary → WikiEvent
│
└── lib/
    └── core/                      # Shared contracts — no Spring Boot dependency
        ├── src/main/proto/
        │   └── wikievent.proto           # Protobuf schema for WikiEvent message
        └── src/main/kotlin/
            └── core/
                ├── Topics.kt                  # Topic name constants
                ├── domain/
                │   ├── WikiEvent.kt
                │   ├── WikiEventMeta.kt
                │   └── ... (other POKOs)
                ├── mapper/
                │   └── ProtoWikiEventMapper.kt   # Protobuf serialization ↔ domain model
                └── exception/
                    └── ... (error hierarchy)
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

### Messaging
| Component | Version | Role |
|-----------|---------|------|
| **Redpanda** | v24.3.14 | Kafka-compatible message broker; decouples producer from consumer via pub-sub topics |
| **Apache Kafka / Spring Kafka** | 4.0.4 | Protocol layer + client libraries for Redpanda integration |
| **Protocol Buffers (Protobuf)** | proto3 | Binary serialization for `WikiEvent` messages; 75% smaller and 5-10x faster than JSON |

### Event Streams
| Topic | Partitions | Replicas | Format | Retention | Purpose | Notes |
|-------|-----------|----------|--------|-----------|---------|-------|
| `wiki.recentchange.proto` | 6 | 1 | Protobuf binary | 7 days | **Canonical stream** — all Wikipedia recent-change events encoded as protobuf `WikiEvent` | Compressed with zstd; recommended for all new consumers |
| `wiki.recentchange.raw` | 3 | 1 | JSON | 1 day | ~~Legacy JSON stream~~ **DEPRECATED** — kept for backward compatibility only | Migrate consumers to `proto` topic |
| `wiki.recentchange.dlq` | 1 | 1 | Protobuf binary | default (7 days) | Dead-letter queue for unprocessable records | No explicit retention/compression configured; uses Redpanda defaults. Proto deserialization not enabled in Console. |

### Dead Letter Queue (DLQ) Handling

When the consumer fails to process a record from `wiki.recentchange.proto`, the message is **not lost** — it is forwarded to the `wiki.recentchange.dlq` topic with full error context for debugging and replay.

**Flow:**

```
wiki.recentchange.proto
    ↓
RedpandaBatchConsumer (batch @KafkaListener)
    ↓
StatsService.recordForActiveUsers(event)
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
| **Application-level** | Exception in `StatsService` (business logic failure) | `RedpandaBatchConsumer.processRecord()` catches and calls `DlqPublisher.send()` | Per-record within a batch — other records in the same batch still process normally |
| **Framework-level** | Deserialization failure or unhandled exception before batch processing | `KafkaErrorHandlerConfig` → Spring's `DefaultErrorHandler` routes to `DlqPublisher` | Entire record rejected before reaching business logic |

**What gets written to DLQ:**

| Component | Content |
|-----------|---------|
| **Key** | Original record key (Wikipedia username) |
| **Value** | Original protobuf bytes (re-serialized via `ProtoWikiEventMapper` if already deserialized) |
| **Header `dlq.error.message`** | Exception message (e.g., `"Cassandra write timeout"`) |
| **Header `dlq.error.class`** | Fully-qualified exception class (e.g., `com.datastax.oss.driver.api.core.AllNodesFailedException`) |
| **Header `dlq.source.topic`** | Source topic name (`wiki.recentchange.proto`) |
| **Header `dlq.source.partition`** | Partition number where the record originated |
| **Header `dlq.source.offset`** | Offset of the failed record in the source partition |

**DLQ producer configuration (`DlqKafkaConfig`):**

| Setting | Value | Reason |
|---------|-------|--------|
| Value serializer | `ByteArraySerializer` | Preserves original bytes exactly as received — no re-encoding |
| Acks | `all` | DLQ messages must not be lost; wait for full broker acknowledgment |
| Key serializer | `StringSerializer` | Retains original Kafka key for correlation |

**Batch acknowledgment behavior:**

The consumer uses **manual acknowledgment** (`AckMode.MANUAL`). A failed record does NOT block the batch:

1. Batch of N records arrives
2. Each record is processed concurrently via `async(Dispatchers.Default)`
3. If record X fails → caught, sent to DLQ, logged as WARN
4. All other records in the batch continue processing
5. After all coroutines complete → `ack.acknowledge()` commits the entire batch offset

This means: **a single poison message does not stall the consumer group**.

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
| Apache Cassandra | 5.0 | User accounts, stats snapshots, revoked tokens |
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
# Allowed values: redis | in-memory
APP_SESSION_BACKEND=redis
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

### Option A — Local IDEs + Docker Infra (Recommended for Development)

Perfect for debugging with IDE breakpoints and local hot-reload.

**1) Start infrastructure:**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

# Start with local Cassandra (default, recommended)
docker compose -f docker-compose.dev.yml up -d

# OR with Redis for session backend
docker compose -f docker-compose.dev.yml --profile redis up -d
```

This starts:
- `redpanda` on `localhost:19092` (Kafka)
- `cassandra` on `localhost:19042` (local database)
- `redpanda-console` on `localhost:8080` (Kafka UI)
- `redis` optional via `--profile redis`

> **Note:** Dev mode uses local Cassandra. For Astra (cloud), use Option B instead.

**2) Run consumer from IDE (or Gradle):**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

APP_JWT_ISSUER=wikistream-local \
APP_JWT_SECRET=local-jwt-secret-at-least-32-characters-long \
APP_JWT_ACCESS_TOKEN_TTL_SECONDS=3600 \
APP_AUTH_ENABLED=true \
APP_SESSION_BACKEND=in-memory \
SERVER_PORT=7001 \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=19042 \
CASSANDRA_KEYSPACE_NAME=wikistream \
CASSANDRA_LOCAL_DATACENTER=datacenter1 \
./gradlew :cmd:consumer:bootRun
```

**3) Run producer (separate terminal):**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 ./gradlew :cmd:producer:bootRun
```

**4) Health check:**

```bash
curl http://localhost:7001/v1/status
```

**5) Stop everything:**

```bash
# Easiest: use the helper script
bash scripts/stop-local-apps.sh

# Then stop Docker infrastructure
docker compose -f docker-compose.dev.yml down
```

Or manually:

```bash
# Option A: Kill both apps by gradle process pattern
pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun'
pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun'

# Option B: Kill both apps by listening port
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true  # Consumer on 7001

# Then stop Docker infrastructure
docker compose -f docker-compose.dev.yml down
```

**Or simply:** Press `Ctrl+C` in each terminal where the apps are running.

---

### Option B — Full Docker Stack (Production-like)

All services containerized — Redpanda, Redis, Consumer, Producer, and Astra.

**1) Prepare Astra credentials (first time only):**

```bash
cp config/auth-secrets.properties.template config/auth-secrets.properties
cp config/cassandra-secrets.properties.template config/cassandra-astra-secrets.properties
```

Edit `config/cassandra-astra-secrets.properties`:

```properties
spring.profiles.active=astra
astra.db.secure-connect-bundle=./config/secure-connect-<your-db>.zip
astra.db.token=AstraCS:...your-token...
ASTRA_DB_KEYSPACE=your-keyspace-name
ASTRA_DB_LOCAL_DATACENTER=your-datacenter
```

Place your `.zip` bundle in `config/`.

**2) Create schema in Astra (one-time):**

In Astra Web UI → **Data Explorer** → run DDL from `cmd/consumer/src/main/resources/db/cassandra/schema.cql`  
(skip `CREATE KEYSPACE` and `USE` — Astra manages those).

**3) Start full stack:**

```bash
cd /Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin

# Default: Redis for sessions, Astra for database, consumer on 7001
docker compose up --build -d

# OR with in-memory sessions instead of Redis
APP_SESSION_BACKEND=in-memory docker compose up --build -d

# OR change consumer port
CONSUMER_PORT=8000 docker compose up --build -d
```

**4) Verify:**

```bash
docker compose ps

# Health check
curl http://localhost:7001/v1/status

# Kafka topics
docker exec wikistream-redpanda rpk topic list

# Kafka UI
open http://localhost:8080
```

**5) Astra warm-up (if DB hibernating):**

```bash
bash scripts/astra-warmup-check.sh
```

**6) Stop:**

```bash
docker compose down
```

---

## Configuration Reference

### Supported Environments

| Variable | Default | Where | Notes |
|----------|---------|-------|-------|
| `APP_AUTH_ENABLED` | `true` | Both | Enable `/v1/auth/*` endpoints |
| `APP_SESSION_BACKEND` | `redis` (full stack), `in-memory` (dev) | Both | Session storage: `redis` or `in-memory` |
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
docker exec wikistream-redpanda rpk topic create wiki.recentchange.proto --partitions 3 --replicas 1 || true
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
| `GET` | `/v1/stats` | ✅ Bearer | Returns authenticated user's `StatsSnapshot` |

`StatsSnapshot` fields:

| Field | Type | Description |
|-------|------|-------------|
| `totalMessages` | `Long` | Messages received while logged in |
| `distinctUsers` | `Int` | Distinct Wikipedia usernames seen |
| `botCount` | `Long` | Bot-authored edits |
| `nonBotCount` | `Long` | Human-authored edits |
| `countByServerUrl` | `Map<String,Int>` | Edits per Wikipedia server |

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
| `spring.kafka.consumer.max-poll-records` | `50` | — | Batch size |
| `spring.cassandra.contact-points` | `localhost` | `CASSANDRA_CONTACT_POINTS` | Local: `127.0.0.1` or `cassandra` (Docker) |
| `spring.cassandra.port` | `9042` | `CASSANDRA_PORT` | Docker maps container 9042 → host 19042 |
| `spring.cassandra.keyspace-name` | `wikistream` | `CASSANDRA_KEYSPACE_NAME` | Keyspace name |
| `spring.cassandra.local-datacenter` | `datacenter1` | `CASSANDRA_LOCAL_DATACENTER` | Required for driver |
| `spring.data.redis.host` | `localhost` | `SPRING_DATA_REDIS_HOST` | Redis host (set to `redis` in Docker) |
| `spring.data.redis.port` | `6379` | `SPRING_DATA_REDIS_PORT` | Redis port |
| `server.port` | `7000` | `SERVER_PORT` | HTTP server port |
| `app.session.backend` | `redis` | `APP_SESSION_BACKEND` | Session storage: `redis` or `in-memory` |
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

### Unit tests (no Docker required)

```bash
./gradlew :lib:core:test
./gradlew :cmd:producer:test
./gradlew :cmd:consumer:test
```

Or all at once via the root aggregator:

```bash
./gradlew ciTest
```

### Integration tests (Docker required)

Integration tests spin up real Cassandra and Redis containers via Testcontainers — no `docker compose up` needed beforehand.

```bash
./gradlew :cmd:consumer:integrationTest
```

Or via root:

```bash
./gradlew integrationTest
```

### Coverage

| Suite | Tests | Scope |
|-------|-------|-------|
| `lib:core:test` | 6 | TopicsTest (raw, proto, dlq), ProtoWikiEventMapper serialization/deserialization |
| `cmd:producer:test` | 12 | Parser, publisher, SSE client, configuration, ingestion runner |
| `cmd:consumer:test` | 45 | Batch consumer (proto topic), services, security, auth, error handling |
| `cmd:consumer:integrationTest` | 28 | Cassandra repos, Redis sessions, AuthService (with real containers) |
| **Total** | **91** | Comprehensive coverage: protobuf serialization, Redpanda topics, DLQ, JWT auth, session management |

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


