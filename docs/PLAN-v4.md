# Plan v4 — from a proof into a product that shows its proof

> Status: in progress. Checkpoints CP21–CP30.
> Previous plans: [v1](IMPLEMENTATION_PLAN.md) (CP1–CP9), [v2](PLAN-v2.md) (CP10–CP15),
> [v3](PLAN-v3.md) (CP16–CP20).

## Thesis — what v4 differs from v3 in

v3 was a plan about **vigilance**: it closed items whose justification was already measured and
written down, and it turned three of them from "we remember to do this" into "the build refuses
otherwise". It added no features on purpose.

v4 adds features, and the selection rule is the one from `CLAUDE.md`: **every item has to bring
its own engineering problem**, not work around the core. The list below was produced by walking
the code rather than by asking what a text editor usually has — which is why it opens with a
finding rather than with a feature.

**The finding that set the order.** The role model is implemented, tested, and enforced
structurally by CP16 — and it is unreachable from the running product:

| Evidence | Where |
|---|---|
| `grant`, `revoke`, `membersOf` are called **only from tests** | `PermissionService.java:208,216,238` vs `PermissionIT`, `CommentIT` |
| `Capability.MANAGE_ACCESS` is declared, documented, and used by **no endpoint** | `Capability.java:32` |
| Default visibility is `link`, and `link` resolves to `Role.EDITOR` for **every logged-in user** | `V3__permissions.sql:21`, `PermissionService.java:173` |
| No endpoint adds a member to a document or to a workspace | `DocumentRestController.java` |

So the product has two roles: owner, and "anyone who logged in". `viewer` and `commenter` exist
in tests only — and they carry half of the project's authorization story. Plan v3 §9 rejected
"an access management screen" as UI work with no engineering content. The screen is exactly
that. The **API and the role-change event are not**, and that is CP21.

---

## 0. What this plan does not touch

Work in progress by somebody else on the same branch, deliberately left alone: the scheduler
pool, the timeline query, snapshot retention, in-memory bounds (`PermissionService.cache`,
`DocumentRegistry`), the CP20 measurement geometry, hosting configuration, the RGA interleaving
measurement, **cluster-wide permission invalidation**, TLA+, the conformance corpus generated
from property-test seeds, the GC window, the language migration, README condensation, and the
`App.tsx` split.

Two consequences for what follows:

1. **CP21 stops at the instance boundary on purpose.** A role change is published to the clients
   of the instance that made it. Cross-instance delivery belongs to the invalidation broadcast
   being built separately; this plan must not build a second, competing channel for the same
   fact.
2. **CP26 (presence across the cluster) touches `ClusterBus`.** It adds a channel, it does not
   change the existing one.

---

## 1. Order and why exactly this one

Ordered by return over cost, not by size.

| CP | What | Cost | Why here |
|---|---|---|---|
| **CP21** | Access API + role-change event | 1 d | Without it three closed checkpoints cannot be seen at all, and the default `link` means "every logged-in visitor may edit" — which stops being a detail the moment the instance is public |
| **CP22** | History that knows the document is formatted + version diff by identity | 2–3 d | Repairs a function that quietly degraded when CP13 landed, and adds the one thing that is impossible without a CRDT |
| **CP23** | Suggestion mode | 3–4 d | The third application of the mark layer, and the first reason for the `commenter` role to exist |
| **CP24** | CRDT snapshot to the client | 1.5–2 d | The largest remaining product debt by the previous plan's own account |
| **CP25** | Mark-aware undo that survives a reload | 0.5–1 d | The cheapest item that closes a limit already written into the README |
| **CP26** | Presence across the cluster | 1 d | A debt named in the javadoc; also the only place where best-effort has to be shown to the user |
| **CP27** | Comments that work offline | 1 d | Closes ADR-0011 empirically instead of declaratively |
| **CP28** | Large paste, resync budget in bytes | 0.5–1 d | Turns an open item into a test scenario |
| **CP29** | Search inside document content | 1 d | Most product value per hour, least narrative value |
| **CP30** | Block structure with a move operation | 5–7 d | Last because it is a plan in itself; first if v4 is meant to change the core |

---

## 2. CP21 — Access is reachable from the product (1 day)

### 2.1 Problem

Beyond the table at the top: `POST /api/documents` leaves visibility at the schema default,
so **every document created through the interface is editable by everyone who logs in** — and
logging in is a display name with no password. On a laptop that is a convenience. On the public
instance that plan v3 §0.1 wants, it is the whole access-control story being false in the one
place a visitor can observe.

### 2.2 Decision

1. `GET /api/documents/{id}/members` — the member list, the owner, and the visibility.
2. `PUT /api/documents/{id}/members/{userId}` — grant a role.
3. `DELETE /api/documents/{id}/members/{userId}` — revoke.
4. `PUT /api/documents/{id}/visibility` — `private` or `link`.
5. **New documents are `private`.** The creator is the owner; everybody else needs a grant or a
   deliberate switch to `link`.

All four require `Capability.MANAGE_ACCESS` — including the read, because the member list is a
list of who has access to something, and handing that to a viewer is a small leak with no
purpose. This is the first endpoint in the project to require that capability at all.

**Two refusals worth writing into the code**, because both are ways an access API becomes a
hole:

- The API cannot grant `owner`. Ownership transfer is a different operation with a different
  consequence (the previous owner may lose the ability to undo it), and folding it into
  "set role" is how that happens by accident.
- The owner cannot revoke themselves. A document with no owner cannot be secured by anybody —
  the exact state CP16 fixed in `POST /documents`, reachable again through the front door.

### 2.3 The role-change event

The README lists under *open, unresolved*: the role arrives once, with the initial state, so
raising somebody's permissions mid-session does nothing until they reload. Revocation is
different — the server rejects the operation anyway — so this is an inconvenience, not a hole.
It is also the thing that makes the whole feature feel broken: the owner grants access, the
other person's editor stays read-only, and nothing says why.

After a grant or a revoke the server publishes `ACCESS_CHANGED` on the document topic. Clients
re-fetch their own role and re-render. Instance-local, for the reason in §0.

### 2.4 Tests

- `AccessIT` (new): grant makes a stranger an editor; revoke takes it away; a non-owner cannot
  grant; the API refuses to grant `owner`; the owner cannot revoke themselves; a new document is
  private, so a stranger gets 404 and not somebody else's text.
- `PermissionIT` keeps passing unchanged — the rule it checks did not move.
- The e2e helper stops creating documents **without a token** (it has been doing that since
  CP16, which means those scenarios have been failing since CP16) and shares the document
  explicitly, which is also the first e2e coverage of the new endpoints.

---

## 3. CP22 — History stops treating the document as a string (2–3 days)

### 3.1 Problem

`HistoryService.textAt` returns `document.text()` (:117) and `restoreOperations` computes a
common prefix and suffix over plain text (:149). Both were right in CP5 and neither was revisited
when CP13 added marks. Today:

- **restoring a version does not restore formatting** — the text goes back, the appearance does
  not;
- the preview shows no formatting, so a version whose only change was formatting looks identical
  to the one before it;
- the timeline counts mark operations into `operationCount` and describes a bolding as
  "3 operations, +0 −0" (:229).

### 3.2 Decision — restore covers marks, and the diff is computed from identity

Restore stays what ADR-0003 pt 6 made it: **a new change, never a truncation of the log**. It
gains a second half — after the text matches the target, compare the target's spans with the
current ones and emit the `AddMark`/`RemoveMark` operations that make them equal.

The diff is the interesting half. Every character carries `(replicaId, lamport)`, and any two
states of one document order their shared characters identically, because both are the same
append-only log stopped at different points. So the difference between two versions is a
**two-pointer merge over two ordered identifier sequences** — linear, exact, and free of the
heuristics a text diff needs. It also answers a question a text diff cannot: text deleted and
retyped identically is a change here, and `git diff` would show nothing.

### 3.3 Tests

- Property: for random edit scripts, `restore(v)` reproduces the text **and the marks** of `v`.
- Property: `diff(A→B)` applied to `A` yields exactly `B`'s visible sequence.
- A test with teeth for the claim above: delete a word and retype it identically; the text diff
  finds nothing, the identity diff finds it.

---

## 4. CP23 — Suggestion mode (3–4 days)

A `commenter` may talk about the text and may not change it. A suggestion is a proposed change
recorded as marks — `suggest-insert` and `suggest-delete` — over real characters, accepted by
removing the mark and rejected by applying the inverse.

Three problems, none of them UI:

1. **Authorization by operation type.** A commenter may insert only characters covered by their
   own suggestion. `DocumentAccessInterceptor:195` already parses the frame body to tell a
   comment mark from a formatting mark, and the README calls that a debt ("operation type in a
   STOMP header, not in the body"). A second condition in the same parser turns the debt into a
   requirement.
2. **The orphaned suggestion** — somebody deletes the anchored text concurrently. The comment
   layer solved this once; the mechanism moves rather than being invented.
3. **Idempotent acceptance** — two people accepting the same suggestion at the same moment.

What it proves: the character layer still does not change by a single field, this time for a
feature nobody had in mind when that layer was designed.

---

## 5. CP24 — The client receives a snapshot, not the whole log (1.5–2 days)

The server has had snapshots since CP3; the client still replays the entire operation log when
opening a document. The work is a snapshot decoder in TypeScript, which makes this the first
honest measurement of the two-implementation debt — CP18 cost nothing on the client and the
README concluded from that single point that the debt is proportional to how much of the
algorithm the client implements. This tests that conclusion from the other side.

Measured: time to first render against log length, before and after.

---

## 6. CP25 — Undo that knows about marks and survives a reload (0.5–1 day)

`Ctrl+Z` after `Ctrl+B` undoes the previous **text** change, because mark operations never enter
the stack, and the stack dies with the tab.

The problem is not "push mark operations too". The inverse of `AddMark` is not "remove the
mark": there may have been another author's mark of the same type underneath, which was losing
the `MarkSet` resolution and would now win. Undo has to restore a **state**, not perform the
opposite gesture — the same distinction `UndoManager` already respects for text when it skips an
entry whose anchor is gone instead of guessing.

Durability is the cheap half: the same IndexedDB the offline buffer already uses.

---

## 7. CP26 — Presence across the cluster (1 day)

`PresenceService` keeps an instance-local map, so a client connected to another instance does
not appear in the list. A Redis channel plus entry expiry fixes it.

The engineering content is in the failure mode, not in the fan-out: Redis is a **deliberately
unreliable** path here (ADR-0004), so after an outage the list of participants must say "this
may be incomplete" rather than present a partial list as complete. It is the only place in this
system where best-effort has to be shown to the user instead of being hidden behind convergence.

---

## 8. CP27 — Comments offline (1 day)

The offline buffer covers CRDT operations, not REST, so a comment written without a connection
disappears. An outbox with an idempotency key, on the IndexedDB that is already there.

The conclusion is worth more than the code: a comment message is **immutable**, so it does not
need merging — it needs exactly-once. That closes ADR-0011's claim that comments are not a CRDT
with evidence instead of with a design intention.

---

## 9. CP28 — Large paste, and the resync budget in bytes (0.5–1 day)

`SYNC_CHUNK = 300` counts operations and assumes ~100 B each. A 50 KB paste is **one**
operation. The broadcast path moved to a byte budget in CP10; the resync path did not.

Framed as a feature rather than as a debt, because pasting is a gesture every user performs in
the first minute and no test performs at all: paste a chapter, disconnect, come back.

---

## 10. CP29 — Search inside content (1 day)

The GIN index covers `title` and `preview_text` — the first sentence of a document. Searching
the actual content needs a `tsvector` kept fresh without re-reading documents; the natural place
is the snapshot path, which already materializes the text under the document lock.

The trade-off is real and has one right answer here: the index may lag, the write path may not
get slower.

---

## 11. CP30 — Block structure with a move operation (5–7 days)

The document is a flat character sequence, so "make this a heading" is not expressible.
(`Block` in `crdt-core` is a run-length block of characters, not a paragraph — the names collide
and the ADR will have to say so.)

The hard part is **concurrent movement of the same block**: naive designs either duplicate it or
lose it (Kleppmann, *Moving Elements in List CRDTs*, 2020). It is the only remaining item that
would change the core.

A cheap variant exists — block-level marks spanning a line, no moves, no tree — and if it is
taken, the plan says plainly what it does not cover.

---

## 12. Estimate

| CP | Estimate |
|---|---|
| CP21 | 1 d |
| CP22 | 2–3 d |
| CP23 | 3–4 d |
| CP24 | 1.5–2 d |
| CP25 | 0.5–1 d |
| CP26 | 1 d |
| CP27 | 1 d |
| CP28 | 0.5–1 d |
| CP29 | 1 d |
| CP30 | 5–7 d |
| **Total** | **17–23 d** |

---

## 13. Risks

| Risk | Probability | Countermeasure |
|---|---|---|
| **Making new documents private breaks integration tests that rely on the `link` default** | High | Expected: they are run and fixed inside CP21, not left for later. The tests that break are the ones stating the old default, which is the point |
| **e2e cannot be run in this environment** (port 8080 is taken by a running instance, and the suite needs a live frontend) | Certain | e2e changes are written but marked as unrun in the final report. They have been failing since CP16 anyway — `freshDocument` creates a document with no token, which CP16 turned into a 401 |
| **Two agents editing one working tree** | Certain | Commits are scoped to explicit paths, never `git commit -a`; files with in-flight work elsewhere are not touched |
| **CP23 grows past its estimate** — it is the widest change in the plan | Medium | Close it on the mark layer plus accept/reject with no UI polish; the wire-format move is the part that must not be skipped |
| **CP30 does not fit** | High | It is last on purpose. The cheap variant is a legitimate close, an unfinished tree is not |

---

## 14. What this plan deliberately does not cover

- **Notifications, mentions, reactions, PDF export, templates, a mobile build.** None of them
  bring a problem this project has not already solved; they are features where code gets written
  and no decision gets made. PLAN-v2 §10 rejected them with the same argument.
- **Export as a separate item.** Serializing text with marks to Markdown is an hour of work and
  zero content; as part of CP28 it has a reason to exist.
- **An AI assistant writing into the document.** If ever, then only as a producer of **suggestion
  mode** operations (CP23) — never as a direct mutation of the text. A model writing directly is
  one more replica with no role, no trace in the history and no way to reject its work in one
  gesture, which is precisely what the permission layer exists to prevent.

---

## 15. Final audit — after CP30

To be filled in when the plan closes: what was not done, what was done differently and why, and
what the measurements did not confirm. Empty until then, on purpose — the v3 audit is worth what
it is because it was written against the code afterwards, not predicted.
