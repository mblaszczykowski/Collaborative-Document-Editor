-- Character counts stored alongside each operation, so the timeline stops reading payloads.
--
-- WHAT WAS WRONG. HistoryService.timeline() ran:
--
--     SELECT o.server_seq, o.op_type, o.payload, o.created_at, ... FROM operations o
--      WHERE o.doc_id = ? ORDER BY o.server_seq
--
-- with no LIMIT and no pagination, and with the payload column — the largest one in the table —
-- included. A document with 100 000 operations sent its ENTIRE log across the wire every time
-- somebody opened the history panel, to compute two integers per group.
--
-- The interesting part is that the code already knew half of this. Its javadoc says the counts
-- are read from the JSON lazily "because building an operation object from each of thousands of
-- rows would be wasteful on a path that only needs lengths". It avoided the PARSING and paid for
-- the TRANSFER, which is the larger of the two.
--
-- The write path knows both numbers at the moment it inserts the row, so this is not a cache
-- that can drift — it is data that was being recomputed instead of recorded.

ALTER TABLE operations
    ADD COLUMN chars_inserted INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN chars_deleted  INTEGER NOT NULL DEFAULT 0;

-- BACKFILL FROM THE PAYLOAD, rather than leaving old rows at zero.
--
-- Leaving them would make the history of every document that existed before this migration read
-- "+0 −0" — not a crash, just quietly wrong numbers in the one place the project claims to
-- record what actually happened. The payload is still there and still parseable, so there is no
-- reason to accept that.
--
-- The shapes come from OpCodec: an insert carries its text in "s", a delete carries ranges in
-- "g" as [replica, starting lamport, length] triples.

UPDATE operations
   SET chars_inserted = COALESCE(length(payload ->> 's'), 0)
 WHERE op_type = 'i';

UPDATE operations
   SET chars_deleted = COALESCE((
           SELECT SUM((range_entry ->> 2)::int)
             FROM jsonb_array_elements(payload -> 'g') AS range_entry
       ), 0)
 WHERE op_type = 'd';

-- Mark operations ('m', 'x') change no character counts and keep the default of 0. They still
-- appear on the timeline as operations, because a formatting change IS a change to the document
-- — but describing one as "+0 −0" would be misleading, so HistoryService reports the count and
-- leaves the character columns alone.

-- The timeline reads a document's operations in server_seq order. The primary key is
-- (doc_id, replica_id, lamport), which does not serve that; operations_by_seq
-- (doc_id, server_seq) does, and now covers this query entirely — the planner can answer it
-- from the index plus a heap fetch of four narrow columns instead of the JSONB.
COMMENT ON COLUMN operations.chars_inserted IS
    'Characters this operation added. Recorded at write time; see V6 for why not derived.';
COMMENT ON COLUMN operations.chars_deleted IS
    'Characters this operation removed. Recorded at write time; see V6 for why not derived.';
