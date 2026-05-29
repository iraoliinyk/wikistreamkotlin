# Redpanda Migration Strategy (Producer/Consumer Split)

## Plan Checklist
- [ ] Align target architecture boundaries and service ownership.
- [ ] Split codebase into `cmd/producer`, `cmd/consumer`, and shared core module.
- [ ] Introduce Redpanda-compatible Kotlin messaging stack and topic contracts.
- [ ] Implement producer: Wikimedia stream -> Redpanda.
- [ ] Implement consumer: Redpanda batch consumption -> stats persistence.
- [ ] Add graceful shutdown and safe acknowledgement handling.
- [ ] Update Docker Compose to run the full stack in Docker.
- [ ] Update unit/integration tests and CI for two services.
- [ ] Execute phased rollout with dual-run verification and cutover.

---

## 1) Goal and Scope
- [ ] Move from direct Wikimedia stream consumption in one app to event-driven architecture with Redpanda as persistence buffer.
- [ ] Split runtime into:
  - [ ] `cmd/producer` (ingest + publish)
  - [ ] `cmd/consumer` (consume + process + persist)
- [ ] Keep behavior continuity for existing stats/auth features during migration.

## 2) Architecture Plan
- [ ] Move from a single reactive ingestion flow to a two-service pipeline with an explicit broker boundary.
- [ ] Treat Redpanda as the durability and replay layer between ingestion and processing.
- [ ] Keep each runtime responsible for one side of the boundary only.

### Service Boundaries and Ownership
| Component | Owns | Does Not Own |
|---|---|---|
| `cmd/producer` | Wikimedia stream intake, parsing/normalization, publish to Redpanda, producer shutdown | Stats persistence, consumer commits, direct Cassandra writes |
| `cmd/consumer` | Redpanda consumption, batch processing, offset commits, stats persistence, DLQ routing | Wikimedia stream intake, broker publishing |
| `lib/core` | Shared DTOs, event envelope, serialization helpers, topic names, error contracts | Runtime wiring, external I/O, offset management |
| Infrastructure | Redpanda, Cassandra, Redis, Docker Compose, observability, CI orchestration | Business logic |

### Runtime Topology
- [ ] Wikimedia SSE -> `cmd/producer` -> `wiki.recentchange.raw` topic in Redpanda -> `cmd/consumer` -> Cassandra/Redis-backed state.
- [ ] Keep producer and consumer independently deployable and independently restartable.
- [ ] Use Redpanda Console or broker metrics only for operational visibility, not for application logic.

### Responsibility Rules
- [ ] Producer must never write directly to Cassandra.
- [ ] Consumer must never read from Wikimedia directly.
- [ ] Any schema or topic contract change must be versioned in `lib/core` before runtime rollout.
- [ ] Offset commits happen only after successful batch persistence.

### Failure and Recovery Model
- [ ] If the producer fails, events stop entering the topic but existing topic data remains available.
- [ ] If the consumer fails, it resumes from the last committed offset after restart.
- [ ] If a malformed event cannot be processed, route it to a DLQ and continue the stream.

### Deployment View
- [ ] Run `producer`, `consumer`, `redpanda`, `cassandra`, and `redis` inside Docker Compose for local/dev parity.
- [ ] Configure internal service DNS names and health checks so startup ordering does not depend on host networking.
- [ ] Separate build artifacts and container images for producer and consumer to support independent rollout.

## 3) Kotlin Library Choice for Redpanda
- [ ] Use Spring Kafka (`org.springframework.kafka:spring-kafka`) as primary Kotlin/Spring integration.
- [ ] Configure against Redpanda Kafka API endpoints (`bootstrap.servers`).
- [ ] Optional fallback path: Apache Kafka client (`org.apache.kafka:kafka-clients`) for low-level control.

## 4) Target Architecture
- [ ] Producer reads Wikimedia SSE and publishes durable events to Redpanda topic(s).
- [ ] Consumer reads only from Redpanda (no direct Wikimedia calls).
- [ ] Consumer updates Cassandra-backed stats using current domain rules.
- [ ] Keep Redis/session/auth behavior where currently required by consumer-side logic.

### Suggested Topic Layout
- [ ] `wiki.recentchange.raw` (primary ingest stream)
- [ ] `wiki.recentchange.dlq` (poison/dead-letter messages)
- [ ] Optional retry topic(s) for delayed retry patterns

## 5) Repository and Module Restructure
- [ ] Convert to multi-module Gradle layout:
  - [ ] `:cmd:producer`
  - [ ] `:cmd:consumer`
  - [ ] `:lib:core` (shared models/parser/error contracts)
- [ ] Move shared domain classes into `lib/core`.
- [ ] Add separate application entrypoints for producer and consumer.
- [ ] Preserve package naming consistency for easier migration.

### Planned File-Level Changes
- [ ] Update `settings.gradle.kts` with new module includes.
- [ ] Refactor root `build.gradle.kts` into shared conventions.
- [ ] Add module build files:
  - [ ] `cmd/producer/build.gradle.kts`
  - [ ] `cmd/consumer/build.gradle.kts`
  - [ ] `lib/core/build.gradle.kts`
- [ ] Add entrypoints:
  - [ ] `cmd/producer/src/main/kotlin/.../ProducerApplication.kt`
  - [ ] `cmd/consumer/src/main/kotlin/.../ConsumerApplication.kt`

## 6) Producer Implementation Strategy (`cmd/producer`)
- [ ] Reuse/adapt Wikimedia stream client and parser from current app.
- [ ] Publish parsed events to Redpanda with resilient producer settings:
  - [ ] `acks=all`
  - [ ] `enable.idempotence=true`
  - [ ] retries/backoff tuned for transient failures
  - [ ] optional compression (`zstd`)
- [ ] Add event envelope/versioning for schema evolution.
- [ ] Expose producer health and readiness endpoints.

### Producer Graceful Termination
- [ ] Stop accepting new SSE records on shutdown signal.
- [ ] Flush in-flight producer messages.
- [ ] Close producer client with timeout.
- [ ] Ensure container stop grace period is sufficient.

## 7) Consumer Implementation Strategy (`cmd/consumer`)
- [ ] Remove direct Wikimedia ingestion dependency.
- [ ] Configure batch consumption from Redpanda:
  - [ ] `enable.auto.commit=false`
  - [ ] tune `max.poll.records`, poll interval, and fetch sizes
- [ ] Process records per batch with bounded concurrency.
- [ ] Persist stats atomically where feasible.

### Acknowledgement and Offset Management
- [ ] Commit offsets only after successful batch processing.
- [ ] Do not commit failed batch offsets.
- [ ] Route malformed/unrecoverable messages to DLQ topic.
- [ ] Ensure rebalance-safe handling for in-flight batches.

### Consumer Graceful Termination
- [ ] Stop polling on shutdown.
- [ ] Complete/abort current batch within timeout policy.
- [ ] Commit successful offsets before close.
- [ ] Close consumer cleanly.

## 8) Docker Compose Migration (`docker-compose.yml`)
- [ ] Add Redpanda broker service.
- [ ] Optional: add Redpanda Console service.
- [ ] Add `producer` and `consumer` services with explicit dependencies.
- [ ] Keep/add Cassandra, Redis, and schema init where needed.
- [ ] Use internal Docker DNS names for all service connections.
- [ ] Add healthchecks and startup ordering.
- [ ] Expose only required host ports.

## 9) Testing Strategy Updates

### Unit Tests
- [ ] Producer tests:
  - [ ] SSE parse/transform behavior
  - [ ] publish retry/idempotence error handling
  - [ ] shutdown flush behavior
- [ ] Consumer tests:
  - [ ] batch processing correctness
  - [ ] commit-on-success behavior
  - [ ] no-commit-on-failure behavior
  - [ ] DLQ routing for poison records

### Integration Tests
- [ ] Add Redpanda Testcontainers setup.
- [ ] Validate end-to-end publish -> consume -> persist flow.
- [ ] Validate restart/recovery semantics and offset continuity.
- [ ] Validate rebalance handling under batch mode.

## 10) CI/CD Strategy Updates
- [ ] Split CI into service-specific jobs/stages:
  - [ ] producer unit/integration/lint
  - [ ] consumer unit/integration/lint
- [ ] Build and publish two Docker images.
- [ ] Add compose-level smoke test to verify full stack.
- [ ] Update release pipeline to version/tag both services.
- [ ] Update quality gates to block on test or lint regressions.

## 11) Rollout Plan (Low-Risk)
- [ ] Phase 1: introduce modules + producer publishes to Redpanda while current consumer still runs.
- [ ] Phase 2: enable new Redpanda consumer in shadow mode and compare output.
- [ ] Phase 3: cut over reads to Redpanda consumer only.
- [ ] Phase 4: remove legacy direct Wikimedia-to-stats path.
- [ ] Phase 5: tune performance and lag alerts.

## 12) Operational Readiness
- [ ] Add dashboards/alerts:
  - [ ] producer publish errors and throughput
  - [ ] consumer lag and batch latency
  - [ ] DLQ volume
- [ ] Define replay/backfill runbook from topic offsets.
- [ ] Define incident handling for broker outages and consumer lag.

## 13) Open Decisions to Finalize Before Build
- [ ] Keep API/auth/stats in `cmd/consumer` or split into separate API service later?
- [ ] Delivery semantics target: at-least-once now, or stronger guarantees?
- [ ] Retry strategy: in-process retry vs retry topic(s)?
- [ ] Schema strategy: JSON with version field now vs schema registry adoption?
- [ ] Partitioning key strategy (e.g., user key) for ordering vs throughput trade-off.

---

## Definition of Done
- [ ] Producer and consumer run independently in Docker Compose.
- [ ] Consumer reads only from Redpanda.
- [ ] Batch consumption + acknowledgement logic verified by tests.
- [ ] Graceful shutdown verified for both services.
- [ ] CI pipelines green for both modules (unit + integration + lint).
- [ ] Documentation updated for local run, test, and deployment flow.

