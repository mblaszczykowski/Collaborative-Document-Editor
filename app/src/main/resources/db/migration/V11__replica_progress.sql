CREATE TABLE replica_progress (
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    replica_id  INTEGER     NOT NULL,
    version     JSONB       NOT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    departed_at TIMESTAMPTZ,

    PRIMARY KEY (doc_id, replica_id)
);

CREATE INDEX replica_progress_live
    ON replica_progress (doc_id, reported_at)
    INCLUDE (version, departed_at);
