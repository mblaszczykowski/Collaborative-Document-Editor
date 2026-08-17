ALTER TABLE documents
    ADD COLUMN owner_id TEXT REFERENCES users (id) ON DELETE SET NULL,

    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'link'
        CHECK (visibility IN ('private', 'link'));

CREATE INDEX documents_by_owner ON documents (owner_id) WHERE deleted_at IS NULL;

ALTER TABLE document_members
    ADD COLUMN role TEXT NOT NULL DEFAULT 'editor'
        CHECK (role IN ('viewer', 'commenter', 'editor', 'owner'));

CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT        NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor', 'owner')),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX workspace_members_by_user ON workspace_members (user_id);
CREATE INDEX document_members_by_user ON document_members (user_id);
