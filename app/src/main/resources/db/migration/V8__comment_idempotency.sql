-- CP27 — comments that survive being written without a connection.
--
-- The offline buffer (CP6) covers CRDT operations, because those travel the WebSocket. A comment
-- travels REST, so a comment written with no connection simply disappeared — the README listed it
-- as a limit of CP14.
--
-- WHAT THIS COLUMN IS, AND WHY IT IS NOT A CRDT. The temptation is to make comments a CRDT too:
-- the machinery is right there. The comment split said no, on the grounds that a comment message has one
-- author, is written in one go, and is never concurrently edited character by character. CP27 is
-- where that claim gets tested by use rather than by argument — and it holds, because the property
-- the offline path actually needs is not merging. It is EXACTLY ONCE.
--
-- A client that writes a comment offline, retries on reconnect, and cannot tell whether the first
-- attempt reached the server needs one thing: a key that makes the second attempt a no-op. That is
-- an idempotency key, which is a column, not a data structure.
--
-- NULLABLE, because every row written before CP27 has no such key and there is nothing to invent
-- for them. UNIQUE, because a key that does not have to be unique guarantees nothing.

ALTER TABLE comment_threads ADD COLUMN client_id TEXT;
ALTER TABLE comment_messages ADD COLUMN client_id TEXT;

CREATE UNIQUE INDEX comment_threads_by_client_id
    ON comment_threads (client_id)
    WHERE client_id IS NOT NULL;

CREATE UNIQUE INDEX comment_messages_by_client_id
    ON comment_messages (client_id)
    WHERE client_id IS NOT NULL;
