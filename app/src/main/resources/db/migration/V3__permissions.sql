-- CP12 — the permission model.
--
-- Until now the access model read "anyone signed in may do anything with any document", and that
-- was coherent, because there was one shared document. Since CP11 there are many documents, so the
-- same model stops being a simplification and becomes a vulnerability.

-- The document's owner. NULL for documents predating this migration — we do not invent an owner
-- for them, because the information about who created them never existed.
ALTER TABLE documents
    ADD COLUMN owner_id TEXT REFERENCES users (id) ON DELETE SET NULL,

    -- VISIBILITY. 'link' by default, that is, "anyone with the address may edit".
    --
    -- A deliberate choice of default, not an oversight. It matches exactly how the application
    -- behaved until now, so the migration breaks nothing and takes nobody's access away — while
    -- the permission mechanism is real and can be turned on per document. The equivalent of a
    -- Google document set to "anyone with the link can edit".
    --
    -- 'private' means: only the owner and the people listed in document_members.
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'link'
        CHECK (visibility IN ('private', 'link'));

CREATE INDEX documents_by_owner ON documents (owner_id) WHERE deleted_at IS NULL;

-- V1 created document_members with no role — back then membership only meant "I have seen this
-- document". Now it carries a permission.
--
-- The roles are ordered ascending, and that ordering is part of the model:
--   viewer    — reads
--   commenter — reads and comments (CP14), does NOT change the text
--   editor    — changes the text and the formatting
--   owner     — additionally manages access
ALTER TABLE document_members
    ADD COLUMN role TEXT NOT NULL DEFAULT 'editor'
        CHECK (role IN ('viewer', 'commenter', 'editor', 'owner'));

-- Workspace-level permissions, inherited by its documents.
--
-- The inheritance is overridable by an entry in document_members — otherwise there would be no way
-- to take away someone's access to ONE document without ejecting them from the whole workspace.
CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT        NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor', 'owner')),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX workspace_members_by_user ON workspace_members (user_id);
CREATE INDEX document_members_by_user ON document_members (user_id);
