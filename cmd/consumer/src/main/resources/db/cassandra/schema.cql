-- Development only: wipe and recreate the keyspace. Dropping the keyspace removes
-- ALL objects inside it — including any stale ones from older schema versions
-- (e.g. the former stats_snapshots / stats_snapshots_latest / batch_processing_state).
-- Do NOT add unqualified `DROP TABLE ...` statements above the `USE` below: they run
-- with no keyspace in scope and fail with "No keyspace has been specified", which makes
-- cqlsh exit non-zero and breaks the cassandra-init job.
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

-- Active user sessions (replaces Redis active-session:* / active-session-jtis:*).
-- Single synthetic partition: bucket is always 'all', so listActiveEmails() is
-- one partition read. Rows expire via per-INSERT USING TTL bound to the JWT TTL.
-- default_time_to_live is only a backstop (2x the default token TTL).
--
-- gc_grace_seconds is lowered from the 10-day default to 1 day: rows are
-- ephemeral (~1h TTL) and self-describing (expires_at column), so tombstone
-- resurrection is harmless; short grace caps tombstones scanned by the
-- enumeration read. NOTE: DataStax Astra may reject gc_grace_seconds /
-- compaction overrides — on Astra, drop the WITH clause; defaults are fine
-- at this scale.
CREATE TABLE IF NOT EXISTS active_sessions
(
    bucket      text,          -- always 'all' (synthetic single partition)
    email       text,
    session_id  text,          -- JWT jti, or 'anon-<uuid>' for jti-less logins
    login_at    timestamp,
    expires_at  timestamp,
    instance_id text,
    client_ip   text,
    user_agent  text,
    device_id   text,
    source      text,
    PRIMARY KEY ((bucket), email, session_id)
) WITH default_time_to_live = 7200
   AND gc_grace_seconds = 86400;