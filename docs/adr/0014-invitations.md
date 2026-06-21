# ADR-0014 — Sharing by invitation link, not by naming a person

Status: **Accepted** · CP21

## Context

The role model has been complete since CP12 (`viewer`, `commenter`, `editor`, `owner`), enforced
in both layers from one rule ([ADR-0009](0009-permission-model.md)), and enforced *structurally*
since CP16 ([ADR-0012](0012-structural-authorization.md)). Reviewing plan v4 against the code
found that none of it was reachable from the running product:

- `PermissionService.grant`, `revoke` and `membersOf` were called from `PermissionIT` and
  `CommentIT`, and from no production code at all;
- `Capability.MANAGE_ACCESS` was declared, documented, and required by **no endpoint**;
- `POST /api/documents` left `visibility` at the schema default `link`, and `link` resolves to
  `EDITOR` for every logged-in user.

So the product could produce two roles — owner, and "anybody who logged in" — while the
repository described four. On a laptop that reads as a convenience. On the public instance plan
v3 §0.1 wants, it is the entire access-control story being false in the one place a visitor can
check it.

Adding the endpoints looks like an afternoon of work, and then it meets the fact that makes it
interesting: **there is nothing to type into a share box.** Login has no password and mints a
fresh `userId` on every call, deliberately (README, *Infrastructure and operations*). "Anna"
today and "Anna" tomorrow are two different users. A person is not addressable before they
arrive.

## Options considered

**1. Grant by user identifier.** The API the service layer already implies: the owner supplies a
`userId` and a role. Correct, and unusable — the only place a user identifier is visible is the
presence frames of a document you already share with them. Sharing would require having already
shared.

**2. Grant by user identifier, plus a user directory to find one.** Fixes usability by making
every user searchable by every other user. That is a bigger disclosure than the problem it
solves, and it is disclosure of exactly the identifier the presence layer treats as sensitive
enough to have caused an IDOR fix in CP11 (`?owner=` taken from the request instead of the
token).

**3. Stable identity derived from the display name.** Would make invitations by name possible and
would merge every two people who pick the same name into one account. A collision here is not an
inconvenience; it is one person getting another person's documents.

**4. An invitation that carries the role.** The owner mints a link; whoever redeems it gets
exactly the role the link names, and identity is bound at redemption. Nothing has to be known
about the recipient beforehand.

**Within option 4, a second choice: a signed token or a stored row.** A signed token needs no
storage and the project already signs one thing (`TokenService`). It also cannot be revoked,
listed, or counted — and "I shared this and I want that to stop" is the point of the feature.

## Decision

**Sharing is by invitation link, and an invitation is a row.**

- `POST /api/documents/{id}/access/invites` mints a 192-bit random token, stores **only its
  SHA-256 hash**, and returns the plain token once. The panel shows it once; re-opening lists the
  invitation and cannot show the link again.
- `POST /api/invites/redeem` exchanges the token for the role it carries. The document comes from
  inside the invite, never from the request.
- `PUT/DELETE /api/documents/{id}/access/members/{userId}` and
  `PUT /api/documents/{id}/access/visibility` are the direct controls, for the case where the
  person is already known — which, after the first redemption, they are.
- All of the above require `Capability.MANAGE_ACCESS`, including the **read** of the member list:
  "who has access to this" is a different question from "what does this say".
- **New documents are private.** `link` visibility stays on offer; it stopped being what happens
  when nobody chooses.

Two refusals are enforced twice, in the API and in the schema, because a refusal that exists in
one layer is a refusal that a bug in that layer removes:

- an invitation cannot grant `owner` (API check + `CHECK` constraint);
- the owner cannot revoke themselves (API check + the fact that ownership lives in
  `documents.owner_id` rather than in `document_members`).

## Consequences

**Positive**

- The four roles become reachable, which turns two ADRs and a checkpoint from a description into
  a feature that can be demonstrated in the product in about fifteen seconds.
- `MANAGE_ACCESS` acquires its first user, so the CP16 vocabulary is now evidenced rather than
  asserted.
- Revocation and expiry are real: an invitation can be taken back, and it stops working on its
  own without a sweep having to run — expiry is checked at redemption, so nothing has to be
  scheduled for the security property to hold.
- Passwordless login stops being a limitation for sharing and becomes irrelevant to it.

**Negative — and the first two are the honest cost of this design**

- **An invitation is a bearer credential.** Whoever holds the link gets the role: forwarding it
  shares access, and there is no second factor. There cannot be one in a system with no
  passwords. Mitigations are partial: expiry, revocation, hashed storage, and the token being
  stripped from the address bar immediately after redemption.
- **An invitation cannot be aimed at one person.** "Anyone with this link, as an editor" is
  expressible; "Anna, as an editor" is not, until Anna has redeemed something. Fixing that needs
  durable identity — that is, accounts — which is out of scope for the same reason it always was.
- The member list shows display names, which are not unique. Two people called Anna are two rows
  that look the same. Removing the wrong one is recoverable (mint another link), but it is a real
  confusion and it comes from the same root as the two items above.
- `document_invites` grows without bound: nothing deletes revoked or expired rows. The same class
  of debt as the snapshot table, and worth the same fix — a retention policy — rather than a
  different one.
- Changing the creation default to `private` changes behaviour for anybody used to the old one.
  Existing rows are untouched (the column default stays `link`), so this is a change in what new
  documents do, not a migration that takes access away.

**To be verified in CP22+:** whether the role-change event is enough on its own. It is
instance-local by design — a client on another instance learns of the change when its permission
cache entry expires (5 s), the same bound that already governs revocation. If that turns out to
be visible in practice, the fix is to carry the fact on the permission invalidation broadcast
rather than to build a second channel for it.
