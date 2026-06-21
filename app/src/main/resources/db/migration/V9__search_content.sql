-- CP29 — searching what a document SAYS, not what its first line says.
--
-- The index added in V2 covers `title || ' ' || preview_text`, and the preview is the first 200
-- characters. So searching worked exactly as far as the opening sentence and then stopped, which
-- is the kind of half-feature that is worse than none: it answers, it looks like it searched, and
-- the document that contains the word is not in the results.
--
-- WHAT IS STORED, AND WHAT IS NOT. A tsvector of the content, not the content. Keeping the whole
-- text in a column beside the document would duplicate it — the operation log is the source of
-- truth (ADR-0003) and the snapshot is already a cache of the state. A tsvector is what search
-- needs and nothing else can be read out of it, which also means this column cannot quietly
-- become a second source of truth for the text.
--
-- WHEN IT IS WRITTEN. On the snapshot path, which already materialises `preview_text`, because
-- that is the one moment the content is in hand anyway. The consequence is stated rather than
-- hidden: THE INDEX LAGS. A document is searchable as of its last snapshot, which in production
-- means at most 500 operations or 30 seconds of quiet behind the truth.
--
-- That is the right side of the trade for this system, and the reasoning is the same one ADR-0003
-- used about snapshots: the write path serves people typing, and search serves somebody who will
-- retry in ten seconds if the first attempt missed. Making the index exact would mean recomputing
-- a tsvector on every keystroke — putting the cost of a rare read onto the most frequent write in
-- the system.

ALTER TABLE documents ADD COLUMN search_vector tsvector;

CREATE INDEX documents_content_search
    ON documents
    USING gin (search_vector)
    WHERE deleted_at IS NULL;

-- The expression index from V2 is dropped: the same query cannot use both, and the new column
-- covers everything it covered (the vector is built from the title AND the content). What remains
-- of the old behaviour is the title fallback in DocumentCatalog.search, which exists because a
-- rename does not touch the vector — see the comment there.
DROP INDEX IF EXISTS documents_search;
