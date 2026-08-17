ALTER TABLE documents ADD COLUMN search_vector tsvector;

CREATE INDEX documents_content_search
    ON documents
    USING gin (search_vector)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS documents_search;
