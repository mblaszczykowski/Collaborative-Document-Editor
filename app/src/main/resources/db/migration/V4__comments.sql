CREATE TABLE comment_threads (
    id          UUID PRIMARY KEY,
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    created_by  TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    resolved_at TIMESTAMPTZ,
    resolved_by TEXT REFERENCES users (id) ON DELETE SET NULL,

    quoted_text TEXT        NOT NULL DEFAULT ''
);

CREATE INDEX comment_threads_by_document
    ON comment_threads (doc_id, created_at)
    WHERE resolved_at IS NULL;

CREATE TABLE comment_messages (
    id        UUID PRIMARY KEY,
    thread_id UUID        NOT NULL REFERENCES comment_threads (id) ON DELETE CASCADE,
    author_id TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at  TIMESTAMPTZ
);

CREATE INDEX comment_messages_by_thread ON comment_messages (thread_id, created_at);
