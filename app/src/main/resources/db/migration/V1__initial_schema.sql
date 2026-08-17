CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    title           TEXT        NOT NULL,
    next_server_seq BIGINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE operations (
    doc_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    replica_id INTEGER     NOT NULL,
    lamport    BIGINT      NOT NULL,
    server_seq BIGINT      NOT NULL,
    op_type    CHAR(1)     NOT NULL,
    payload    JSONB       NOT NULL,
    user_id    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (doc_id, replica_id, lamport)
);

CREATE UNIQUE INDEX operations_by_seq ON operations (doc_id, server_seq);

CREATE TABLE snapshots (
    doc_id           UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    up_to_server_seq BIGINT      NOT NULL,
    format_version   INTEGER     NOT NULL,
    state            BYTEA       NOT NULL,
    text_length      INTEGER     NOT NULL,
    tombstone_count  INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (doc_id, up_to_server_seq)
);

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
