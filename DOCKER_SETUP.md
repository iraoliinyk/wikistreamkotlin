# Docker Configuration Guide

This document explains the two Docker Compose configurations in this project.

## Files Overview

| File | Purpose | Use Case | Apps |
|------|---------|----------|------|
| `docker-compose.dev.yml` | Development: infra only | Running apps locally from IDE with debugger | Redpanda, Cassandra/Astra, Redis (optional), Redpanda Console |
| `docker-compose.yml` | Production: full stack | Running everything containerized | Redpanda, Redis, Consumer, Producer, Redpanda Console, **Astra** |
| `Dockerfile.consumer` | Consumer image | Used by both compose files | — |
| `Dockerfile.producer` | Producer image | Used by both compose files | — |

---

## Development Setup (`docker-compose.dev.yml`)

### When to use
- Debug and breakpoint in IDE
- Hot reload code changes  
- Test locally with minimal dependencies

### What starts (default: local Cassandra)
- **Redpanda** (Kafka broker) on `localhost:19092`
- **Cassandra** 5.0 on `localhost:19042` (starts by default)
- **Redis** (optional via `--profile redis`)
- **Redpanda Console** (Kafka UI) on `localhost:8080`

### Database Mode

**Local Cassandra (default - recommended)**
```bash
docker compose -f docker-compose.dev.yml up -d
```
This starts the full local stack including Cassandra. Run apps locally from IDE pointing to `localhost:19042`:
```bash
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=19042 \
./gradlew :cmd:consumer:bootRun
```

**For Astra (cloud Cassandra)**

Astra is a fully-managed cloud Cassandra service. To use it instead of local Cassandra:

**Step 1: Prepare credentials (one-time)**
```bash
cp config/cassandra-astra-secrets.properties.template config/cassandra-astra-secrets.properties
# Edit with your Astra DB ID, region, and token
```

**Step 2: Start infrastructure (same command as local Cassandra)**
```bash
docker compose -f docker-compose.dev.yml up -d
```
The Cassandra container will start, but your app will ignore it and connect to cloud instead.

**Step 3: Run consumer locally - it auto-activates Astra profile**
```bash
./gradlew :cmd:consumer:bootRun
```
How it works: `config/cassandra-astra-secrets.properties` contains `spring.profiles.active=astra` (line 1), which automatically:
- Ignores local Cassandra settings
- Activates the `astra` Spring profile
- Uses cloud Cassandra credentials from that file
- Works whether local Cassandra container is running or not

**Why keep both?** The `docker-compose.dev.yml` is agnostic to your database choice. The decision is made purely through:
- `config/cassandra-astra-secrets.properties` existence and content (triggers Astra profile)
- App environment (local Cassandra env vars = local mode, Astra secrets file = cloud mode)

### Session Backend Options

**Option 1: In-memory sessions (simpler, default for dev)**
```bash
# Run consumer with APP_SESSION_BACKEND=in-memory
docker compose -f docker-compose.dev.yml up -d
```

**Option 2: Redis sessions**
```bash
docker compose -f docker-compose.dev.yml --profile redis up -d
# Run consumer with APP_SESSION_BACKEND=redis
```

### Running Apps Locally

Once infrastructure is up, start apps from IDE or Gradle:

```bash
# Terminal 1: Consumer
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=19042 \
APP_JWT_ISSUER=dev \
APP_JWT_SECRET=dev-secret-at-least-32-characters-long \
APP_JWT_ACCESS_TOKEN_TTL_SECONDS=3600 \
./gradlew :cmd:consumer:bootRun

# Terminal 2: Producer
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :cmd:producer:bootRun
```

**OR from IDE:**
- Right-click `cmd/consumer/src/main/kotlin/ConsumerApplicationKt`
- Select "Run" with environment variables set
- For debugging, select "Debug" instead

---

## Full Stack Setup (`docker-compose.yml`)

### When to use
- Simulate production deployment
- Demo or testing without code changes
- Cloud Cassandra (Astra) integration
- Don't need IDE debugging

### What starts
- **Redpanda** (Kafka broker) on `localhost:19092`
- **Redis** (session storage) on `localhost:6379`
- **Consumer App** on `localhost:7001` (configurable via `CONSUMER_PORT`)
- **Producer App** (no HTTP port, publishes to Kafka)
- **Redpanda Console** (Kafka UI) on `localhost:8080`
- **Astra** (cloud Cassandra) — requires credentials

### Setup Steps

**Step 1: Prepare credentials (first time only)**
```bash
cp config/auth-secrets.properties.template config/auth-secrets.properties
cp config/cassandra-secrets.properties.template config/cassandra-astra-secrets.properties
```

**Step 2: Edit `config/cassandra-astra-secrets.properties`**
```properties
spring.profiles.active=astra
astra.db.secure-connect-bundle=./config/secure-connect-<your-db>.zip
astra.db.token=AstraCS:...your-token...
ASTRA_DB_KEYSPACE=your-keyspace
ASTRA_DB_LOCAL_DATACENTER=your-datacenter
```

**Step 3: Create schema in Astra (one-time)**
- Go to Astra Web UI → Data Explorer
- Run DDL from `cmd/consumer/src/main/resources/db/cassandra/schema.cql`
- Skip `CREATE KEYSPACE` and `USE` lines

**Step 4: Start stack**
```bash
docker compose up --build -d
```

**Step 5: Verify**
```bash
curl http://localhost:7001/v1/status
```

### Configuration Options

| Variable | Default | Example |
|----------|---------|---------|
| `APP_AUTH_ENABLED` | `true` | `false` to disable auth |
| `APP_SESSION_BACKEND` | `redis` | `in-memory` for in-process sessions |
| `CONSUMER_PORT` | `7001` | `8000` to use port 8000 |

### Examples

**With in-memory sessions:**
```bash
APP_SESSION_BACKEND=in-memory docker compose up --build -d
```

**Different consumer port:**
```bash
CONSUMER_PORT=8000 docker compose up --build -d
curl http://localhost:8000/v1/status
```

**Auth disabled (no login required):**
```bash
APP_AUTH_ENABLED=false docker compose up --build -d
curl http://localhost:7001/v1/stats  # No Bearer token needed
```

---

## Quick Commands

### Development Mode

```bash
# Start infra
docker compose -f docker-compose.dev.yml up -d

# With Redis
docker compose -f docker-compose.dev.yml --profile redis up -d

# Stop
docker compose -f docker-compose.dev.yml down

# Logs
docker compose -f docker-compose.dev.yml logs -f redpanda cassandra
```

### Full Stack Mode

```bash
# Start everything
docker compose up --build -d

# Stop everything
docker compose down

# Rebuild images
docker compose up --build -d

# View logs
docker compose logs -f consumer producer

# Check running services
docker compose ps
```

### Kafka UI

Both setups include **Redpanda Console** on `http://localhost:8080`:
- View topics, partitions, consumer groups
- Inspect message content
- Monitor producer/consumer lag

### Troubleshooting

**Port conflicts:**
```bash
# Find process on port
lsof -nP -iTCP:19092 -sTCP:LISTEN

# Stop compose and retry
docker compose down
docker compose up -d
```

**Check container logs:**
```bash
docker compose logs redpanda
docker compose logs consumer
```

**Reset everything:**
```bash
docker compose down --remove-orphans -v  # Also removes volumes
docker compose up --build -d
```

---

## Database Notes

### Local Cassandra (dev-only)
- Schema auto-initialized by `cassandra-init` service
- Data persists in volumes during development

### Astra (full stack)
- Requires `config/cassandra-astra-secrets.properties`
- Schema must be manually created in Astra UI
- More expensive than local Cassandra, but scalable for production

### Switching Between Cassandra and Astra

**Dev mode: Use local Cassandra**
```bash
docker compose -f docker-compose.dev.yml up -d
# Run apps locally pointing to localhost:19042
CASSANDRA_CONTACT_POINTS=127.0.0.1 CASSANDRA_PORT=19042 ./gradlew :cmd:consumer:bootRun
```

**Dev mode: Switch to Astra (same infra, different app config)**
```bash
# Ensure config/cassandra-astra-secrets.properties exists with your Astra credentials
docker compose -f docker-compose.dev.yml up -d  # Same command; local Cassandra still starts
# App auto-activates astra profile (from secrets file); connects to cloud, ignores local Cassandra
./gradlew :cmd:consumer:bootRun
```

**Why does local Cassandra start even when using Astra?**
- The compose file is unaware of which database your app will use
- Docker Compose profiles would add complexity (see header of docker-compose.dev.yml for technical details)
- Local Cassandra container uses minimal resources when unused
- This design keeps dev and prod infra setup identical and composable

**Full stack: Always uses Astra**
```bash
docker compose up -d
# Consumer and producer run in containers, both use Astra
```

---

## Image Version Pinning

All external dependencies are pinned to specific versions for reproducibility and stability:

| Service | Version | Notes |
|---------|---------|-------|
| Redpanda Broker | v24.3.14 | Stable, well-tested |
| Redpanda Console | v0.24.0 | UI for topic monitoring; pinned to prevent breaking changes |
| Cassandra | 5.0 | Major version pinned for compatibility |
| Redis | 7-alpine | Major version pinned; lightweight Alpine image |

**Why version pinning?**
- **Reproducibility:** Same setup across developers, CI/CD, and production
- **Stability:** No unexpected breaking changes from automatic updates
- **Debugging:** Can pinpoint issues to specific versions
- **Security:** Can review release notes before upgrading

**Note:** Local app images (`wikistream-consumer`, `wikistream-producer`) use `latest` because they're built from your current codebase.

---

## File Removed

The following files were consolidated and removed:
- `docker-compose.astra.yml` → integrated into main `docker-compose.yml`
- `Dockerfile` → unused; only `Dockerfile.consumer` and `Dockerfile.producer` needed

