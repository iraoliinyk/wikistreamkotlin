-- Development only: drop old schema
DROP MATERIALIZED VIEW IF EXISTS stats_snapshots_latest;
DROP TABLE IF EXISTS stats_snapshots;
DROP TABLE IF EXISTS batch_processing_state;
DROP KEYSPACE IF EXISTS wikistream;

CREATE KEYSPACE IF NOT EXISTS wikistream
    WITH replication = {
        'class': 'NetworkTopologyStrategy',
        'datacenter1': 3
        } AND durable_writes = true;

USE wikistream;

-- 1. COUNTER table for atomic statistical blind updates.
--    Cassandra merges counter deltas across replicas with no RMW required.
CREATE TABLE IF NOT EXISTS stats_counters
(
    user_email     text,
    bucket_day     text, -- "2026-06-23" (UTC)
    total_messages counter,
    bot_count      counter,
    non_bot_count  counter,
    PRIMARY KEY ((user_email, bucket_day))
);

-- 2. Append-only log of server URL hits.
--    Aggregated on read via COUNT(*) GROUP BY server_url.
--    TimeWindowCompactionStrategy cleans old partitions automatically.
CREATE TABLE IF NOT EXISTS server_url_events
(
    user_email text,
    bucket_day text,
    server_url text,
    event_id   timeuuid,
    PRIMARY KEY ((user_email, bucket_day), server_url, event_id)
) WITH compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_size': 1,
    'compaction_window_unit': 'DAYS'
    } AND default_time_to_live = 2592000;
-- 30 days

-- 3. Set-semantics table for distinct users seen per session owner per day.
--    Inserting the same tracked_user_email twice is a no-op (idempotent).
CREATE TABLE IF NOT EXISTS tracked_user_sets
(
    user_email         text,
    bucket_day         text,
    tracked_user_email text,
    PRIMARY KEY ((user_email, bucket_day), tracked_user_email)
) WITH default_time_to_live = 2592000;

CREATE TABLE IF NOT EXISTS user_accounts
(
    email         text PRIMARY KEY,
    password_hash text,
    created_at    timestamp,
    updated_at    timestamp,
    active        boolean
);

CREATE TABLE IF NOT EXISTS revoked_tokens
(
    jti        text PRIMARY KEY,
    email      text,
    expires_at timestamp,
    revoked_at timestamp
);