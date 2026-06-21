-- The schema follows directly from ADR-0003 (the operation log as the source of truth, snapshots
-- as a cache) and ADR-0004 (server_seq as the cursor for resynchronisation and anti-entropy).

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    title           TEXT        NOT NULL,
    -- The server_seq counter lives on the document rather than being a Postgres sequence.
    -- The reason: a sequence leaves gaps when a transaction rolls back, and ADR-0004 bases
    -- detection of lost operations on this counter's continuity. The price is a row lock on
    -- write, that is, serialised writes within ONE document — acceptable, because writes are
    -- batched every 50 ms anyway (application.yml).
    next_server_seq BIGINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- THE SOURCE OF TRUTH. An append-only table — never UPDATE, never DELETE.
-- Restoring an old version of a document is recorded as NEW operations (ADR-0003, point 6),
-- because truncating the log would destroy auditability and diverge the connected clients.
CREATE TABLE operations (
    doc_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    replica_id INTEGER     NOT NULL,
    lamport    BIGINT      NOT NULL,
    server_seq BIGINT      NOT NULL,
    op_type    CHAR(1)     NOT NULL,
    payload    JSONB       NOT NULL,
    user_id    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The primary key is the CRDT operation's identifier. That makes write idempotence a
    -- property of the schema rather than of deduplication code: a retransmission after a
    -- reconnect simply cannot create a second row.
    PRIMARY KEY (doc_id, replica_id, lamport)
);

-- The resynchronisation cursor: "give me everything above server_seq X".
-- UNIQUE, because two operations with the same server_seq would mean a counter allocation bug.
CREATE UNIQUE INDEX operations_by_seq ON operations (doc_id, server_seq);

-- A CACHE. It may be deleted at any moment — the system will rebuild the state from the log,
-- more slowly but correctly. A test in CP5 deletes every snapshot and checks that no result
-- changes; without such a test the cache would quietly become the source of truth.
CREATE TABLE snapshots (
    doc_id           UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    up_to_server_seq BIGINT      NOT NULL,
    -- The serialisation format version. On a mismatch the snapshot is IGNORED rather than
    -- migrated — migrating a cache is a cost with no benefit when the log suffices.
    format_version   INTEGER     NOT NULL,
    state            BYTEA       NOT NULL,
    -- Tombstone overhead metrics (ADR-0005) recorded at snapshot time, so they can be graphed
    -- without loading the whole state into memory.
    text_length      INTEGER     NOT NULL,
    tombstone_count  INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (doc_id, up_to_server_seq)
);

-- A simplified model of users and access. Deliberately minimal — a full model of roles and
-- sharing is outside the project's scope (README, "what I would do differently").
CREATE TABLE users (
    id           TEXT PRIMARY KEY,
    display_name TEXT        NOT NULL,
    color        TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_members (
    doc_id    UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    user_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, user_id)
);
