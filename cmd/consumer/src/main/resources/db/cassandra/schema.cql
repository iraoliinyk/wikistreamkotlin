CREATE KEYSPACE IF NOT EXISTS wikistream
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE wikistream;

CREATE TABLE IF NOT EXISTS stats_snapshots (
    id text PRIMARY KEY,
    total_messages int,
    distinct_users int,
    bot_count int,
    non_bot_count int,
    count_by_server_url map<text, int>,
    tracked_users set<text>,
    version bigint
);

CREATE TABLE IF NOT EXISTS user_accounts (
    email text PRIMARY KEY,
    password_hash text,
    created_at timestamp,
    updated_at timestamp,
    active boolean
);

CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti text PRIMARY KEY,
    email text,
    expires_at timestamp,
    revoked_at timestamp
);

