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
- [Run Locally (Host Apps + Docker Infra)](#run-locally-host-apps--docker-infra)
- [Running with Docker Compose](#running-with-docker-compose)
- [Running with DataStax Astra (Cloud Cassandra)](#running-with-datastax-astra-cloud-cassandra)
- [API Reference](#api-reference)
- [Configuration Reference](#configuration-reference)
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
│   │           ├── RedpandaPublisher.kt        # KafkaTemplate → wiki.recentchange.raw
│   │           └── config/
│   │               ├── ProducerKafkaConfig.kt  # ProducerFactory + KafkaTemplate beans
│   │               ├── WebClientConfig.kt
│   │               └── WikiStreamProperties.kt
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
│                   └── AppErrorLogger.kt
│
└── lib/
    └── core/                      # Shared contracts — no Spring Boot dependency
        └── src/main/kotlin/
            └── core/
                ├── Topics.kt                  # Topic name constants
                ├── EventEnvelope.kt           # Generic schema-versioned wrapper
                ├── domain/
                │   ├── WikiEvent.kt
                │   └── WikiEventMeta.kt
                └── exception/
                    └── AppError.kt            # Sealed error hierarchy
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
| Component | Role |
|-----------|------|
| Redpanda (Kafka-compatible) | Message broker — decouples producer from consumer |
| Topic `wiki.recentchange.raw` | Raw `WikiEvent` JSON (3 partitions) |
| Topic `wiki.recentchange.dlq` | Dead-letter queue for unprocessable records (1 partition) |

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

## Run Locally (Host Apps + Docker Infra)

This mode runs infrastructure with Docker and starts `consumer` / `producer` directly from Gradle.

### 1) Start infrastructure

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
docker compose up -d
```

This starts:
- `redpanda` on `localhost:19092`
- `cassandra` on `localhost:19042`
- init jobs: `redpanda-init` (topics), `cassandra-init` (schema)

Redis is profile-based and does **not** start by default:

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
docker compose --profile redis up -d
```

Verify:

```bash
docker compose ps -a
```

### 2) Run consumer locally

Use `APP_AUTH_ENABLED=true` if you need `/v1/auth/register` and `/v1/auth/login`.

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
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

Health check:

```bash
curl http://localhost:7001/v1/status
```

### 3) Run producer locally

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :cmd:producer:bootRun
```

### 4) Stop both local apps

If both apps were started via Gradle (`:cmd:consumer:bootRun` and `:cmd:producer:bootRun`), stop them with:

```bash
pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun' 2>/dev/null || true
pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun' 2>/dev/null || true
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true
```

### 5) Most common local issues

- `bootRun` stuck at `92% EXECUTING` is usually a running app waiting for requests (normal for long-running Spring tasks).
- `Port 7001 was already in use`:

```bash
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true
```

- `POST /v1/auth/register` returns `404`: set `APP_AUTH_ENABLED=true`.

- `GET /v1/stats` returns `401`: login first and send `Authorization: Bearer <accessToken>`.

- If `GET /v1/stats` still returns `401` with a token, refresh token via login (old token may be signed with a different JWT secret/issuer from a previous run).

- `GET /v1/stats` returns zeros only: producer is not feeding events yet, or multiple consumer instances are running with `APP_SESSION_BACKEND=in-memory` (session state is per-instance).

- Inspect running Gradle tasks and their listening ports (macOS/zsh):

```bash
(printf "%-8s | %-45s | %s\n" "PID" "TASK" "PORTS"; printf "%-8s-+-%-45s-+-%s\n" "--------" "---------------------------------------------" "-----"; ps -ax -o pid=,command= | grep 'org.gradle.appname=gradlew' | grep -v grep | while read -r pid cmd; do task=$(printf "%s" "$cmd" | sed -E 's#^.*gradle-wrapper\.jar[[:space:]]+##'); ports=$(pgrep -P "$pid" java | while read -r jpid; do lsof -nP -a -p "$jpid" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR>1{split($9,a,":"); print a[length(a)]}'; done | sort -u | paste -sd, -); [ -z "$ports" ] && ports="-"; printf "%-8s | %-45s | %s\n" "$pid" "$task" "$ports"; done)
```

- `POST /v1/auth/register` returns `500` with `unexpected_app_error`: fixed in current code; if seen, restart with latest build.

For the full local walkthrough, see `readme_local_launch.md`.

---

## Running with Docker Compose

`docker-compose.yml` is profile-based:
- default (`docker compose up -d`): infra only (`redpanda`, `cassandra`, init jobs)
- `redis` profile: optional Redis
- `app` profile: `consumer` + `producer` containers

```
┌──────────────────────────────────────────────────────────┐
│ docker compose up -d                                    │
│ redpanda-init  -> wiki.recentchange.raw / .dlq topics   │
│ cassandra-init -> keyspace/tables from schema.cql       │
└──────────────────────────────────────────────────────────┘
```

### Start

```bash
docker compose up -d
```

With Redis too:

```bash
docker compose --profile redis up -d
```

Start app containers too:

```bash
docker compose --profile app up -d
```

### Verify

```bash
docker compose ps -a
```

### Redpanda (from host)

The broker is reachable at `localhost:19092` for local tooling (e.g. `rpk`, Kafka UI):

```bash
docker exec wikistream-redpanda rpk topic list
```

### Inspect Cassandra

```bash
docker exec -it wikistream-cassandra cqlsh
```

```cql
USE wikistream;
SELECT email, active FROM user_accounts LIMIT 20;
SELECT id, total_messages, distinct_users FROM stats_snapshots LIMIT 20;
SELECT jti, email FROM revoked_tokens LIMIT 20;
```

### Stop

```bash
docker compose down
```

---

## Running with DataStax Astra (Cloud Cassandra)

`docker-compose.astra.yml` replaces local Cassandra with DataStax Astra. Redpanda and Redis still run locally.

### Step 1 — Prepare credentials

```bash
cp config/cassandra-secrets.properties.template config/cassandra-secrets.properties
cp config/auth-secrets.properties.template      config/auth-secrets.properties
```

Edit `config/cassandra-secrets.properties`:

```properties
spring.profiles.active=astra
astra.db.secure-connect-bundle=./config/secure-connect-<your-db>.zip
astra.db.token=AstraCS:...your-token...
ASTRA_DB_KEYSPACE=your-keyspace-name
ASTRA_DB_LOCAL_DATACENTER=your-datacenter
```

Place the Astra Secure Connect Bundle (`.zip`) in the `config/` directory.

### Step 2 — Create tables in Astra

In the Astra web UI → **Data Explorer** → run the DDL from `src/main/resources/db/cassandra/schema.cql`  
(skip the `CREATE KEYSPACE` and `USE` lines — Astra manages keyspace creation).

### Step 3 — Start

```bash
docker compose -f docker-compose.astra.yml \
  --profile "$(grep '^APP_SESSION_BACKEND=' .env | cut -d= -f2)" \
  up --build
```

### Step 4 — Astra warm-up (if DB is hibernating)

```bash
bash scripts/astra-warmup-check.sh
```

### Step 5 — Verify

```bash
curl http://localhost:7000/v1/status
```

### Stop

```bash
docker compose -f docker-compose.astra.yml down
```

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

## Configuration Reference

### Producer — `cmd/producer/src/main/resources/application.properties`

| Property | Default | Env override |
|----------|---------|--------------|
| `spring.kafka.bootstrap-servers` | `localhost:19092` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `wiki.stream.url` | Wikimedia SSE URL | — |
| `wiki.stream.user-agent` | `wikistream-producer/local` | — |

### Consumer — `cmd/consumer/src/main/resources/application.properties`

| Property | Default | Env override |
|----------|---------|--------------|
| `spring.kafka.bootstrap-servers` | `localhost:19092` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `spring.kafka.consumer.group-id` | `wiki-consumer` | — |
| `spring.kafka.consumer.max-poll-records` | `50` | — |
| `spring.cassandra.contact-points` | `localhost` | `CASSANDRA_CONTACT_POINTS` |
| `spring.cassandra.port` | `9042` | `CASSANDRA_PORT` |
| `spring.cassandra.keyspace-name` | `wikistream` | `CASSANDRA_KEYSPACE_NAME` |
| `spring.data.redis.host` | `localhost` | `SPRING_DATA_REDIS_HOST` |
| `spring.data.redis.port` | `6379` | `SPRING_DATA_REDIS_PORT` |
| `app.session.backend` | `redis` | `APP_SESSION_BACKEND` |
| `app.auth.enabled` | `true` | `APP_AUTH_ENABLED` |
| `app.security.jwt.secret` | _(required)_ | via `config/auth-secrets.properties` |

### Secrets files (git-ignored)

| File | Template | Purpose |
|------|----------|---------|
| `config/auth-secrets.properties` | `*.template` | JWT issuer, secret, TTL |
| `config/cassandra-secrets.properties` | `*.template` | Astra bundle path + token |

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
| `lib:core:test` | 1 | Domain model |
| `cmd:producer:test` | 2 | Parser, publisher |
| `cmd:consumer:test` | 20 | Services, security, error handling |
| `cmd:consumer:integrationTest` | 21 | Cassandra repos, Redis sessions, AuthService |
| **Total** | **44** | |

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

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:7000` | App base URL |
| `TEST_EMAIL` | `local-test-@example.com` | Auto-replaced with timestamped email on first run |
| `TEST_PASSWORD` | `StrongPass#123` | Used for register and login |
| `ACCESS_TOKEN` | _(set by Login request)_ | Bearer token, extracted automatically |

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
| [`REDPANDA_IMPLEMENTATION_GUIDE.md`](REDPANDA_IMPLEMENTATION_GUIDE.md) | Full migration guide: producer/consumer split, Redpanda setup |
