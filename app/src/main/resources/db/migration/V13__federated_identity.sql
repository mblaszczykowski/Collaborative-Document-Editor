-- Identity that outlives a browser tab.
--
-- WHAT WAS WRONG. Login was passwordless by name: type anything, get an identity. Every call to it
-- minted a fresh `UUID.randomUUID()`, so "Anna" today and "Anna" tomorrow were two different users,
-- and nothing anywhere verified that the person typing "Anna" was Anna. That was a deliberate
-- scoping decision — the project is about distributed data consistency, not identity — and it was
-- written down. What was not written down is that it made every rule above it rest on an unverified
-- claim: `link` visibility grants EDITOR to "anyone logged in", and logging in cost nothing, so it
-- granted edit to anyone at all.
--
-- It also cost the product real features, quietly. Sharing works by invitation LINK rather than by
-- naming a person because there was nobody stable to name (see V5). `created_by` could not be used
-- for authorization — the comment-anchor check had to be loosened from "the thread you opened" to
-- "a thread in this document", because a replayed offline operation arrives under a NEW identifier
-- after the tab is closed. Both are downstream of identity being ephemeral.
--
-- WHAT REPLACES IT. An OpenID Connect provider vouches for the person, and this table records which
-- provider said so. `(provider, subject)` is the identity: `subject` is the provider's own stable
-- identifier for the account, which is the only field an OIDC provider promises never changes — an
-- email address can be reassigned, and a display name is not identifying at all.
ALTER TABLE users ADD COLUMN provider TEXT;
ALTER TABLE users ADD COLUMN subject  TEXT;
ALTER TABLE users ADD COLUMN email    TEXT;

-- The uniqueness that makes a returning user the SAME user. Partial, because the rows written by
-- the development login have no provider at all and must not collide with each other.
CREATE UNIQUE INDEX users_by_federated_identity
    ON users (provider, subject)
    WHERE provider IS NOT NULL;

-- Handing the browser its session token, without putting the token in a URL.
--
-- The provider redirects back to the SERVER, and the token has to reach the SINGLE-PAGE APP, which
-- is a different origin. Three ways to bridge that, and the choice matters:
--
--   1. Redirect to the app with the token in the URL fragment. No server state, and the token then
--      lives in browser history and in anything that reads `location`. This is what the deprecated
--      implicit flow did, and it is deprecated for this reason.
--   2. Set a cookie. The whole API authenticates with a Bearer header precisely so it has no
--      ambient authority and no CSRF surface; introducing a cookie brings both back for one hop.
--   3. Redirect with a SINGLE-USE code, which the app exchanges for the token over POST. The code
--      is worthless once used and expires in a minute. That is this table.
--
-- Stored HASHED, and the reasoning is V5's, applied to a shorter-lived secret: the row is a
-- credential, and a database read — a backup, a log, a support query — should not yield one. The
-- plaintext exists only in the redirect and in the exchange request.
--
-- IN POSTGRES RATHER THAN IN MEMORY, because the redirect can land on any instance. An in-memory map
-- would work on one and fail on a cluster, which is the failure that only appears in production.
-- Redis would do as well, but the cluster design makes Redis the best-effort path, and a lost login
-- code is a sign-in that fails in the user's face.
CREATE TABLE login_codes (
    code_hash  TEXT        PRIMARY KEY,
    user_id    TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Expiry is checked in the query rather than by a sweep, exactly as V5 does for invitations, so a
-- code cannot be redeemed late even if nothing has cleaned it up yet. The index is for the sweep
-- that does clean up, which keeps the table bounded rather than correct.
CREATE INDEX login_codes_by_age ON login_codes (created_at);
