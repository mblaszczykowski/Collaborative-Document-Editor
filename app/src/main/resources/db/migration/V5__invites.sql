-- CP21 — sharing a document with a specific person.
--
-- WHY AN INVITE TABLE AND NOT "GRANT A ROLE TO A USER".
--
-- Granting a role is one line and it already exists (PermissionService.grant). What does not
-- exist is a way for the owner to NAME the person. Logging in has no password and mints a fresh
-- identifier every time (DocumentRestController.login), so "Anna" today and "Anna" tomorrow are
-- two different users. There is nothing stable to type into an invite box.
--
-- Two ways out, and only one of them survives contact with that fact:
--
--   by identifier — the owner would have to obtain somebody's user id, and the only place ids
--                   are visible is the presence frames of a document they already share. So
--                   sharing would require having already shared. A directory endpoint would
--                   fix that by making every user searchable by everyone, which is a worse
--                   trade than the problem it solves.
--
--   by link       — the owner mints an invite that CARRIES the role, and whoever redeems it
--                   gets exactly that role. No identity is needed before the fact, which is
--                   the same reason link sharing exists in products people already use.
--
-- The second one is also the only one that fits passwordless login: identity is created at the
-- moment of redemption, and the invite is what connects it to a document.
--
-- WHY THE TOKEN IS STORED HASHED. The row is a bearer credential: whoever holds the token gets
-- the role. A dump of this table (a backup, a log, a curious reader of the database) must not be
-- a set of working invitations, exactly as it must not be a set of working passwords. The plain
-- token exists once, in the response to the request that created it.
--
-- WHY NOT A SIGNED TOKEN INSTEAD OF A TABLE. A signed token needs no storage, and the project
-- already signs one thing (TokenService). It also cannot be revoked, cannot be listed, and
-- cannot be counted — and "I shared this with somebody and I want that to stop" is the whole
-- point of the feature. Storage buys revocation; that is the trade.

CREATE TABLE document_invites (
    -- SHA-256 of the token, hex. Primary key, so redeeming is a point lookup on a value the
    -- server computes from what the caller presents.
    token_hash TEXT PRIMARY KEY,

    doc_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,

    -- 'owner' is deliberately absent from the constraint.
    --
    -- Ownership transfer is a different operation with a different consequence: the previous
    -- owner may lose the ability to undo it. Folding it into "pick a role" is how that happens
    -- by accident. The API refuses it as well — this is the second layer of the same refusal,
    -- and the layer that survives a bug in the first.
    role       TEXT        NOT NULL CHECK (role IN ('viewer', 'commenter', 'editor')),

    created_by TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Every invite expires. An invitation with no end is a permanent hole that nobody
    -- remembers creating.
    expires_at TIMESTAMPTZ NOT NULL,

    -- Revocation is soft, like every other removal in this schema: the row stays so that
    -- "this link stopped working" is answerable, rather than indistinguishable from
    -- "this link never existed".
    revoked_at TIMESTAMPTZ
);

CREATE INDEX document_invites_live
    ON document_invites (doc_id, created_at DESC)
    WHERE revoked_at IS NULL;
