-- Two corrections to earlier migrations, both of the same kind: a rule that was written down in
-- one place and enforced in another.

-- ---------------------------------------------------------------------------
-- 1. document_members.role must not permit 'owner' — the second layer the invitation design claims exists.
-- ---------------------------------------------------------------------------
--
-- V5 spends six lines on this, on document_invites:
--
-- 'owner' is deliberately absent from the constraint. Ownership transfer is a different
-- operation with a different consequence: the previous owner may lose the ability to undo it.
-- Folding it into "pick a role" is how that happens by accident. The API refuses it as well —
-- this is the second layer of the same refusal, and the layer that survives a bug in the first.
--
-- The reasoning is right and it was applied to the wrong table. document_invites is a bearer
-- credential that is REDEEMED into a document_members row; document_members is what
-- PermissionService.grant actually writes, and what PermissionService.roleFrom actually reads. Its
-- CHECK (V3) permits 'owner', so the "layer that survives a bug in the first" did not exist where a
-- bug in the first would land.
--
-- What such a row would do is not cosmetic. roleFrom resolves an explicit member row BEFORE the
-- visibility rule and parses it with Role.parse, so role = 'owner' resolves to Role.OWNER — full
-- MANAGE_ACCESS, granted by a table entry rather than by documents.owner_id, which is where this
-- system's notion of ownership actually lives. Two answers to "who owns this document", one of them
-- reachable only by a bug.
--
-- WHY THE DEFAULT GOES TOO. V3 gave the column DEFAULT 'editor', which made "insert a membership
-- row" a way to hand out write access without naming a role. Every caller in the codebase names one
-- (AccessController.grantable refuses anything else, and it refuses 'owner' explicitly), so the
-- default served nothing except a future INSERT that forgot — and the failure of that INSERT is
-- exactly the kind of thing this constraint should make loud rather than convenient.

-- Existing rows first, because the constraint below would refuse to attach otherwise and a
-- migration that fails at startup takes the whole instance down.
--
-- This is expected to affect ZERO rows on any instance this code has run: nothing in the
-- application can write 'owner' here (AccessController.grantable rejects the name, InviteService
-- cannot issue it, and no test inserts it). It is written anyway because a migration that assumes
-- its own history is a migration that fails on the one database that had it.
--
-- Downgraded to 'editor' rather than deleted: the row means somebody was deliberately given access,
-- and the strongest thing this table is allowed to say is 'editor'. Real ownership is unaffected —
-- it is documents.owner_id, which this migration does not touch.
UPDATE document_members SET role = 'editor' WHERE role = 'owner';

ALTER TABLE document_members ALTER COLUMN role DROP DEFAULT;

ALTER TABLE document_members DROP CONSTRAINT document_members_role_check;
ALTER TABLE document_members
    ADD CONSTRAINT document_members_role_check
    CHECK (role IN ('viewer', 'commenter', 'editor'));

-- workspace_members is deliberately left alone. Its CHECK permits 'owner' too, and the question
-- there is a different one that has not been answered yet: a workspace has an owner_id of its own,
-- and whether a workspace member may be recorded as its owner is a decision about workspaces rather
-- than an oversight about documents. Narrowing it here would be changing a model on the way past.

-- ---------------------------------------------------------------------------
-- 2. An index for the title half of the search query, which V9 removed.
-- ---------------------------------------------------------------------------
--
-- V9 dropped documents_search — the expression index over to_tsvector(title || ' ' || preview_text)
-- — saying:
--
-- the same query cannot use both, and the new column covers everything it covered
--
-- Neither half is true, and the first is the important one. DocumentCatalog.search ORs two
-- predicates:
--
-- d.search_vector @@ websearch_to_tsquery('simple', ?)
-- OR to_tsvector('simple', d.title) @@ websearch_to_tsquery('simple', ?)
--
-- Postgres serves an OR of two indexable predicates with a BitmapOr: each branch produces a bitmap
-- from its own index and the two are unioned. It uses both, and it does so routinely. What it
-- cannot do is build a BitmapOr when ONE branch has no index — there is no partial plan, so the
-- whole thing falls back to a sequential scan over every live document, on the endpoint that runs
-- on every keystroke in the search box.
--
-- The second half is not true either, in the one case the title branch exists for: a rename does
-- not rebuild search_vector (that happens on the snapshot path), and a document that has never been
-- snapshotted has no vector at all — which is precisely the document somebody just created and is
-- now looking for. DocumentCatalog.search says this in a comment beside the OR; V9 dropped the
-- index that made the comment affordable.
--
-- Indexed on the title alone, not on title || preview_text as V2 did: preview_text is now covered
-- by search_vector, and an expression index has to match the query's expression exactly to be used
-- at all.
--
-- Partial on deleted_at IS NULL, like every other index in this schema that serves the document
-- list: the bin is read by one endpoint with its own filter, and excluding deleted rows keeps the
-- index proportional to what is actually searched.
CREATE INDEX documents_title_search
    ON documents
    USING gin (to_tsvector('simple', title))
    WHERE deleted_at IS NULL;
