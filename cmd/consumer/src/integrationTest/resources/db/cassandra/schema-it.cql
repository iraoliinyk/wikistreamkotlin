CREATE KEYSPACE IF NOT EXISTS wikistream_it
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
     AND durable_writes = true;

USE wikistream_it;

CREATE TABLE IF NOT EXISTS stats_counters
(
    user_email     text,
    bucket_day     text,
    total_messages counter,
    bot_count      counter,
    non_bot_count  counter,
    PRIMARY KEY ((user_email, bucket_day))
);

CREATE TABLE IF NOT EXISTS server_url_events
(
    user_email text,
    bucket_day text,
    server_url text,
    event_id   timeuuid,
    PRIMARY KEY ((user_email, bucket_day), server_url, event_id)
);

CREATE TABLE IF NOT EXISTS tracked_user_sets
(
    user_email         text,
    bucket_day         text,
    tracked_user_email text,
    PRIMARY KEY ((user_email, bucket_day), tracked_user_email)
);

CREATE TABLE IF NOT EXISTS active_sessions
(
    bucket      text,
    email       text,
    session_id  text,
    login_at    timestamp,
    expires_at  timestamp,
    instance_id text,
    client_ip   text,
    user_agent  text,
    device_id   text,
    source      text,
    PRIMARY KEY ((bucket), email, session_id)
);
