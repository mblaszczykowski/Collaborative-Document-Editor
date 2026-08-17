UPDATE document_members SET role = 'editor' WHERE role = 'owner';

ALTER TABLE document_members ALTER COLUMN role DROP DEFAULT;

ALTER TABLE document_members DROP CONSTRAINT document_members_role_check;
ALTER TABLE document_members
    ADD CONSTRAINT document_members_role_check
    CHECK (role IN ('viewer', 'commenter', 'editor'));

CREATE INDEX documents_title_search
    ON documents
    USING gin (to_tsvector('simple', title))
    WHERE deleted_at IS NULL;
