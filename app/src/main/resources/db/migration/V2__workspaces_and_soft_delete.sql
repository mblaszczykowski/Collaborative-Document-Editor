CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    owner_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX workspaces_by_owner ON workspaces (owner_id);

ALTER TABLE documents
    ADD COLUMN workspace_id UUID REFERENCES workspaces (id) ON DELETE SET NULL,

    ADD COLUMN deleted_at TIMESTAMPTZ,

    ADD COLUMN preview_text TEXT NOT NULL DEFAULT '';

CREATE INDEX documents_live_by_updated
    ON documents (updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX documents_by_workspace
    ON documents (workspace_id, updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX documents_search
    ON documents
    USING gin (to_tsvector('simple', title || ' ' || preview_text))
    WHERE deleted_at IS NULL;
