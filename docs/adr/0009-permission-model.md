# ADR-0009 — The permission model

- **Status:** Accepted
- **Date:** 2026-08-15
- **Applies to checkpoints:** CP12 (implementation), CP14 (the commenter role gains substance)

---

## Context

Up to CP11 the access model read "any logged-in user may do anything with any document", and it
was coherent, because there was one shared document. CP11 introduced multiple documents, and a
document identifier is part of the address supplied by the client — both in the STOMP destination
(`/topic/doc/{id}/ops`) and in the REST path (`/api/documents/{id}`).

The same simplification therefore stopped being a simplification and became a vulnerability: any
logged-in user could read someone else's document live, or fetch its content with a single GET.

Three questions to settle:
1. **How to express permissions** — a flat list or an ordering?
2. **Where to enforce them** — and is one place enough?
3. **What about documents that already exist** and have no owner at all?

---

## Options considered

### 1. Expressing permissions

**Option A — a set of independent permissions** (`READ`, `WRITE`, `COMMENT`, `SHARE`).

**For:** flexible; it can express "may share but not edit".
**Against:** every check is a set-membership question, and there are exponentially many
combinations. Four of them make sense, and the rest are states nobody thought about — and that is
where the holes appear.

**Option B — ordered roles** (`viewer` < `commenter` < `editor` < `owner`).

**For:** checking a permission is a **comparison** rather than a table of exceptions. The set of
states is finite and enumerated. Every role can do everything the previous one can, so it is
impossible to construct a role that can do both more and less at once.
**Against:** it cannot express "may share but not edit". Deliberately: that is not a combination
anyone needs in a document editor.

### 2. Where to enforce

**Option A — the WebSocket layer only.** Editing goes there, so that is where the value is.
**Against:** REST returns the full document content (`GET /api/documents/{id}`) and the change
history. Closing one of two routes to the same data is **worse than closing neither**, because it
gives a false sense that the problem is solved.

**Option B — both layers, one rule.** A shared `PermissionService` with two entry points: the STOMP
channel interceptor and an HTTP filter.

### 3. Documents with no owner

**Option A — close everything, require access to be granted explicitly.**
**Against:** the migration revokes access to documents that used to work. A security measure that
breaks existing data does not reach production — it gets switched off and protects nothing.

**Option B — visibility as a property of the document**, defaulting to `link` ("anyone with the
address may edit"), switchable to `private`.

---

## Decision

**1B + 2B + 3B.** Ordered roles, enforcement in both layers from one rule, visibility defaulting to
`link`.

### The order in which a role is resolved

1. **Owner** → `owner`. An owner's access cannot be revoked by a table entry, because they are the
   one who manages those entries.
2. **An explicit entry for the document** → overrides inheritance. Without this it would be
   impossible to revoke access to ONE document without removing somebody from the whole workspace.
3. **Workspace membership** → inherited by its documents.
4. **`link` visibility** → `editor` for every logged-in user.
5. In all other cases → **no access**.

### Authorization must see the OPERATION TYPE

This is the non-obvious part of the decision. The `commenter` role may read and comment but not
change the text — and comments (CP14) travel **the same CRDT operation channel** as editing. The
distinction between "may write" and "may comment" does not exist at the level of a STOMP
destination and has to descend to the operation type.

The "role → allowed types" mapping is therefore in one place
(`DocumentAccessInterceptor.allowedToSend`), so that adding an operation type in CP13 and CP14 is
an entry in a table rather than a hunt for conditions scattered across the code.

### A refusal returns 404, not 403

The answer "this document exists but you have no access to it" is itself a leak: it lets you probe
which identifiers are real. From the point of view of somebody without permission the document
should simply not exist — the same as on the WebSocket side, where the subscription simply does not
work.

---

## Consequences

### Positive
- One rule, two entry points. A third route to the data cannot be added without somebody noticing
  that it lacks a check — because both existing ones call the same method.
- Defaulting visibility to `link` means the migration breaks nothing, while the mechanism is real.
- The `commenter` role forced a design that will carry CP14 without an architecture change.

### Negative

- **Permission cache invalidation is LOCAL to an instance.** With multiple instances the others
  will see a revocation only once the entry expires, that is, within 5 seconds. The full solution
  is broadcasting the invalidation over the Redis channel — the same one the operations use.
  **Not done, listed in the README.**
- **The permission cache is a trade-off, not an optimisation without a downside.** The check happens
  on every frame, so a database query each time would be a query per keystroke. Five seconds is
  simultaneously the upper bound on the delay of a revocation — and it is the only guarantee this
  mechanism gives.
- **The default `link` visibility is permissive.** A document created without deliberately changing
  the settings is accessible to every logged-in user. This matches the behaviour before CP12 and
  how most link-shared editors work — but somebody assuming "private until I share it" will be
  surprised. A `private` default would be safer and would require a sharing interface, which CP12
  does not build.
- **There is no interface for managing access.** `PermissionService` exposes `grant`, `revoke` and
  `setVisibility`, but there is no screen from which to call them. The model works and is tested;
  it lacks the last step to being a product feature.
- **The token is still checked once, at `CONNECT`.** Revoking the token itself mid-session does not
  tear down the connection — what changed is that *permissions* are checked on every frame, while
  *identity* still comes from a one-off check. Listed in the README.

### Verified in CP14 — and the answer is "no"

The question was whether the "role → operation types" mapping would carry comments with a single
entry. **It did not.** A comment anchor is a CRDT mark of exactly the same shape as bold
([ADR-0011](0011-komentarze.md)) — the same operation, the same address, the same channel. What
distinguishes them is the **value of the `type` field**, which means the distinction "a commenter
may, an editor may, a reader may not" requires looking inside the **frame body**, not at its header.

The interceptor does this today only for the `commenter` role and only when that role occurs — but
it is an authorization layer coupled to a data format, which is exactly the construction this ADR
wanted to avoid. A change to the operation format could silently change permissions.

The right solution is now known precisely: **the operation type in a STOMP header**, set by the
client and verified against the body only after authorization. A header is a cheap metadata channel
and exists for exactly this. Not done — it requires changing the wire format on both sides, and the
cost does not balance a single case. Recorded as a debt in the README.

What this answer confirms: **the `commenter` role was not superfluous.** Without it nobody would
have discovered that authorization has to descend to the operation type — and discovering that at
the first external audit is far worse than at your own checkpoint.

---

## Verified in the plan audit — one route was left open

The "Decision" section of this ADR says permissions are enforced **in both layers from one rule**.
Throughout CP12–CP15 that was not true: `HistoryController` checked nothing.

Three endpoints were open:

| Endpoint | What it handed out |
|---|---|
| `GET /history` | the timeline of any document |
| `GET /history/at` | the **full content** of any document at any moment |
| `POST /history/restore` | **overwriting** any document by rolling it back to an old version |

The last is the worst, and not only because it is a write: it was the **only route in the project
by which a role without write permission could change text** — and not by a character, but by
rolling back the whole document. The per-operation-type authorization this ADR designed with
`commenter` in mind did not cover that path at all, because it does not go through STOMP.

What is worth recording here, because it is not a mistake in the code but **in the shape of the
solution**: plan v2 §4.3 named this route explicitly ("authorization only in the transport is
authorization of one path") and specified a test `historyRestoreChecksWriteAccess`. Writing the
threat down in the plan was not enough. What would have been enough is **a solution in which
skipping the check requires a conscious decision** — one rule enforced by a filter on
`/api/documents/{id}/**` instead of a check repeated in every controller.

Fixed (a check in the controller, `PermissionIT.historyEndpointsCheckAccess`), but fixed the weaker
way: a new controller will still start without authorization and say nothing about it. The proper
fix is in the README, in the section on limits.

**Since CP16 it is no longer only in the README.** The construction described above —
`@RequiresDocumentAccess` / `@RequiresAuthentication` / `@PublicEndpoint`, a guard that refuses to
serve an endpoint declaring nothing, and an architecture test that fails the build — is
[ADR-0012](0012-autoryzacja-strukturalna.md). Writing this one shows why it was needed: a fourth
hole of the same class was found while planning it.
