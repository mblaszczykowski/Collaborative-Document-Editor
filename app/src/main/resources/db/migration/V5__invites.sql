CREATE TABLE document_invites (
    token_hash TEXT PRIMARY KEY,

    doc_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,

    role       TEXT        NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor')),

    created_by TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    expires_at TIMESTAMPTZ NOT NULL,

    revoked_at TIMESTAMPTZ
);

CREATE INDEX document_invites_live
    ON document_invites (doc_id, created_at DESC)
    WHERE revoked_at IS NULL;
