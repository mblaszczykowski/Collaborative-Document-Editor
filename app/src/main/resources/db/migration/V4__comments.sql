-- CP14 — comments.
--
-- A comment is made of two things, of which ONLY ONE is a CRDT:
--
--   THE ANCHOR  — a range in the document. It is a mark of type `comment` carrying the thread
--                 identifier as its value, that is, exactly the machinery CP13 introduced
--. This checkpoint USES it rather than building it. It has no table,
--                 because it lives in the operation log like any other CRDT operation.
--
--   THE CONTENT — the messages in the thread. The tables below.
--
-- WHY THE CONTENT IS NOT A CRDT. A message has one author, is created whole, and is not
-- concurrently edited character by character. A CRDT solves a specific problem — concurrent
-- editing of the same sequence — and applying it where that problem does not exist is a cost with
-- no benefit: an operation log, merging, tombstones and GC for text nobody ever edits in parallel.
--
-- The temptation of "we have a CRDT, so everything will be a CRDT" is real, and this is the place
-- where we deliberately do not give in to it.

CREATE TABLE comment_threads (
    id          UUID PRIMARY KEY,
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    created_by  TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Resolving a thread, not deleting it. The conversation stays — all that changes is whether
    -- it is visible by default. The same principle as soft-deleting documents.
    resolved_at TIMESTAMPTZ,
    resolved_by TEXT REFERENCES users (id) ON DELETE SET NULL,

    -- A copy of the text the thread is about, as of when it was opened.
    --
    -- Redundant with the anchors, and DELIBERATELY redundant: when the anchored text is deleted
    -- the thread becomes "detached", and without this copy there is no way to show what it was
    -- about. A conversation about an unknown fragment is worth little.
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
