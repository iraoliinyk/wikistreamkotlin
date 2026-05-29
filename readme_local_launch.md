# Local Launch Guide

This guide explains how to run and debug the project locally.

> **The consumer requires Cassandra, Redis (or in-memory mode), and Kafka to be running before startup.**
> If any of these is missing the app will hang at startup while the driver retries the connection.
> The quickest way to satisfy all dependencies is to start Docker services first (option A below).

## What this setup expects

From current project config:

- Kafka/Redpanda broker reachable via `spring.kafka.bootstrap-servers` (default: `localhost:19092`)
- Consumer requires Cassandra (default: `localhost:9042`, keyspace `wikistream`)
- Redis is optional if `APP_SESSION_BACKEND=in-memory`
- Consumer app default port in config is `7000`; examples below override it to `7001` for local runs
- Consumer requires JWT env vars:
  - `APP_JWT_ISSUER`
  - `APP_JWT_SECRET`
  - `APP_JWT_ACCESS_TOKEN_TTL_SECONDS`

## Kafka/Redpanda port mapping

- Local broker installed on host (for example via Homebrew Kafka): `localhost:9092`
- Docker Redpanda external listener: `localhost:19092`
- Docker-internal listener (container-to-container): `redpanda:9092`

Use `SPRING_KAFKA_BOOTSTRAP_SERVERS` to select the right endpoint for your current setup.

## 1) Start infrastructure

### Option A — Docker (recommended)

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
docker compose up -d
```

This starts: **Cassandra** (host port `19042`), **Redpanda** (host port `19092`), and runs one-shot init containers:
- `cassandra-init` — applies schema automatically from `cmd/consumer/src/main/resources/db/cassandra/schema.cql`
- `redpanda-init` — creates both Kafka topics

It does **not** start Redis by default.

If you want Docker Redis too, run:

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
docker compose --profile redis up -d
```

Wait ~60 seconds for Cassandra health check to pass. Check status:

```bash
docker compose ps -a
```

Both init containers should show `Exited (0)`.

> **Note:** Cassandra is mapped to `localhost:19042` (not 9042) to avoid conflict with any local Homebrew Cassandra installation.

Then use these values in the run commands:
- `SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092`
- `CASSANDRA_CONTACT_POINTS=127.0.0.1`
- `CASSANDRA_PORT=19042`
- Redis:
  - not needed when `APP_SESSION_BACKEND=in-memory`
  - if needed, start it with `docker compose --profile redis up -d` and use `SPRING_DATA_REDIS_HOST=localhost`, `SPRING_DATA_REDIS_PORT=6379`

### Option B — Services installed locally on host

- Kafka on `localhost:9092` (Homebrew: `brew services start kafka`)
- Cassandra on `localhost:9042` (Homebrew: `brew services start cassandra`)
- Redis on `localhost:6379` (Homebrew: `brew services start redis`) — or skip with `APP_SESSION_BACKEND=in-memory`

Then use `SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092` in the run commands.

## 2) Create Kafka topics (one-time)

### Local broker on host (port `9092`)

```bash
kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic wiki.recentchange.raw --partitions 3 --replication-factor 1
kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic wiki.recentchange.dlq --partitions 1 --replication-factor 1
```

### Docker Redpanda from host (port `19092`)

```bash
kafka-topics --bootstrap-server localhost:19092 --create --if-not-exists --topic wiki.recentchange.raw --partitions 3 --replication-factor 1
kafka-topics --bootstrap-server localhost:19092 --create --if-not-exists --topic wiki.recentchange.dlq --partitions 1 --replication-factor 1
```

> Topics are also created automatically by the `redpanda-init` service in `docker-compose.yml`.

## 3) Initialize Cassandra schema (one-time for local Cassandra)

> **Skip this step if using Docker** — `cassandra-init` runs the schema automatically.

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
# Local Homebrew Cassandra (port 9042):
cqlsh 127.0.0.1 9042 -f cmd/consumer/src/main/resources/db/cassandra/schema.cql
```

## 4) Run consumer locally

### Terminal

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

> Use `CASSANDRA_PORT=9042` if you're connecting to a local Homebrew Cassandra instead of Docker.

### IntelliJ Run/Debug configuration

- Main class: `com.redspace.wikistreamkotlin.consumer.ConsumerApplicationKt`
- Working directory: project root (`/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin`)
- Environment variables: same as terminal command above — including `SERVER_PORT=7001` and `CASSANDRA_PORT=19042`

## 5) Run producer locally

### Terminal

```bash
cd "/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin"
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :cmd:producer:bootRun
```

### IntelliJ Run/Debug configuration

- Main class: `com.redspace.wikistreamkotlin.producer.ProducerApplicationKt`
- Working directory: project root (`/Users/ioliinyk/Desktop/kotlinProjects/wikistreamkotlin`)
- Environment variables:
  - `SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092` (or `localhost:9092` when using a local broker)

## 6) Verify quickly

```bash
curl http://localhost:7001/v1/status
```

Expected response:

```json
{"status":"ok"}
```

## Troubleshooting

- **App hangs at ~92% / startup never completes**
  - Cassandra or Redis is not reachable. The DataStax driver retries indefinitely by default.
  - Fix: start Docker services (`docker compose up -d`) or install services locally, then restart the app.
  - Kill the hanging process: `kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null`

- `Could not resolve placeholder 'APP_JWT_ISSUER'`
  - Add all three `APP_JWT_*` vars to the consumer config.

- `POST /v1/auth/register` returns 404
  - Check `APP_AUTH_ENABLED` value.
  - `APP_AUTH_ENABLED=false` disables auth endpoints (`/v1/auth/register`, `/v1/auth/login`, `/v1/auth/logout`) by design.
  - Set `APP_AUTH_ENABLED=true` to use register/login flows.

- `GET /v1/stats` returns 401
  - `/v1/stats` is protected when `APP_AUTH_ENABLED=true`.
  - Login first, then call stats with `Authorization: Bearer <accessToken>`.
  - If token came from an older app run (different JWT secret/issuer), login again to refresh token.

- `GET /v1/stats` returns only zero values
  - This is expected until events are consumed while your user session is active.
  - Ensure producer is running (`./gradlew :cmd:producer:bootRun`).
  - Ensure only one consumer instance is running when `APP_SESSION_BACKEND=in-memory`.
  - Re-login after consumer restart (in-memory sessions are cleared on restart).
  - Verify broker traffic:

```bash
docker exec wikistream-redpanda rpk group describe wiki-consumer
docker exec wikistream-redpanda rpk topic describe-storage wiki.recentchange.raw
```

- Kafka connection refused
  - Check broker is up and port matches `SPRING_KAFKA_BOOTSTRAP_SERVERS`.
  - Docker Redpanda: `localhost:19092` | Local Homebrew Kafka: `localhost:9092`

- Cassandra keyspace/table errors
  - With Docker: `docker compose ps -a` — check `cassandra-init` exited with code `0`. If it failed, re-run: `docker compose run --rm cassandra-init`
  - With local Cassandra: re-run the `cqlsh` schema command and verify keyspace `wikistream` exists.
  - Use `CASSANDRA_CONTACT_POINTS=127.0.0.1` (not `localhost`) to avoid IPv6 resolution issues.
  - **Docker Cassandra port is `19042`** (not `9042`) — set `CASSANDRA_PORT=19042` in your run command.

- `Port 7000 was already in use`
  - Either kill the process on 7000, or use `SERVER_PORT=7001` as shown in the commands above.
  - Find and stop it:

```bash
lsof -nP -iTCP:7000 -sTCP:LISTEN
kill <PID>
```

- `Port 7001 was already in use` (common with `SERVER_PORT=7001`)
  - Quick fix (copy/paste):

```bash
kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null || true
```

  - Inspect first (optional):

```bash
lsof -nP -iTCP:7001 -sTCP:LISTEN
kill <PID>
```

- **Why `SERVER_PORT=7001` and not `7000` or `700`?**
  - `7000` is the app default — a previous run may still hold it.
  - `700` is a privileged port (< 1024) — requires root on macOS/Linux, will fail without `sudo`.
  - `7001` is free, unprivileged, and easy to remember.

- Redis startup errors in local mode
  - Set `APP_SESSION_BACKEND=in-memory` to bypass Redis entirely.

## Optional: use secrets files instead of env vars

You can keep JWT values in the secrets file already supported by consumer config:

- `config/auth-secrets.properties` — JWT issuer, secret, TTL (local dev)

**Astra (cloud Cassandra) only:**

- `config/cassandra-astra-secrets.properties` — sets `spring.profiles.active=astra` + Astra credentials
  - This file activates the `astra` Spring profile and configures the DataStax secure connect bundle.
  - **Do NOT create this file for local Docker dev** — it will override local Cassandra settings and make the consumer try to connect to Astra cloud.
  - For local Docker dev use the env vars (`CASSANDRA_PORT=19042`, etc.) shown in section 4.

If you use these files, keep working directory as project root so `spring.config.import=optional:file:./config/...` resolves correctly.
