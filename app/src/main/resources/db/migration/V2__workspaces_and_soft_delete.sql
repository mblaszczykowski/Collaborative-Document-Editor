-- CP11 — many documents. The V1 schema was enough for one shared document; this adds what is
-- needed to tell documents apart, group them, and delete one without destroying the source of
-- truth.

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    owner_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX workspaces_by_owner ON workspaces (owner_id);

ALTER TABLE documents
    -- NULL means a document outside any workspace ("private"). The column is nullable on
    -- purpose: documents created before this migration belong to no workspace, and assigning
    -- them to an artificial "default" would be inventing a fact that does not exist. List
    -- queries therefore have to handle NULL, and that is explicit.
    ADD COLUMN workspace_id UUID REFERENCES workspaces (id) ON DELETE SET NULL,

    -- SOFT DELETE, not DELETE.
    --
    -- The operations table has ON DELETE CASCADE on doc_id, so a hard delete of a document
    -- would erase its operation log — the SOURCE OF TRUTH (ADR-0003), which in this system is
    -- append-only and never deleted. A deleted document should be unreachable, not
    -- irreversibly destroyed; those are two different things, and only the first is what the
    -- user clicking "delete" wants.
    ADD COLUMN deleted_at TIMESTAMPTZ,

    -- A CACHE of the content for the document list. Materialised at snapshot time.
    --
    -- Without it the document list would have to load a CRDT replica into memory per entry to
    -- show the first three lines — so opening a list of a hundred documents would load a
    -- hundred replicas. It is the same trap treating a snapshot as a cache guards against, so
    -- the same rule applies: this MAY be deleted at any moment, and the system will rebuild the
    -- preview at the next snapshot.
    ADD COLUMN preview_text TEXT NOT NULL DEFAULT '';

-- The document list always asks for the undeleted ones, ordered by change date. A partial index,
-- because deleted documents appear on no list except the bin — there is no reason for them to
-- occupy space in an index serving the hot path.
CREATE INDEX documents_live_by_updated
    ON documents (updated_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX documents_by_workspace
    ON documents (workspace_id, updated_at DESC)
    WHERE deleted_at IS NULL;

-- Full-text search over the title and the content preview.
--
-- 'simple' rather than a language-specific configuration: those are not in the standard Postgres
-- distribution (they need ispell dictionaries), so the docker-compose.yml image would not have one
-- and the migration would fail at startup. 'simple' does no stemming, so "documents" will not be
-- found by searching for "document" — a known limitation rather than an oversight, and one that
-- goes into the "what I would do differently in production" section.
CREATE INDEX documents_search
    ON documents
    USING gin (to_tsvector('simple', title || ' ' || preview_text))
    WHERE deleted_at IS NULL;
