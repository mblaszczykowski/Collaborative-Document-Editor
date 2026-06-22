-- CP23 — suggestion mode ("track changes").
--
-- The split is the one the comment split made for comments, and it is made here for the same reason:
--
--   THE PROPOSAL ITSELF  — lives in the CRDT. A proposed insertion is real text in the real
--                          character sequence wearing a `suggest-insert` mark; a proposed
--                          deletion is a `suggest-delete` mark over text that is still there.
--                          Nothing about that needs a table — it is in the operation log like
--                          every other operation, and it merges like every other operation.
--
--   EVERYTHING ELSE      — this table. Who proposed it, when, whether it was accepted, and by
--                          whom. None of it is edited concurrently, so none of it needs a CRDT.
--
-- WHY THE ROLE MODEL FINALLY DOES SOMETHING. `commenter` has existed since CP12 as the role that
-- may read and talk but not change the text. Until now that meant a person who could only ever
-- write ABOUT the document. A suggestion is how they contribute TO it without the permission to
-- change it — which is what the role was always for, and what it had no way of expressing.
--
-- WHY THE SERVER CREATES THE OPERATIONS. A proposed insertion needs an Insert operation, and a
-- commenter may not send one — that is the whole point of the role. So the client asks over REST
-- and the server replica generates the operations, exactly as history restore does. The
-- alternative was to let a commenter send inserts and have the frame interceptor verify that each
-- one is covered by a suggestion mark, which means authorization reading the payload of an
-- operation to decide whether the operation is allowed. The README already lists that pattern as
-- a debt where it exists (comment marks); this checkpoint declined to add a second instance.

CREATE TABLE suggestions (
    id          UUID PRIMARY KEY,
    doc_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    author_id   TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    kind        TEXT        NOT NULL CHECK (kind IN ('insert', 'delete')),

    -- What was proposed, as text, at the moment of proposing: the inserted string, or the text
    -- proposed for deletion. Redundant against the marks and DELIBERATELY redundant, for the
    -- same reason comment_threads.quoted_text is: once the anchored characters are gone, the
    -- suggestion has to be describable in a list, and "a proposal about something unknown" is
    -- worth nothing.
    quoted_text TEXT        NOT NULL DEFAULT '',

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Resolution is recorded rather than removed, like everything else that ends in this schema.
    -- "Who rejected my suggestion, and when" is a question with an answer.
    resolved_at TIMESTAMPTZ,
    resolved_by TEXT REFERENCES users (id) ON DELETE SET NULL,
    resolution  TEXT CHECK (resolution IN ('accepted', 'rejected'))
);

CREATE INDEX suggestions_open
    ON suggestions (doc_id, created_at)
    WHERE resolved_at IS NULL;
