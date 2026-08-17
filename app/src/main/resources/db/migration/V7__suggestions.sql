CREATE TABLE suggestions (
    id          UUID PRIMARY KEY,
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    author_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    kind        TEXT        NOT NULL CHECK (kind IN ('insert', 'delete')),

    quoted_text TEXT        NOT NULL DEFAULT '',

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    resolved_at TIMESTAMPTZ,
    resolved_by TEXT REFERENCES users (id) ON DELETE SET NULL,
    resolution  TEXT CHECK (resolution IN ('accepted', 'rejected'))
);

CREATE INDEX suggestions_open
    ON suggestions (doc_id, created_at)
    WHERE resolved_at IS NULL;
