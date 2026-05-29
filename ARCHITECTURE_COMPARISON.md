# Redpanda vs Current WebFlux Architecture Comparison

## Current Architecture (WebFlux SSE)

```
Wikimedia Stream
       |
       | (SSE / Server-Sent Events)
       |
    WebClient (Spring WebFlux)
       |
    WikiStreamClient.streamEvents() 
       ↓
    Flow<WikiEvent>
       ↓
    WikiStreamConsumer
       ↓
    Stats aggregation
       ↓
    Cassandra persistence
```

### Current Flow Characteristics
- **Pattern**: Direct pull-based streaming from external source.
- **Integration**: Spring WebFlux reactive streams (Reactor Flux).
- **Backpressure**: WebFlux handles internally via reactive backpressure.
- **State**: In-memory event processing; no persistent event log.
- **Failure Recovery**: Restart from latest stream position (no replay capability).
- **Scalability**: Single instance only (no horizontal scaling of consumption).
- **Latency**: Near-real-time (millisecond range), minimal buffering.
- **Delivery Guarantee**: Best-effort, no acknowledgement semantics.

---

## Proposed Architecture (Redpanda Event-Driven)

```
Wikimedia Stream
       |
       | (SSE)
       |
    Producer Service
    (WebFlux WebClient)
       ↓
    Serialize + envelope
       ↓
    Redpanda Topic
    (Persistent event log)
       ↓
    Consumer Service
    (Kafka consumer, batch mode)
       ↓
    Batch processing + aggregation
       ↓
    Cassandra persistence
       ↓
    Manual offset commit
```

### Proposed Flow Characteristics
- **Pattern**: Event-driven with broker-mediated streaming.
- **Integration**: Spring Kafka (or Apache Kafka client) + Redpanda.
- **Backpressure**: Broker manages producer backpressure; consumer controls batch size and poll rate.
- **State**: Persistent event log in Redpanda; consumer maintains offset state.
- **Failure Recovery**: Replay from saved offset; full replay from topic start available.
- **Scalability**: Multiple consumer instances via consumer group partitions.
- **Latency**: Milliseconds to seconds (depends on batch size and processing latency).
- **Delivery Guarantee**: At-least-once with manual acknowledgement; exactly-once semantic available via idempotent producer + transactional processing.

---

## Key Differences

### 1) **Data Durability**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Persistence | No (in-memory only) | Yes (replicated topic) |
| Replay Capability | None | Full replay from topic beginning or any offset |
| Data Loss Risk | High on crash | Low (broker-replicated) |
| Event Audit Trail | None | Complete |

### 2) **Failure & Recovery**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Producer Failure | Lost connection resumes from live stream position | Messages buffered in broker until retry/success |
| Consumer Failure | Stats miss events until restart | Restart resumes from last committed offset |
| Restart Semantics | Resume live stream (no catch-up) | Replay missed batch(es) from broker |
| Partial Batch Failure | No retry; move on | No commit → retry or DLQ |

### 3) **Scalability**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Horizontal Scaling | ❌ Not possible (single source) | ✅ Multiple consumers per topic partition |
| Load Distribution | Single instance | Partitioned across consumer group |
| Partition Model | Single stream | Topic with multiple partitions |
| Consumer Concurrency | Single source → sequential | Batch per partition + configurable parallelism |

### 4) **Processing Guarantees**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Delivery Model | Best-effort, no ack | Configurable (at-least-once by default) |
| Idempotence | None required | Producer idempotence + deduplication key (optional) |
| Ordering | Source ordering preserved | Per-partition ordering guaranteed |
| Exactly-Once | Not achievable | Achievable with idempotent producer + transactional commit |

### 5) **Operational Complexity**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Components | 1 service | 2+ services (producer, consumer) + broker |
| Configuration | WebFlux client config | Producer/consumer/broker configs |
| Monitoring | App logs, Cassandra metrics | Broker metrics, lag, consumer group status |
| Debugging | WebFlux backlog, app logs | Offset lag, committed position, topic inspection |
| Deployment | Single JAR, one config | Multiple JARs, topic provisioning, offset management |

### 6) **Resource Utilization**
| Aspect | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Memory (in-flight) | Bounded by Reactor pool | Configurable batch size + broker buffering |
| Network Calls | Continuous SSE stream | Batched poll requests |
| CPU | Dedicated to stats processing | Distributed across batching + processing |
| Disk | Cassandra only | Cassandra + Redpanda broker |

### 7) **Latency Profile**
| Scenario | Current (WebFlux) | Redpanda-based |
|--------|---|---|
| Source → Consumer | ~0 (direct) | ~0 to 100ms (broker hop) |
| Source → Cassandra | Real-time, variable | Batch latency + processing |
| End-to-end (SSE → stats) | Milliseconds | Batch window (10s–100s of ms) |

---

## Resilience Patterns Enabled by Redpanda

### A) Topic Replay & Backfill
- **Current**: Impossible; no event history.
- **Redpanda**: Query broker offset, rewind consumer to arbitrary position, replay batches.
- **Use Case**: Fix bug in stats aggregation → replay last 24 hours.

### B) Dual Consumption
- **Current**: Single consumer only.
- **Redpanda**: Multiple consumer groups read same topic independently.
- **Use Case**: A/B test new aggregation logic alongside production.

### C) Dead-Letter Topic
- **Current**: Failed events lost.
- **Redpanda**: Route poison records to DLQ topic for inspection/replay.
- **Use Case**: Analyze malformed events, fix parser, replay from DLQ.

### D) Consumer-Lag Monitoring
- **Current**: No visibility into lag (real-time or lost events unknown).
- **Redpanda**: lag = (broker latest offset) - (consumer committed offset).
- **Use Case**: Alert on lag spike → investigate consumer/broker issues.

---

## When to Use Each

### Use Current WebFlux If:
- ✅ Single instance, real-time processing is sufficient.
- ✅ No replay/audit requirements.
- ✅ Acceptable data loss risk on crash.
- ✅ Operational overhead of broker is not justified.
- ✅ Latency must be sub-100ms unconditionally.

### Use Redpanda If:
- ✅ Want persistent event audit trail.
- ✅ Need horizontal scaling of consumption.
- ✅ Require replay capability for bug fixes or investigation.
- ✅ Want at-least-once or stronger delivery guarantees.
- ✅ Acceptable to add broker deployment/operations overhead.
- ✅ Batch processing (not strictly real-time) is acceptable.
- ✅ Multiple independent consumers of same stream.

---

## Migration Trade-Offs

### Gains
- Durable event log → replay, audit, backfill.
- Horizontal scalability → handle higher throughput.
- Decoupling → producer and consumer fail independently.
- Failure recovery → restart from checkpoint, not live stream only.
- Multiple consumers → route stream to different systems (stats, search, etc.).

### Costs
- Latency increase by batch window (10s–100s ms typical).
- Operational complexity: broker setup, topic management, lag monitoring.
- Message schema versioning discipline required.
- Offset/commit semantics must be understood and tested.
- Storage overhead for topic retention.

---

## Code-Level Differences

### Current Flow (WebFlux)
```kotlin
// Single continuous flow
wikiStreamClient
    .streamEvents()
    .collect { event ->
        statsService.recordForActiveUsers(event)  // Real-time -> DB
    }
```

### Proposed Flow (Redpanda)

**Producer:**
```kotlin
// Enrich event, serialize, publish to broker
val envelope = EventEnvelope(
    schemaVersion = 1,
    timestamp = Instant.now(),
    event = event,
)
kafkaTemplate.send(topic = "wiki.recentchange.raw", event)
```

**Consumer (Batch Mode):**
```kotlin
// Receive batch of events, commit after successful processing
@KafkaListener(
    topics = ["wiki.recentchange.raw"],
    containerFactory = "batchFactory"
)
fun processBatch(events: List<WikiEvent>, ackStrategy: Acknowledgment) {
    events.forEach { statsService.recordForActiveUsers(it) }
    ackStrategy.acknowledge()  // Commit offset only after all processed
}
```

---

## Summary Table

| Dimension | WebFlux (Current) | Redpanda (Proposed) |
|-----------|---|---|
| **Persistence** | 🔴 None | 🟢 Full topic log |
| **Scalability** | 🔴 Single instance | 🟢 Horizontal (consumer groups) |
| **Replay** | 🔴 Not possible | 🟢 Full offset range |
| **Latency** | 🟢 Sub-100ms | 🟡 Batched (10s–100s ms) |
| **Recovery** | 🔴 Live-only | 🟢 Checkpoint-based |
| **Complexity** | 🟢 Simple | 🔴 Higher (multi-service) |
| **Delivery Guarantee** | 🔴 Best-effort | 🟢 At-least-once (configurable) |
| **Operational Overhead** | 🟢 Low | 🔴 Higher (broker ops) |

---

## Decision Matrix

**Choose Redpanda if:**
```
  Audit trail needed?        →  YES
  Horizontal scale needed?   →  YES
  Acceptable batch latency?  →  YES
  Replay important?          →  YES
  Multiple consumers?        →  YES
```

**Stick with WebFlux if:**
```
  Sub-100ms latency critical?  →  YES
  Single instance sufficient?  →  YES
  Data loss acceptable?        →  YES
  Operational simplicity key?  →  YES
```

