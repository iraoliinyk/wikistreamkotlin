# About
Spring Boot + Kotlin app that consumes the [wiki-recentchange stream](https://stream.wikimedia.org/v2/stream/recentchange).

Events are aggregated into a `StatsSnapshot` and stored in Cassandra. The latest snapshot can be retrieved via GET request.

# Read Me First
This project requires Java 24, Kotlin and IntelliJ IDEA to run locally.

Local Cassandra is started through `docker compose`.

Sensitive Cassandra or Astra credentials must not be committed. Copy `config/cassandra-secrets.properties.template` to `config/cassandra-secrets.properties` when you need secure credentials locally.

JWT auth secrets must not be committed. Copy `config/auth-secrets.properties.template` to `config/auth-secrets.properties` and set a strong signing secret.

## Implemented requests
### 1. GET  /v1/stats returns recent changes statistics, including:
* number of messages consumed
* number of distinct users
* number of bots and number of non-bots
* count by distinct server URLs

### 2. GET  /v1/status health check

### 3. Authentication APIs
* `GET /v1/auth/email-exists?email=<email>` checks whether user exists
* `POST /v1/auth/register` registers a user with email + password
* `POST /v1/auth/login` returns JWT Bearer access token
* `POST /v1/auth/logout` revokes current token (requires `Authorization: Bearer <token>`)

`GET /v1/stats` now requires a valid Bearer token.

## Cassandra configuration

The application uses Spring Boot Cassandra properties from `src/main/resources/application.properties`.

Default local values:

- contact point: `localhost`
- port: `9042`
- keyspace: `wikistream`
- datacenter: `datacenter1`

Schema for local Docker Cassandra is defined in `src/main/resources/db/cassandra/schema.cql`.

## Run with Docker
The Docker build uses the Gradle wrapper files committed in `gradle/wrapper/` together with `gradlew`.

Build the application image:

```bash
docker build -t wikistreamkotlin:latest .
```

Run the container and publish app port 7000:

```bash
docker run --rm -p 7000:7000 --name wikistreamkotlin wikistreamkotlin:latest
```

Verify status endpoint:

```bash
curl http://localhost:7000/v1/status
```

## Run with Docker Compose
Start Cassandra, initialize the schema, and then start the app (builds the image if needed):

```bash
docker compose up --build
```

Run in detached mode:

```bash
docker compose up --build -d
```

Stop and remove containers:

```bash
docker compose down
```

Verify that data is persisted in Cassandra:

```bash
docker exec wikistreamkotlin-cassandra cqlsh -e "SELECT id, total_messages, distinct_users, bot_count, non_bot_count FROM wikistream.stats_snapshots;"
```

Fetch recent changes:

```bash
curl http://localhost:7000/v1/stats
```

Verify status endpoint:

```bash
curl http://localhost:7000/v1/status
```
