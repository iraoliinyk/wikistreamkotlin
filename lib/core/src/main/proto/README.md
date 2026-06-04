# Protobuf Definitions - WikiStream Kotlin

This directory contains Protocol Buffer schema definitions for the WikiStream project.

## Files

### `wikievent.proto` (v1.0)

Core protobuf messages for Wikipedia event streaming:

- **WikiEventMeta** - Kafka/stream processing context and metadata
- **WikiEvent** - Main Wikipedia edit event domain model

## Schema Overview

### WikiEventMeta (Fields 1-9)

Encapsulates event processing metadata:

| Field | Number | Type | Nullable | Purpose |
|-------|--------|------|----------|---------|
| uri | 1 | string | No | Source system URI |
| request_id | 2 | string | Yes | Correlation ID for tracing |
| id | 3 | string | No | Unique metadata identifier |
| domain | 4 | string | Yes | Wikipedia domain (e.g., en.wikipedia.org) |
| stream | 5 | string | Yes | Stream category (e.g., revisions) |
| dt | 6 | string | Yes | ISO 8601 timestamp |
| topic | 7 | string | Yes | Kafka topic name |
| partition | 8 | string | Yes | Kafka partition |
| offset | 9 | int64 | Yes | Kafka offset |

### WikiEvent (Fields 1-17, Reserved 51-100)

Core Wikipedia event model:

| Field | Number | Type | Nullable | Purpose |
|-------|--------|------|----------|---------|
| schema | 1 | string | Yes | Event schema version |
| meta | 2 | WikiEventMeta | No | Processing metadata |
| id | 3 | int64 | Yes | Event ID |
| type | 4 | string | Yes | Edit type (edit, new, etc.) |
| namespace | 5 | int32 | Yes | Wikipedia namespace |
| title | 6 | string | Yes | Article title |
| title_url | 7 | string | Yes | URL-encoded title |
| comment | 8 | string | Yes | Edit comment |
| timestamp | 9 | int64 | Yes | Unix milliseconds |
| user | 10 | string | Yes | Username/IP address |
| bot | 11 | bool | No | Bot flag (default: false) |
| notify_url | 12 | string | Yes | Notification URL |
| server_url | 13 | string | Yes | Server base URL |
| server_name | 14 | string | Yes | Hostname |
| server_script_path | 15 | string | Yes | MediaWiki script path |
| wiki | 16 | string | Yes | Wiki database ID |
| parsedcomment | 17 | string | Yes | Formatted comment |

## Backwards Compatibility

### Reserved Field Strategy

**Reserved Range: 51-100**

All future removals must use this range to ensure:
1. Original field numbers never get reused
2. Old and new clients can coexist during migration
3. Wire format remains stable and unambiguous

### How to Handle Deprecation

**Example: Removing a field**

```protobuf
// OLD (before removal):
message WikiEvent {
    optional string deprecated_field = 42;
}

// NEW (after removal):
message WikiEvent {
    reserved 42;
    reserved "deprecated_field";
}
```

**Why this matters:**

- If you simply delete the field, someone might later add a new field with number 42
- An old client with `deprecated_field = 42` would mistake new data for old
- This causes silent data corruption (the worst kind of bug)

## Type Compatibility Matrix

When evolving fields, you can safely perform these transformations:

| From | To | Safe? | Notes |
|------|-----|-------|-------|
| int32 | int64 | ✅ | Larger container, backwards compatible |
| bytes | string | ⚠️ | Only if content is valid UTF-8 |
| string | string | ✅ | No-op, but rename may help |
| Optional → Required | ❌ | NO! | Will break old clients sending unset fields |
| Required → Optional | ✅ | YES | Allows new clients to skip field |
| scalar | repeated | ❌ | NO! | Different wire format |

## Generated Code Location

After running `./gradlew lib:core:generateProto`, generated files appear in:

```
lib/core/build/generated/source/proto/main/
└── com/redspace/wikistreamkotlin/proto/
    ├── WikiEvent.kt
    ├── WikiEventMeta.kt
    ├── WikiEventKt.kt
    ├── WikiEventMetaKt.kt
    └── WikiEventProto.java
```

## Kotlin API Examples

### Building a WikiEvent

```kotlin
import com.redspace.wikistreamkotlin.proto.*

val event = wikiEvent {
    schema = "mediawiki.revision-create"
    meta = wikiEventMeta {
        uri = "https://en.wikipedia.org"
        id = "evt-123"
        domain = "en.wikipedia.org"
        stream = "revisions"
        offset = 12345L
    }
    id = 1000L
    type = "edit"
    namespace = 0
    title = "Example Article"
    titleUrl = "Example_Article"
    comment = "Fixed typo"
    timestamp = System.currentTimeMillis()
    user = "Editor123"
    bot = false
    serverUrl = "https://en.wikipedia.org"
    serverName = "en.wikipedia.org"
    wiki = "enwiki"
}

val bytes = event.toByteArray()
```

### Parsing a WikiEvent

```kotlin
val parsedEvent = WikiEvent.parseFrom(bytes)
println("Edit by: ${parsedEvent.user}")
println("On: ${parsedEvent.title}")
```

### Handling Optional Fields

```kotlin
val event: WikiEvent = // ...

if (event.hasComment()) {
    println("Comment: ${event.comment}")
} else {
    println("No comment provided")
}

// Or in Kotlin DSL:
val comment = event.comment.ifEmpty { "No comment" }
```

## Testing Protobuf Serialization

### Round-trip Serialization Test

```kotlin
@Test
fun `WikiEvent round-trip serialization`() {
    val original = wikiEvent {
        id = 42L
        type = "edit"
        username = "Tester"
    }
    
    // Serialize
    val bytes = original.toByteArray()
    
    // Deserialize
    val restored = WikiEvent.parseFrom(bytes)
    
    // Verify
    assertEquals(original.id, restored.id)
    assertEquals(original.type, restored.type)
    assertEquals(original.username, restored.username)
}
```

### Null Handling Test

```kotlin
@Test
fun `WikiEvent handles optional fields correctly`() {
    val event = wikiEvent {
        id = 1L
        // comment intentionally not set
    }
    
    // Optional fields return empty string by default
    assertTrue(event.comment.isEmpty())
    
    // hasXxx() method checks if field was explicitly set
    assertFalse(event.hasComment())
}
```

## Troubleshooting

### Generated code not found

**Problem:** IntelliJ shows "Cannot resolve symbol" for generated proto classes

**Solution:**
```bash
# 1. Generate proto files
./gradlew lib:core:generateProto

# 2. Rebuild
./gradlew build

# 3. Invalidate IntelliJ caches
# File → Invalidate Caches... → Invalidate and Restart
```

### Compilation errors in .proto

**Problem:** Build fails with proto syntax errors

**Verification:**
```bash
# Check protoc installation
protoc --version

# Validate proto file syntax
protoc --proto_path=lib/core/src/main/proto --cpp_out=/tmp lib/core/src/main/proto/wikievent.proto
```

### Version mismatch

**Problem:** Generated code doesn't match Kotlin compiler version

**Solution:** Ensure aligned versions in `lib/core/build.gradle.kts`:
- Protobuf: 4.28.0+
- Kotlin: 2.3.0+
- Gradle plugin: 0.9.4+

## Evolution Examples

### Adding a New Event Type

```protobuf
// v1.1 update - add new edit classification field
message WikiEvent {
    // ... existing fields ...
    
    // NEW FIELD: Classification tags for the edit
    // Added in v1.1, field 18 (next available)
    repeated string edit_tags = 18;
}
```

### Deprecating a Field

```protobuf
// v1.2 update - deprecated `parsedcomment`, use `parsed_comment` instead
message WikiEvent {
    // ... existing fields ...
    
    // DEPRECATED in v1.2: Use parsed_comment instead
    reserved 17;
    reserved "parsedcomment";
    
    // NEW FIELD: More consistently named version
    optional string parsed_comment = 51;
}
```

## Build Integration

The protobuf plugin is configured in `lib/core/build.gradle.kts`:

```kotlin
plugins {
    id("com.google.protobuf") version "0.9.4"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.0"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("kotlin") {}
            }
        }
    }
}
```

## Next Steps

1. ✅ Proto files defined (`wikievent.proto`)
2. ⬜ Update `lib/core/build.gradle.kts` with protobuf plugin
3. ⬜ Run `./gradlew lib:core:generateProto`
4. ⬜ Create converter functions (domain ↔ proto)
5. ⬜ Add serialization tests
6. ⬜ Integrate into producer/consumer

---

**Last Updated:** 2026-06-03  
**Version:** 1.0  
**Schema Status:** Active  
**Compatibility:** Backwards compatible from v1.0+

