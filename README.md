# About
Spring Boot + Kotlin app that consumes the [wiki-recentchange stream](https://stream.wikimedia.org/v2/stream/recentchange).

Events are aggregated per authenticated user into a `StatsSnapshot` and stored in Cassandra.
Each user sees only their own stats while logged in; stats stop updating after logout.

# Read Me First
This project requires Java 24, Kotlin and IntelliJ IDEA to run locally.

Two Docker Compose files are provided:

| File | Purpose |
|---|---|
| `docker-compose.yml` | Local Redis + local Cassandra + app |
| `docker-compose.astra.yml` | Local Redis + app connected to DataStax Astra Cassandra |

Sensitive Cassandra or Astra credentials must not be committed.
Copy `config/cassandra-secrets.properties.template` to `config/cassandra-secrets.properties` and fill in real values.

JWT auth secrets must not be committed.
Copy `config/auth-secrets.properties.template` to `config/auth-secrets.properties` and set a strong signing secret.

Session backend selection is centralized in the root `.env` file.
Both `docker-compose.yml` and `docker-compose.astra.yml` read `APP_SESSION_BACKEND` from this file.

---

## Documentation

### Authentication & Authorization

- **[JWT & Bearer Token Scheme](JWT_BEARER_SCHEME.md)** — Token structure, signing, validation, revocation mechanisms, and detailed request lifecycle

### Session Management

- **[Active User Sessions](ACTIVE_USER_SESSIONS.md)** — User login state tracking via Redis/in-memory, background stats recording, metadata schema, configuration, and error handling

---

## Tech Stack

### Language & Runtime
| Component | Version |
|---|---|
| Kotlin | 2.2.21 |
| Java | 24 (Eclipse Temurin) |
| Gradle | 9.x (Kotlin DSL) |

### Framework
| Component | Version | Role |
|---|---|---|
| Spring Boot | 4.0.5 | Application framework |
| Spring WebFlux | (Boot-managed) | Reactive HTTP server (Netty) |
| Spring Data Cassandra | (Boot-managed) | Cassandra ORM |
| Spring Data Redis | (Boot-managed) | Redis session storage |
| Spring Security | (Boot-managed) | Auth filter chain |
| Spring Security OAuth2 Resource Server + Jose | (Boot-managed) | JWT decode & validation |
| Kotlinx Coroutines + Reactor bridge | (Boot-managed) | Coroutine ↔ Reactor interop |

### Storage
| Store | Usage |
|---|---|
| Apache Cassandra 5.0 | User accounts, stats snapshots, revoked tokens |
| Redis 7 | Active session tracking (login/logout state) |
| DataStax Astra | Cloud-hosted Cassandra (Astra profile) |

### External Data Source
| Source | Protocol |
|---|---|
| [Wikimedia recent-change stream](https://stream.wikimedia.org/v2/stream/recentchange) | Server-Sent Events (SSE) via `WebClient` |

### Auth
| Mechanism | Details |
|---|---|
| JWT (HS256) | Signed with app secret, TTL-based expiry |
| Bearer scheme | `Authorization: Bearer <token>` on protected routes |
| Token revocation | Blocked server-side via `revoked_tokens` Cassandra table + `jti` lookup |
| Session state | Redis hash per email, TTL mirrors JWT expiry |

### Testing
| Library | Role |
|---|---|
| JUnit 5 | Test runner |
| Mockito-Kotlin 5.4.0 | Mocking |
| Spring Boot Test | Integration test support |
| WebTestClient | HTTP-layer integration tests |
| Reactor Test | Reactive stream assertions |
| Testcontainers 2.0.4 | Real Redis and Cassandra in tests |

### Infrastructure
| Component | Details |
|---|---|
| Docker | Multi-stage build (builder: JDK 24, runtime: JRE 24) |
| Docker Compose | `docker-compose.yml` (local), `docker-compose.astra.yml` (Astra) |
| Lettuce | Reactive Redis client (pooled, configurable host/port) |
| kotlin-logging-jvm 2.0.11 | Structured logging facade |

---

## API Reference

### Stats
| Method | Path | Auth required | Description |
|---|---|---|---|
| `GET` | `/v1/stats` | ✅ Bearer token | Returns the authenticated user's personal stats snapshot |
| `GET` | `/v1/status` | ❌ | Health check |

Stats include:
- number of messages consumed while the user was logged in
- number of distinct Wikipedia users seen
- number of bots and non-bots
- count by distinct server URLs

> Stats are scoped to the authenticated user. Events are only recorded for currently logged-in users. After logout, recording stops and the next login continues from the last saved snapshot.

### Auth
| Method | Path | Auth required | Description |
|---|---|---|---|
| `POST` | `/v1/auth/register` | ❌ | Register with `{ email, password }` |
| `POST` | `/v1/auth/login` | ❌ | Login and receive JWT Bearer token |
| `POST` | `/v1/auth/logout` | ✅ Bearer token | Revoke current token |

---

## Cassandra configuration

The application uses Spring Boot Cassandra properties from `src/main/resources/application.properties`.

Default local values:

- contact point: `localhost`
- port: `9042`
- keyspace: `wikistream`
- datacenter: `datacenter1`

Schema is defined in `src/main/resources/db/cassandra/schema.cql` and applied automatically by `cassandra-init` on first startup.

---

## Run with Docker Compose — Local Cassandra

`docker-compose.yml` starts local Redis and Cassandra 5.0 containers, applies the schema, then starts the app.
No external credentials required.

`APP_SESSION_BACKEND` comes from `.env` (single source of truth).
Repo default is `APP_SESSION_BACKEND=redis`.

**Step 1** — (Optional) create JWT auth secrets file:

```bash
cp config/auth-secrets.properties.template config/auth-secrets.properties
```

Edit `config/auth-secrets.properties` and set a strong JWT secret (at least 32 characters):

```properties
app.security.jwt.issuer=wikistreamkotlin
app.security.jwt.secret=your-long-random-secret-here-at-least-32-chars
app.security.jwt.access-token-ttl-seconds=3600
```

> If you skip this step, JWT values default to the env vars set in `docker-compose.yml` and the app will still start.

**Step 2** — Start all services and build image:

```bash
docker compose up --build
```

Run in detached mode:

```bash
docker compose up --build -d
```

**Step 3** — Verify the app is running:

```bash
curl http://localhost:7000/v1/status
```

Expected response: `{ "status": "ok" }`

Quick Redis session check (inside app container):

```bash
docker exec wikistreamkotlin ping -c 1 redis
```

**Step 4** — Stop and remove containers:

```bash
docker compose down
```

### Inspect local Cassandra data

Open an interactive CQL shell:

```bash
docker exec -it wikistreamkotlin-cassandra cqlsh
```

### CQL test queries

```cql
USE wikistream;

SELECT email, active FROM user_accounts LIMIT 20;
SELECT id, total_messages, distinct_users, bot_count FROM stats_snapshots LIMIT 20;
SELECT * FROM revoked_tokens LIMIT 20;
```

---

## Run with Docker Compose — DataStax Astra

`docker-compose.astra.yml` starts local Redis + app (no local Cassandra). All DB calls go to Astra cloud.
The `config/` directory is mounted read-only into the container so secrets are never baked into the image.

`APP_SESSION_BACKEND` comes from `.env` (single source of truth).
Repo default is `APP_SESSION_BACKEND=redis`.

**Step 1** — Prepare secrets files:

```bash
cp config/cassandra-secrets.properties.template config/cassandra-secrets.properties
cp config/auth-secrets.properties.template config/auth-secrets.properties
```

**Step 2** — Edit `config/cassandra-secrets.properties`:

```properties
spring.profiles.active=astra
astra.db.secure-connect-bundle=./config/secure-connect-<your-db-name>.zip
astra.db.token=AstraCS:...your-token...
ASTRA_DB_KEYSPACE=your-keyspace-name
ASTRA_DB_LOCAL_DATACENTER=your-datacenter
```

Place the Astra Secure Connect Bundle (`.zip`) in the `config/` directory.
`astra.db.secure-connect-bundle` must point to it using `./config/` as the path prefix.

**Step 3** — Edit `config/auth-secrets.properties`:

```properties
app.security.jwt.issuer=wikistreamkotlin
app.security.jwt.secret=your-long-random-secret-here-at-least-32-chars
app.security.jwt.access-token-ttl-seconds=3600
```

**Step 4** — Create keyspace tables on Astra before the first run.
Open the Astra web UI → **Data Explorer** → run the SQL from `src/main/resources/db/cassandra/schema.cql`
(skip the `CREATE KEYSPACE` and `USE` lines, which are for local Cassandra only).

**Step 5** — Start app connected to Astra:

```bash
docker compose -f docker-compose.astra.yml --profile "$(grep '^APP_SESSION_BACKEND=' .env | cut -d= -f2)" up --build
```

Detached:

```bash
docker compose -f docker-compose.astra.yml --profile "$(grep '^APP_SESSION_BACKEND=' .env | cut -d= -f2)" up --build -d
```

**Step 6** — Verify:

```bash
curl http://localhost:7000/v1/status
```

Optional Redis reachability check:

```bash
docker exec wikistreamkotlin ping -c 1 redis
```

**Step 7** — Stop:

```bash
docker compose -f docker-compose.astra.yml down
```

---

## Login and Stats Flow (Redis-backed sessions)

Use this quick flow after either compose setup to verify auth + session + stats:

```bash
curl -X POST http://localhost:7000/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"local-flow@example.com","password":"StrongPass#123"}'

curl -X POST http://localhost:7000/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"local-flow@example.com","password":"StrongPass#123"}'
```

Copy the returned access token into `ACCESS_TOKEN`, then request stats:

```bash
ACCESS_TOKEN="<paste-token-here>"
curl http://localhost:7000/v1/stats -H "Authorization: Bearer $ACCESS_TOKEN"
```

---
## Postman Collection

A Postman collection is provided at `postman_collection.json`.

It covers all API calls in the correct order with automatic token extraction.

### Import

1. Open Postman
2. Click **Import** (top-left)
3. Select `postman_collection.json` from the project root

### Variables

| Variable | Default | Description |
|---|---|---|
| `BASE_URL` | `http://localhost:7000` | App base URL |
| `TEST_EMAIL` | `local-test-@example.com` | Auto-replaced with timestamped email on first run |
| `TEST_PASSWORD` | `StrongPass#123` | Password for register and login |
| `ACCESS_TOKEN` | _(set by Login)_ | Bearer token extracted automatically after login |

> The collection pre-request script generates a unique timestamped email **once** per run.
> It does not regenerate on each call, so register and login always use the same email.

### Request order

Run requests in this order for a complete auth + stats flow:

1. **Health Check** — verify app is reachable
2. **Register User** — creates account (expects `201`)
3. **Login** — returns and saves `ACCESS_TOKEN` automatically
4. **Fetch Stats** — uses `Authorization: Bearer {{ACCESS_TOKEN}}`
5. **Logout** — revokes token
6. **Verify Revoked Token** — confirms re-use returns `401`

### Run as collection

1. Click the collection name in Postman
2. Click the **▶ Run** button
3. All 6 requests execute sequentially with pass/fail results

### Reset between runs

If you want to register a fresh email:
- In Postman → **Collections** → collection variables
- Reset `TEST_EMAIL` to `local-test-@example.com`
- The next run will auto-generate a new unique email

---


## Run with Docker (image only)

Build the application image without Docker Compose:

```bash
docker build -t wikistreamkotlin:latest .
```

Run the container (requires separate running Cassandra and env vars):

```bash
docker run --rm -p 7000:7000 --name wikistreamkotlin wikistreamkotlin:latest
```

---

## Code Quality Checks

Run detekt + ktlint together via the aggregated Gradle task:

```bash
./gradlew lintKotlin
```

For full failure diagnostics in terminal/CI logs:

```bash
./gradlew lintKotlin --stacktrace
```
