# Plan v2 — from artifact to product

> **Status: the plan is closed in full (CP10–CP15).** The successor to
> [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md), which covers checkpoints CP1–CP9 (also
> closed).
>
> | Checkpoint | State | Notes |
> |---|---|---|
> | CP10 — Foundations | **closed** | The open question from CP8 is settled — see below |
> | CP11 — Multi-document | **closed** | Cluster filtering, a memory limit, schema V2, the catalog, the list with search and a bin |
> | CP12 — Permissions | **closed** | Closed the IDOR that CP11 opened — both routes, WS and REST |
> | CP13 — Rich text | **closed** | Peritext in Java and TS, conformance vectors, GC fixed |
> | CP14 — Comments | **closed** | The anchor is a CP13 mark — **zero lines in the CRDT layer** |
> | CP15 — Measurement + documentation | **closed** | Two measurement bugs, the GC concern confirmed with a number, a second demo |
>
> **Final audit of the plan (after CP15).** Going through CP10–CP15 point by point found one thing
> **not done and dangerous**, plus several done differently from what the plan assumed — the
> reckoning is in section [11](#11-final-audit--what-was-not-done).
>
> **Three things this plan did not foresee, which turned out to matter most.**
>
> 1. **Rate limiting lost operations silently** (CP12 introduced it, CP15 found it). A refusal on
>    rate behaved exactly like a refusal on permissions — with no response. Those two cases are
>    asymmetric: without permission the client should NOT learn that the document exists; over the
>    rate limit it MUST learn, because its operation is waiting in the buffer and without a signal
>    nobody retries it. 115 operations were lost, the document stopped converging, and nothing
>    reported it.
> 2. **The same mistake had a third instance** — the user's role did not reach the client either.
>    A reader typed, saw their own text, and the server rejected every operation. Fixed together
>    with the first.
> 3. **The GC concern from point 5.5 was confirmed more strongly than it assumed.** The plan
>    predicted "two tombstones per mark"; the measurement showed "a whole run-length block per
>    mark" — on a document written in one continuous run, GC reclaims **nothing** after formatting
>    is added. The numbers are in the README and in the tombstone-collection design.
>
> **The most important result of CP10, which the plan did not predict.** Point 2.1 assumed that the
> lack of repeatable convergence above ~10 clients was a problem in the system, and that coalescing
> broadcasts would fix it. Coalescing did help (2.7× faster convergence at 50 clients), but **the
> cause of the non-convergence was the measuring rig**: the test client had the default 8 KiB
> JSR-356 buffer, which let broadcasts through (~70 B per frame) and stopped resynchronisation
> responses (300 operations ≈ 15 KB) — that is, the only mechanism capable of repairing a lost
> operation. The details are in the README and the transport decision.
>
> The plan's hypothesis about the server's send buffer overflowing **was not confirmed**: the
> `ws_sessions_closed_total{reason}` metric recorded not one such close at 50 clients.

Version 1 was a **narrow engineering artifact**: one document, one problem (convergence of
distributed data), maximum depth. Product scope was deliberately rejected in
the original UI scope, and the README closes with a "what I would do differently in
production" section listing exactly the four gaps this plan now addresses.

Version 2 changes the positioning: **a rich text editor with comments, many documents and a
permission model.** That is a different project from v1 — not an extension but a change of thesis.

The rules for keeping this document are carried over from v1 unchanged: if a decision from the plan
turns out badly in practice, we fix it **here** and commit the plan change together with the
reason. The history of iteration is part of the artifact.

---

## 0. The change of positioning — before CP10 begins

The repository currently **explicitly advises against** the scope this plan introduces. Without
tidying that up, the documentation will contradict itself, and that costs more than having no
documentation — the reader stops trusting the whole.

| File | What it says now | What to do |
|---|---|---|
| `CLAUDE.md`, "What to avoid" | "Excessive scope (rich text formatting, comments, a multi-document workspace) at the cost of depth in the core problem" | Rewrite: the scope is extended deliberately after CP9 closed, on condition that the core's depth is maintained |
| the original UI scope | The mockup's functional scope rejected | Status → **Superseded by the scope extension**. Do not delete — the decision was right for v1 |
| README, "What I would do differently" | Lists these features as deliberate boundaries | Split into "done in v2" and "still out of scope" |

### the scope extension — extending the scope into a product

The first thing to write, before any code. The same layout as the other ADRs.

**The context to describe:** v1 achieved its goal (the CRDT core proven by property tests,
convergence measured, the boundaries named). The narrow scope stopped being an advantage the moment
the core was finished — further depth in the same place has a diminishing return, and the four
rejected features are the natural direction in which that core can be *used* rather than merely
shown.

**Options considered:** (A) stay with v1 and deepen the core — Fugue, CRDT-aware undo; (B) extend
into a product; (C) extend selectively — only multi-document and permissions, without rich text.

**Negative consequences — to be written down honestly, because they are real:**
- **Dilution.** The project stops having a one-sentence description. A reviewer with three minutes
  may come away with "another Google Docs clone" instead of "somebody really understands CRDTs".
  Mitigation: the README still has to open with the core rather than a feature list.
- **The double cost of every algorithm change** (Java + TypeScript) now applies to formatting marks
  as well. The debt described in the README grows in proportion to the new scope.
- **Tombstone GC becomes even less effective** — see CP13, the section on anchors. This is the most
  serious technical consequence and may force a redesign of the block representation that
  the tombstone-collection design deferred.
- **Every number in the README becomes stale.** Rich text changes the traffic profile. Leaving the
  old numbers would be dishonest — hence CP15 is mandatory rather than optional.

**To be verified in CP15:** whether, after the extension, the project still reads as one coherent
whole rather than two things glued together.

---

## 1. The order, and why this one

The dependencies are not arbitrary — three of them are hard:

```
CP10  Foundations ──────────┐
      (propagation, traffic,│  rich text and comments INCREASE traffic.
       runnability)         │  Building them on an undiagnosed propagation
                            │  problem above 10 clients means debugging
                            │  two things at once.
                            ▼
CP11  Multi-document ───────┐
      (the container)       │  permissions without many documents have
                            │  nothing to protect — one shared document is
                            │  an access model of "everyone, everything".
                            ▼
CP12  Permissions ──────────┐
      (authorization per    │  authorization has to cover operation TYPES.
       STOMP destination)   │  Adding formatting marks after authorization is
                            │  built = one place to extend. The other way
                            │  round = rework.
                            ▼
CP13  Rich text ────────────┐
      (the algorithmic core)│  a comment is an anchored range.
                            │  The anchor layer is built in CP13 —
                            │  comments merely REUSE it.
                            ▼
CP14  Comments
                            │
                            ▼
CP15  Re-measurement + documentation
```

The key observation: **comments are cheap, but only after rich text.** Done earlier they would need
their own anchor layer, which CP13 would replace anyway. That is the only reason comments — the
simpler feature from a product point of view — come later in the plan than rich text.

---

## 2. CP10 — Foundations (2–3 days)

This is not "tidying up before the real work". Three of these things are a **precondition** for
doing CP11–CP14 sensibly.

### 2.1 Close the open question about propagation

The README ends its performance section with "I do not know whether the cause lies in the system or
in the measuring rig". Rich text and comments will **multiply the traffic** — starting from an
undiagnosed problem means every future regression will be indistinguishable from the existing one.

The hypothesis to check first: **the broadcast path is not batched, unlike the write path.**

`DocumentService.java:150` — `onFastPath.accept(documentId, List.of(op))`. One frame per keystroke,
sent to N subscribers. The write (`flush()`) and the acknowledgements are batched every 50 ms; the
broadcast is not.

The arithmetic for the harness scenario (50 clients × 40 operations):

| Quantity | Value |
|---|---:|
| Frames on the `/ops` topic | 2000 × 50 = **100 000** |
| Send queue per client | ~2000 × ~200 B ≈ **400 KB** |
| `send-buffer-size-bytes` (`application.yml:55`) | **512 KiB** |

Once the buffer overflows the session is closed — which gives exactly the symptom from the README:
the server holds the complete set of operations, some clients fall permanently behind, and there is
no error in the log.

The steps:

1. **Measure before fixing.** A counter of sessions closed by buffer overflow in `DocumentMetrics`
   plus a panel on the dashboard. If it fires at 25 clients and stays silent at 10, the question is
   answered with a number. That is a result in itself, regardless of whether the hypothesis holds.
2. **Coalesce broadcasts into a ~10–15 ms window**, in the same shape as `flushAll()`. This
   requires updating the transport decision and
   the cluster design, because the cluster design says "broadcast immediately" and
   that will stop being literally true. The cost: ≤15 ms of latency against a measured p50 of
   22–553 ms.
3. **Separate the clients from the server** in the harness — the README itself names this as the
   precondition for finally settling "the system or the rig".

**Closing criterion:** the 25- and 50-client scenarios converge repeatably across five consecutive
runs, or a metric and a description exist explaining why not.

### 2.2 Presence — O(N²) with no throttling

`Editor.tsx:82` — `reportCursor` fires on every `selectionSet || docChanged`, that is, on every key
and every arrow. `PresenceService.java:79` responds by broadcasting **the full participant list to
everyone** in the document. No throttling, no delta.

10 people typing at 5 characters/s = 50 messages/s to the server → 500 sends/s, each with a full
JSON array.

On top of that: `LoadHarnessIT` **generates no presence traffic at all** (`TestStompClient` does
not send it). The numbers in the README therefore describe a lighter system than the one a browser
uses.

- Throttle on the client: ~50–100 ms, trailing edge.
- Broadcast the changed participant rather than the whole list.
- Add presence traffic to the harness — otherwise the CP15 numbers repeat the same mistake.

After CP12 a second dimension appears here: presence has to respect permissions (a viewer sees
cursors, but whether others see the viewer's cursor is a product decision to be taken in CP12).

### 2.3 Runnable with one command

There is no `Dockerfile`, and `docker-compose.yml` promises in its header "To start the full stack:
`docker compose up -d`" while containing only postgres, redis and the observability profile.

With four new features to demonstrate this stops being cosmetic — without it nobody will see those
features.

- A multi-stage `Dockerfile` for `app` (a layered jar), a `Dockerfile` + nginx for `frontend`.
- The `app` and `frontend` services in compose; fixing the misleading header.
- Eventually: a hosted instance and a link above the GIF in the README.

### 2.4 Debt that contradicts the documentation

Small, but in a repository this careful they stand out — and all of them are a contradiction
between the code and its own description.

| Place | Problem |
|---|---|
| `application.yml:67` → `CollabEditProperties:52` | `snapshot-after-idle-ms` is declared and justified by a comment, and **read nowhere**. `maybeSnapshot()` checks only the operation-count threshold |
| `application.yml:63` | `op-batch-max-size: 100` — the same. `drainUnwritten()` takes the whole buffer regardless of size; the declared limit does not exist |
| `application.yml:13`, `:65`, `OperationStore.java:52` | Dead "to be verified in CP8" notes — CP8 is closed. The rule in `docs/adr/README.md` says to come back after a checkpoint |
| `.github/workflows/nightly.yml` | The chaos job is `if: false` with `echo "To be enabled in CP8"`, even though `ClusterChaosIT` exists and the README cites it |
| `.gitignore` | The rule targets `demo/recordings/`, while the recordings live in `frontend/demo-recordings/` — 1.5 MB of `.webm` tracked in git |
| `TokenService.java:78` | The payload is assembled with `String.join("|")`, `verify` does `split("\\|")` and rejects when `parts.length != 4`. `displayName` comes from the user (`DocumentRestController.java:66`). A name containing `\|` → a token is issued but CONNECT rejects it: login "works", the application does not. It fails safe, but it is a real bug. There is also no length limit on the name |
| `App.tsx` | 732 lines, 10 components. The rest of the repository keeps exemplary file discipline; CP11 will add routing and the document list here anyway, so do the split **before**, not after |

Fully qualified package names instead of imports: `PresenceService.java:37`,
`DocumentService.java:263`, `DocumentStompController.java:214`.

---

## 3. CP11 — Multi-document (2–3 days)

### 3.1 The data model

The `V2__workspaces.sql` migration:

```
workspaces          id, name, owner_id, created_at
documents           + workspace_id (FK), + deleted_at (soft delete),
                    + preview_text (materialised at snapshot time)
```

`preview_text` is a **cache, not a source of truth** — exactly like the snapshots in
the operation-log decision. The document list must not load CRDT replicas into memory
merely to show the first three lines; that is the same trap the existing test "deleting every
snapshot changes nothing" guards against.

A soft delete rather than `DELETE`, because `operations` has `ON DELETE CASCADE` — a hard delete of
a document would erase the operation log, that is, the source of truth. That contradicts the
principle in `V1__initial_schema.sql` ("an append-only table"). A deleted document should be
unreachable, not irreversibly destroyed.

### 3.2 Two real scaling problems that multi-document exposes

**(a) Every instance deserialises the traffic of every document.**

`RedisConfig` subscribes to the pattern `collabedit:doc:*:ops` and justifies it by saying that
filtering out documents not held in memory "is trivial". For the check itself — yes. But
`ClusterBus.onMessage:112` calls `readTree(message.getBody())` **before** any filter; the
`registry.isLoaded()` check happens only in `DocumentService.onClusterMessage:240`. With one
document that did not matter. With a thousand, every instance parses the JSON of the whole
cluster's traffic.

The fix is cheap and requires no change to the subscription model: the document identifier **is
already in the channel name**. It is enough to extract it from `message.getChannel()` and check
`isLoaded` before parsing. The justification in `RedisConfig` then becomes true.

**(b) `flushAll()` every 50 ms across every loaded document.**

`DocumentService.java:167` iterates `registry.loaded()` and calls `drainUnwritten()` for each,
which takes the document's lock. With 500 loaded documents that is 10 000 lock acquisitions per
second, mostly with no work to do.

The fix: the registry maintains a set of "dirty" documents (those with unwritten operations), and
`flushAll` iterates over that instead of over everything. The same pattern applies to
`antiEntropySweep`.

### 3.3 Memory management

`idle-document-eviction-ms` already exists, but there is no **upper bound** on the number of
replicas in memory. With many documents this is needed:

- An LRU limit on `DocumentRegistry`, configurable and with the value justified.
- Metrics: the number of loaded replicas, the estimated memory per replica, evictions/s.
- Eviction has to force a `flush` before releasing — otherwise an unwritten batch is lost.

### 3.4 The remaining scope

REST: listing, creation, renaming, soft delete, restore, search (Postgres FTS over
`preview_text`). Frontend: routing, the document list, creation — after the `App.tsx` split from
CP10.

### 3.5 Tests

| Test | The failure mode it catches |
|---|---|
| `evictionFlushesUnwrittenOperations` | Eviction under memory pressure loses a batch — data loss visible only under load |
| `softDeletedDocumentKeepsOperationLog` | `ON DELETE CASCADE` erases the source of truth; irreversible |
| `previewTextIsOnlyACache` | The preview becomes a source of truth — the exact counterpart of the existing snapshot test |
| `clusterMessageForUnloadedDocumentIsNotParsed` | A regression of the filtering from 3.2(a); functionally invisible, visible only in a CPU profile |
| `flushTouchesOnlyDirtyDocuments` | A regression from 3.2(b) — likewise |

---

## 4. CP12 — Permissions (2.5–3.5 days)

### 4.1 This is primarily security work, not UI work

The current state: `StompConnectInterceptor` handles **only** `CONNECT` and `DISCONNECT`. There is
no authorization on `SUBSCRIBE` or `SEND` at all. The `document_members` table has existed in the
schema since V1 but **is enforced nowhere**.

Today that is harmless — there is one shared document and an access model of "everyone,
everything". **The moment CP11 lands it becomes a vulnerability:** any authenticated user can
subscribe to `/topic/doc/{any-id}/ops` and read somebody else's document, or send an operation to
`/app/doc/{any-id}/op`.

This is the single most important element of this checkpoint and the reason CP12 cannot be pushed
past CP13.

### 4.2 The role model

| Role | Text | Formatting | Comments | Sharing |
|---|:---:|:---:|:---:|:---:|
| `viewer` | — | — | read | — |
| `commenter` | — | — | write | — |
| `editor` | ✓ | ✓ | ✓ | — |
| `owner` | ✓ | ✓ | ✓ | ✓ |

The `V3__permissions.sql` migration: `document_members.role`, `workspace_members` (a role inherited
by the documents, overridable per document), `invitations`.

**A decision to record in the permission model:** inheritance from the workspace versus per-document
permissions only. Inheritance is more convenient from a product point of view and harder to verify
— an access check stops being one query, and cache invalidation has to cover every document in the
workspace.

### 4.3 Authorization per STOMP destination

Extending the existing `ChannelInterceptor`:

- `SUBSCRIBE` → extract the `documentId` from the destination, check the read permission, reject
  the frame if absent.
- `SEND` → check the permission **appropriate to the operation type**, not merely to the document.
  A `commenter` may send `AddMark(type=comment)` but not `Insert`. That means authorization has to
  look inside the operation — and after CP13 the list of types grows, so the mapping "role →
  permitted operation types" is to be **one table in one place**, not a series of `if`s scattered
  through the interceptor.
- A permission cache per session, otherwise every operation is a database query.

**A second line of defence in `DocumentService.submit`.** The STOMP layer is not the only route to
a write — there is also restoring a version from history (`HistoryService`). Authorization only in
the transport is authorization of one path.

### 4.4 Revocation mid-session

The README lists this as a known limitation: the token is checked once, at `CONNECT`. With
permissions this becomes a real problem — revoking access should take effect immediately, not after
12 hours (`LIFETIME` in `TokenService`).

A `collabedit:acl:{userId}` channel in Redis → the instance invalidates its cache and **actively
tears down that user's subscriptions** on documents they have lost access to. The client receives
an error and takes its existing handling path (`onError` in `connection.ts`).

### 4.5 Rate limiting

The README lists its absence as a boundary. It naturally belongs in the same interceptor: a token
bucket per session, with separate limits for operations and for presence (very different profiles).
The values justified numerically rather than plucked from the air, per the rule in `CLAUDE.md`.

### 4.6 Tests

| Test | The failure mode it catches |
|---|---|
| `subscribeToForeignDocumentIsRejected` | IDOR — reading somebody else's document by subscribing to a topic. **The most important test of this checkpoint** |
| `commenterCannotInsertText` | Authorization per document instead of per operation type |
| `revokedAccessTerminatesLiveSubscription` | Revoking access only takes effect once the token expires |
| `historyRestoreChecksWriteAccess` | Authorization only in the transport; bypassed via the second write path |
| `workspaceRoleIsOverriddenByDocumentRole` | Inheritance overriding an explicit revocation |
| `rateLimitClosesFloodingSession` | One client saturating an instance |

---

## 5. CP13 — Rich text (5–7 days) — **the algorithmic core of v2**

The largest and hardest checkpoint. The counterpart of CP2 in v1, but harder: in two languages, on
an existing wire format, and with consequences for GC.

### 5.1 Why this cannot be done "per character"

A trap that has to be named in the mark layer, because it is the most common wrong approach:
**formatting as an attribute of a character does not work.** If Ania bolds a range while Bartek
concurrently inserts text into the middle of it, the inserted text has to be bold — and with
per-character attributes there is nobody to make it bold, because at the moment of insertion Ania's
operation had not arrived.

Storing ranges as offsets does not work either. The reason is identical to the one for cursors in
the editor-layer decision: an offset stops pointing at the same place after somebody
else's edit. The project solved this once — by anchoring a cursor to a **CRDT character identifier**
— and the same solution scales to formatting.

### 5.2 The design (Peritext) — to be verified in CP13

```
Anchor = (Id charId, Side side)            Side ∈ {BEFORE, AFTER}
Mark   = (Id opId, String type, String value, Anchor start, Anchor end)

Op.AddMark(Id id, String type, String value, Anchor start, Anchor end)
Op.RemoveMark(Id id, String type, Anchor start, Anchor end)
```

- **`Side` matters and is not cosmetic.** A mark ending "after character X" and one starting
  "before character Y" behave differently when somebody inserts text between X and Y — one covers
  it, the other does not. That is the mechanism by which typing at the end of a bold fragment stays
  bold, while typing at the end of a link is no longer part of it.
- **`RemoveMark` has to be a range operation**, not a reference to an `AddMark` identifier. Two
  people may bold overlapping ranges while a third unbolds their intersection.
- **Conflict resolution:** for a given character and a given mark type, the operation with the
  highest `(lamport, replicaId)` covering it wins. The same total order RGA already uses — no new
  arbitration mechanism.
- **Rendering:** a sweep over the block list maintaining a set of active marks, emitting spans.
  `O(n + m log m)`.
- **Expansion behaviour** (`expand`) is a property of the mark **type**, not of an individual
  operation: bold expands at the end, a link does not. The type table lives in one place.

### 5.3 Two traps the compiler will not catch

`Op` is `sealed interface Op permits Op.Insert, Op.Delete`. Adding variants makes the compiler
point at every exhaustive `switch` — `RgaDocument.isAlreadyKnown`, `RgaDocument.tryApply`,
`OpCodec.toJson`. That is an advantage and worth naming.

But **two places use a ternary `instanceof` instead of a `switch`** and will pass
compilation silently, with the wrong result:

| Place | Code | Effect |
|---|---|---|
| `OperationStore.java:83` | `op instanceof Op.Insert ? "i" : "d"` | A new operation type recorded in the log as a **delete**. Corruption of the source of truth |
| `DocumentStompController.java:75` | `op instanceof Op.Insert ? "insert" : "delete"` | A wrong trace attribute — misleading diagnostic data |

Both to be rewritten as a `switch` **before** the variants are added, as a separate commit.

### 5.4 The wire format and persistence

- `OpCodec`: new discriminators `"t"` (alongside `"i"` and `"d"`). The format is hand-written and
  documented in javadoc — the extension is clean, and the javadoc needs updating.
- `operations.op_type CHAR(1)`: **no schema migration**, new codes suffice. `payload` is `JSONB`.
  The V1 schema turns out to be sufficient here — worth noting.
- `DocumentSnapshot.CURRENT_FORMAT_VERSION`: bump it. The migration is **free by design** —
  `V1__initial_schema.sql:46` says outright that a snapshot with a mismatched version is ignored and
  the state rebuilt from the log. The decision from the operation-log decision pays off here.
- **The conformance corpus needs regenerating**, plus new vectors for marks. The CI job "the
  conformance vectors are current" covers the rest.

### 5.5 The consequence for GC — the most serious in the whole plan

The tombstone-collection design forbids removing a tombstone that is another block's
`originLeft`. The README already admits that as a result GC reclaims practically only the
document's tail.

**After CP13 a second condition appears: a tombstone that is a mark anchor cannot disappear
either.** After CP14 a third: a comment anchor. GC's effectiveness drops further, and rich text
increases the number of anchors in proportion to how much formatting there is.

This may force the redesign the README identifies as the right solution: **dropping the content of
deleted nodes while keeping them in the structure** (the Yjs approach). It changes the `Block`
representation and invalidates the snapshot format — but the snapshot format is being bumped in
this checkpoint anyway, so **if that change is going to happen, now is the cheapest moment.**

A decision to be taken at the start of CP13, not during it. To be recorded in the comment split together
with a revision of the tombstone-collection design.

### 5.6 Frontend

`remoteCursors.ts` is a ready-made pattern: CRDT identifiers → offsets → CodeMirror decorations,
computed at the last moment before drawing. Marks travel the same road through
`Decoration.mark()`. CodeMirror stays purely a view layer —
the editor-layer decision needs no revision.

A toolbar, keyboard shortcuts, and `bridge.ts` extended with formatting transactions.

**The double implementation (Java + TS) applies.** It is the debt described in the README and CP13
doubles it — but doing without it on the client would mean the browser cannot render formatting
without a response from the server, that is, losing optimistic editing.

### 5.7 Tests

| Test | The failure mode it catches |
|---|---|
| `markConvergence` (property) | Marks are not commutative — two people format and each sees something different |
| `insertIntoMarkedRangeInheritsMark` | The classic "formatting per character" bug from 5.1 |
| `concurrentBoldAndUnboldConverges` | No total order on marks; formatting flickers |
| `markSurvivesConcurrentDeleteOfBoundary` | An anchor on a deleted character diverges the structure |
| `expandBehaviourDiffersByMarkType` | Typing after a link extends the link |
| `markAnchorPreventsGarbageCollection` | GC removes an anchor — the symptom only appears at the next edit in that place |
| Java ↔ TS conformance for marks | The two implementations diverging silently |

---

## 6. CP14 — Comments (2–2.5 days)

### 6.1 Two layers, only one of which is a CRDT

**The anchor** — a range in the document. It is a mark of type `comment` carrying the thread
identifier as its value. All the machinery was built in CP13; this checkpoint **uses** it rather
than building it.

**The thread's content** — comments and replies. **Deliberately not a CRDT.** A message has one
author, is appended whole, and is not concurrently edited character by character. Ordinary
relational tables suffice.

That distinction is worth recording in the authorization guard, because the temptation of "we have a CRDT, so
everything will be a CRDT" is real, and the answer is: a CRDT solves a specific problem (concurrent
editing of the same sequence), and applying it where that problem does not exist is a cost with no
benefit.

The `V4__comments.sql` migration: `comment_threads` (doc_id, status, created_by, resolved_at),
`comment_messages` (thread_id, author_id, body, created_at, edited_at).

### 6.2 An orphaned comment

What happens when the anchored text is deleted. The anchor survives, because tombstones survive —
the thread becomes zero-length.

The product decision: show it as "detached" in the margin, do not delete it silently. Deleting
would mean losing a conversation in response to a text edit, which is surprising behaviour. It goes
into the failure modes section of the README.

### 6.3 Permissions

The `commenter` role from CP12 acquires content here: writing to threads, no writing to the text.
Enforced by the "role → operation types" mapping from 4.3 — if CP12 was done properly, this
checkpoint adds no new authorization code, only an entry in the table.

### 6.4 Tests

| Test | The failure mode it catches |
|---|---|
| `commentAnchorFollowsConcurrentEdit` | An anchor on offsets rather than identifiers — the comment points at somebody else's text |
| `deletingAnchoredTextDetachesThread` | Deleting text loses the conversation |
| `commenterCannotEditDocumentText` | A regression in the role mapping from CP12 |
| `threadSurvivesDocumentRestore` | Restoring a version from history loses comments added later |

---

## 7. CP15 — Re-measurement and documentation (1.5–2 days)

**Mandatory, not optional.** After CP13 every number in the README describes a different system.
Leaving them would be exactly what `CLAUDE.md` warns against — documentation reconstructed from
memory instead of measured.

- The harness runs again, **with presence traffic and formatting operations** in the profile.
- A new dimension in the table: the share of marks in the traffic and in the snapshot size.
- The measured GC overhead after anchors are added — a number confirming or refuting the concern
  from 5.5.
- README: separating "done in v2" from "still out of scope"; new failure modes (an orphaned
  comment, revoked permissions mid-session, a soft-deleted document).
- A new demo — a GIF from a Playwright script, as in CP9. Four new features need a new scenario.
- **A pass back over the ADRs** (0008–0012) — the house convention: write down what actually came
  out, including what turned out worse than assumed.

### How it turned out

| Task | Result |
|---|---|
| A harness with presence and formatting | Done. **It revealed a bug, not just numbers** — rate limiting lost operations silently (12 scenarios, 351 refusals; after the fix, zero losses) |
| The share of marks in a snapshot | **8 B per mark.** Negligible — a mark does not copy the text it covers |
| GC overhead after adding anchors | **The concern is confirmed, and more strongly.** Not "two tombstones per mark" but a whole run-length block. On text written in one continuous run: 0 % reclaimed. the tombstone-collection design, the CP15 measurement |
| README: done in v2 vs still out of scope | Done by **strikethrough** rather than rewriting — it shows which original diagnosis was right and which was not |
| New failure modes | Five new rows, including a role without write permission — found while writing that section |
| A new demo | A second GIF, a separate Playwright scenario. One would have been too long or too cursory |
| A pass back over the ADRs | 0002, 0003, 0004 and 0005 amended; 0007 marked as superseded by 0008 |

Outside the plan: **the user's role now travels to the client**. It came up while describing the
rate-limit fix — the same mistake (silent server-side enforcement) occurred a second time, in a
different place, and led to the same silent replica divergence.

---

## 8. Estimate

| Checkpoint | Days |
|---|---:|
| CP10 — Foundations | 2–3 |
| CP11 — Multi-document | 2–3 |
| CP12 — Permissions | 2.5–3.5 |
| CP13 — Rich text | 5–7 |
| CP14 — Comments | 2–2.5 |
| CP15 — Measurement + documentation | 1.5–2 |
| **Total** | **15.5–21 working days** |

For comparison: v1 (CP1–CP9) took ~13–17 days. **This plan is a second project the same size as
the first.** Worth knowing before starting rather than halfway through CP13.

If the budget is smaller than the whole, the sensible stopping points are the end of CP12 (a
product with many documents and permissions, without rich text — a coherent whole) or the end of
CP13 (without comments). **Stopping in the middle of CP13 is not a stopping point** — the wire
format and the conformance corpus would be in a transitional state.

---

## 9. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Diluting the message** — the project loses its one-sentence description | High | The README still opens with the CRDT core and the demo, not a feature list. Rich text is presented as a *consequence* of a good core rather than as a separate feature |
| **CP13 overruns** — Peritext in two languages is the hardest thing in the whole project | High | Close it on one mark type (bold) with full property tests, and only then extend. Types are cheap, the mechanism is expensive |
| **GC stops working** once anchors are added | Medium | Decide on redesigning the block representation **at the start of CP13**, not during it (5.5) |
| An IDOR vulnerability lands with CP11, before CP12 closes it | Medium | CP11 does not go onto a hosted instance before CP12. Or: a temporary restriction to one's own documents |
| The README's numbers go stale silently | Medium | CP15 is mandatory; consider a CI job comparing the harness against a recorded result |
| The two implementations diverge on marks | Low | The conformance corpus plus the existing CI job. The mechanism was proven in v1 |

---

## 10. What this plan deliberately does NOT cover

An extended scope does not mean an unlimited one. These stay out of scope, destined for the "what I
would do differently in production" section:

- **Embedded objects** (tables, images, code blocks). Peritext handles marks over text; embedded
  objects are a different structural problem.
- **CRDT-aware undo.** Still absent, still listed in the README. After rich text it becomes harder
  — an undo has to cover marks.
- **Fugue instead of RGA.** The interleaving anomaly stays documented rather than fixed.
- **Cluster-wide presence.** Listed in the README; unchanged by this plan.
- **Offline editing of comments.** The offline buffer covers CRDT operations; comments require a
  connection.
- **Notifications, mentions, export to docx/pdf, mobile versions.** Product scope with no
  engineering content matching the project's thesis.


---

## 11. Final audit — what was NOT done

At the end the plan was walked point by point, against the code rather than against memory. This
section exists because "the plan is closed" without such a pass is a declaration rather than a
fact — and because the first item below is proof that the declaration would not have been enough.

### Not done, found by the audit and fixed

| Plan item | What it was |
|---|---|
| §4.3 "a second line of defence", the `historyRestoreChecksWriteAccess` test | **`HistoryController` checked no permissions at all.** Three open endpoints, including a full read of somebody else's document content and overwriting it. The only route in the project by which a role without write permission could change the text. Fixed, and the test verified by mutation |
| §4.6 `workspaceRoleIsOverriddenByDocumentRole` | Inheritance worked, the test did not exist. Added |
| §6.4 `threadSurvivesDocumentRestore` | The behaviour was correct, the test did not exist. Added |

The first item matters more than its fix. The plan **named that path explicitly** and that was not
enough — which means a protection resting on somebody remembering a point from a document is not a
protection. The conclusion went into the permission model and into the README.

### Done differently from what the plan assumed — deliberately

| Plan item | How it actually is |
|---|---|
| §2.2 "broadcast the changed participant, not the whole list" | A delta for cursor movement, **the full list on join and leave**. A leave cannot be expressed as a delta ("this entry is gone"), and a third message type would cost more than sending the list on an event that happens once per session |
| §3.2(b) "the registry maintains a set of dirty documents" | A lock-free `hasPendingWork()` before taking the lock. It solves the same problem (10k lock acquisitions/s) without extra state to keep in sync |
| §4.4 "a `collabedit:acl:{userId}` channel, actively tearing down subscriptions" | A permission cache with a 5 s TTL plus immediate invalidation on the instance that performs the revocation. Revocation takes effect **within 5 s** rather than instantly on every instance. Cheaper and sufficient at this scale; the bound is written into the README |
| §4.6 `rateLimitClosesFloodingSession` | The server **does not close the session**; it replies `RATE_LIMITED` and the client retries. Closing a session on a rate breach loses the work of a client who is simply typing fast — see the bug described above |
| §5.5 "decide on the block representation at the start of CP13" | The decision was **deferred**, deliberately and with a reason: the measurement was missing. The measurement is in CP15 and came out badly, so the change stopped being optional — but the cheapest moment (bumping the format version) was missed. That is a real cost of this decision and there is no point hiding it |

### Deliberately not done, written up in the README

- **The `invitations` table** (§4.2) — the permission model works without invitations; without a
  screen for managing access the table would be dead schema.
- **An access management screen.** `grant` / `revoke` / `setVisibility` exist and are tested, but
  only code calls them. The model works; it is one step short of being a product feature.
- **A hosted instance and a link above the GIF** (§2.3, "eventually"). Docker Compose brings the
  whole thing up with one command; there is no hosting.
