# About
Spring Boot + Kotlin app that consumes the [wiki-recentchange stream](https://stream.wikimedia.org/v2/stream/recentchange).

Events are stored in local memory and can be retrieved via GET request.

# Read Me First
This project requires Java 24, Kotlin and IntelliJ IDEA to run locally

## Implemented requests
### 1. GET /stats returns recent changes statistics, including:
* number of messages consumed
* number of distinct users
* number of bots and number of non-bots
* count by distinct server URLs

### 2. GET /status health check

## Run with Docker
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
Start the app (builds the image if needed):

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

Fetch recent changes:

```bash
curl http://localhost:7000/v1/stats
```

Verify status endpoint:

```bash
curl http://localhost:7000/v1/status
```
