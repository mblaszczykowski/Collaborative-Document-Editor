# Collaborative Document Editor

Real-time collaborative text editing. Several people in one document, over a bad connection, typing
into the same paragraph.

![Two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

The recorded demos show an earlier UI. Current validation and remaining work are listed in
[docs/review-fix-status.md](docs/review-fix-status.md).

The merge algorithm is an RGA-style CRDT, written from scratch twice: Java on the server,
TypeScript in the browser. Formatting, comments and suggestions are built on the same core.

Enable local demo login or configure an OIDC provider before starting the stack on :5173.
See [Running it](#running-it) for the complete commands.

## Features

- **Editing** by multiple participants, with cursors and presence across instances. Current
  capacity and propagation latency have not been benchmarked after the durability changes.
- **Offline editing** into IndexedDB, surviving a tab close, draining on reconnect.
- **Undo** that takes back only your own operations.
- **Rich text**: six inline styles, plus headings, quotes and lists.
- **Comments** anchored to a range. Delete the text and the thread stays, marked detached.
- **Suggestions**: edits as proposals over the real text. Operations are generated server-side, so
  a suggester cannot write directly.
- **History**: time travel to any point, diff two versions by character identity, restore.
- **Access control**: four document roles, invitation links, search, trash, and revocation during
  a session. Workspace APIs exist; complete workspace membership and UI flows remain unfinished.
- **A diagnostics bar** in the app with latency, jitter and drop-rate sliders and a button that
  cuts the network. Open a document in two windows and break one of them.

## Architecture

![Two browsers and two backend instances, each holding an RGA replica, all appending to and replaying from one Postgres operation log](docs/diagrams/architecture.svg)

All four replicas use the same merge rule. Browsers apply edits optimistically; the backend
authorizes them, reserves their payload identities, and records the canonical operation log in
Postgres. Instances also gossip over Redis, covered under [Cluster](#cluster).

```
crdt-core/     the algorithm. A Maven module with no Spring dependencies, so it can be
               benchmarked alone and the compiler stops the app layer leaking in

app/           Spring Boot 3, Java 21
  ws/          STOMP, presence, per-frame authorization, rate limiting
  auth/        roles, permissions, invitations, tokens
  domain/      the in-memory document replica
  persist/     operation log, snapshots, catalog, search
  history/     time travel, diff, restore
  comments/    threads and messages
  suggestions/ accept and reject
  cluster/     Redis pub/sub, anti-entropy, permission invalidation

frontend/      React + TypeScript + Vite
  crdt/        the second RGA implementation, mark layer, undo
  transport/   STOMP client, offline buffer, gap detection, network simulator
  editor/      CodeMirror 6 bridge, formatting, remote cursors
  ui/          document list, history, comments, suggestions, sharing

conformance/   shared test vectors and the mark-type table
ops/           Prometheus / Tempo / Grafana / OTel Collector
```

CodeMirror 6 is the view only. Slate or ProseMirror would bring their own document model, which
would be a second source of truth next to the CRDT.

The two implementations have to agree exactly. Keeping them in step is a shared corpus in
[`conformance/`](conformance): operation sequences with expected text and formatting, replayed by
both suites. Change the ordering rule in one language and the other language's build fails.

The corpus has a hole. Vectors carry operations that already exist, so a rule applied at *creation*
time, like whether bold grows when you type at its end, changes no vector. That rule lives in a
JSON file both sides read, with a test each way. I added the second direction after declaring a
mark type in Java, forgetting it in the JSON, and watching both suites stay green.

## The CRDT

Sending positions (*insert "!" at index 2*) breaks as soon as anything is inserted earlier. Every
position-based approach ends up rewriting other people's messages to compensate; that is
Operational Transformation, and it is hard to get right for N peers without a server.

So positions are never sent. Each character gets an identifier at creation and keeps it, and an
insert names the character it goes after:

```
Id = (replicaId, lamport)                       printed as  1@7
Insert { id: 1@7, originLeft: 1@6, text: "!" }
```

`lamport` is a Lamport clock, bumped on every local edit and to `max(local, incoming + 1)` on
receipt. `originLeft` refers to something that already happened, so no later edit invalidates it.

### Ordering concurrent inserts

Find the origin, walk right while the block you meet outranks the one being inserted, stop at the
first that does not. Identifiers compare by `(lamport, replicaId)`, which is a total order.

```
  both replicas hold "ok"; neither has seen the other
  Ann inserts 1@5 "!" after k        Bo inserts 2@5 "?" after k

  Ann receives 2@5:  1@5 > 2@5? no  -> stop           both get  o k ? !
  Bo  receives 1@5:  2@5 > 1@5? yes -> skip, then end
```

Opposite delivery orders, same result, nothing rewritten or rolled back. Higher identifiers sort
left; the direction is arbitrary but both implementations must pick the same one.

Termination relies on the Lamport clock. Anything created causally after a block has a higher
timestamp, so skipping a block skips its whole subtree and the scan ends on its own.

### Blocks

One object per character is wasteful. Each block holds a whole run:

```
  Block{1@1, "Hello "}  Block{1@7, "world"}

  delete "wor" splits it, because a block is entirely alive or entirely dead:

  Block{1@1, "Hello "}  Block{1@7, len 3, deleted by 2@40}  Block{1@10, "ld"}
                                          ^ which operation deleted it
```

A per-character flag array would complicate every index operation for little gain. The insertion
scan stays coarse: lamports inside a block increase and its characters are causally chained, so if
the first outranks the incoming one, so does the rest. Typing appends into the previous block when
replica, lamport and origin line up. A deleted block keeps its identity and length so it can still
serve as an origin.

`RgaDocument` reaches the Java block list through an interface with two implementations.
`ArrayBlockList` uses linear scans for position and identity lookups; `TreapBlockList` gives expected
O(log n) indexed lookups and updates, with each node carrying its subtree's visible and total
length. Full text reads, GC, formatting resolution, and insertion scans still depend on the
document or affected range size. The browser currently uses an array. Treap priorities are a
splitmix64 hash of the block identifier rather than random, so an identical block structure
reproduces its tree shape without a separate random seed.

Naive implementation first, then tests, then JMH, then the tree. The commit history shows that
order.

### Tombstones and GC

A tombstone keeps its identifier and length so old operations can still resolve their origins and
insert retries cannot resurrect deleted text. GC releases its text payload after the deletion is
causally stable. The server takes the component-wise minimum of its version vector and reported
replica vectors retained within the seven-day offline grace period. A server crash or unrecorded
departure receives the same grace as a clean disconnect.

```
  A { 1:120, 2:88, 3:200 }   B { 1:118, 2:91, 3:200 }   C { 1:120, 2:88, 3:196 }
  meet: { 1:118, 2:88, 3:196 }
```

The test is on the **deletion**, not the insertion. An audit found it applied to the insertion,
which is only equivalent when nothing is deleted soon after being written. A paragraph written last
week and deleted a second ago has an insertion far below the meet, so its tombstone looked
collectable while a peer 10 ms behind still displayed the text. That is what `Block.deletedBy` is
for; `GcSafetyTest` pins it.

All collected blocks retain identity skeletons, including buffered-operation and mark anchors.
Adjacent compatible skeletons coalesce. This reclaims payload memory, rather than promising that
all historical identity metadata disappears.

Offline duration alone no longer makes a retained origin unusable. An operation whose identity is
missing below a legacy GC boundary, or whose clock exceeds the supported budget, receives
`RESYNC_REQUIRED`. Recovery freezes editing while it creates a durable recovered draft containing
supported formatting. If recovery fails, the local outbox is retained and export remains available.

### Lamport clock notes

**The version vector does not deduplicate.** Per-replica counters are not contiguous, since the
clock jumps whenever someone else's operation arrives. Replica A legitimately produces lamports
1, 2, 47, 48, 103, and "everything from A up to 48" cannot distinguish a lost message from a jump.
An `Insert` is a duplicate if its identifier is already in the structure. Deletes track observed
operation IDs so a new concurrent deletion still contributes causal metadata and receives an
acknowledgment. Format-5 snapshots retain winning deletion metadata rather than every observed
delete ID; application replay skips entries already covered by the contiguous log boundary and
validates retries against the canonical operation log. Detecting gaps is the transport's job.

**The clock does not buy convergence.** These notes used to claim, with a counterexample, that
skipping the bump on receipt causes divergence. That was wrong. I removed the bump and ran 200 000
random scenarios, 3-5 replicas, 30 edits each, every operation delivered twice: zero divergences.
Integration is deterministic with respect to the *set* of elements present, not their arrival
order.

The bump stays. It is a premise of the published RGA proof, and it guarantees that a concurrently
typed run stays contiguous. Nothing was testing that either, so removing the bump left every
property test green. `RgaInvariants` asserts it now.

## Anchors and marks

Cursors, formatting, comment highlights and suggestions all need a place in the document that
survives other people typing, so they are one object: `Anchor { charId: Id, side: BEFORE | AFTER }`.
An offset would stop pointing at the same place as soon as somebody inserts earlier text.

Which side the anchor sits on decides whether formatting grows:

```
  a mark ends between b and c in "abcd", then somebody types X into that gap:

    end = after(b)   ->   a b | X c d     X is outside, the mark does not grow
    end = before(c)  ->   a b X | c d     X is inside, the mark grows over it

  bold ends before(c)  ->  typing at the end of bold text is bold
  link ends after(b)   ->  typing after a link does not join it
```

Which types expand lives in
[`conformance/schema/mark-types.json`](conformance/schema/mark-types.json), read by both
implementations. Unknown types do not expand: a mark that expands wrongly restyles text nobody
formatted, while one that fails to expand costs a keystroke.

Marks are a separate CRDT layer over the characters, in the style of Peritext. The character layer
never gained a field for bold, comments or suggestions.

![Bolding a range, typing inside it, commenting on a selection, deleting anchored text, restoring from the bin](docs/demo-v2.gif)

```
  Ann bolds "llo" in "Hello world". Marek, who has not received the mark, types "XX" inside it.

  a range between anchors        a per-character flag
  He[llXXo] world                H e l l X X o   w o r l d
     bold covers XX                  B B _ _ B
                                           ^ permanent hole
```

Resolving marks to spans is a sweep over the block list. "The sweep agrees with itself on every
replica" passes when the sweep returns nothing at all, so it is checked against `NaiveMarkResolver`,
an obviously correct and far too slow implementation. Replacing `MarkSet.spans` with
`return List.of()` left all four original mark properties green; the oracle catches it immediately.

Twelve mark types share the mechanism: six inline styles, `comment`, two suggestion types, and
`heading` / `quote` / `list`.

**Comments** are half in the CRDT and half out. The anchor is a CRDT operation; the conversation is
rows in Postgres. A range must merge without an arbiter, but a sentence written by one person has a
single author and an obvious order. Two consequences: the anchor is created after the thread, so a
thread briefly exists without one, and the highlight arrives over the CRDT while the conversation
arrives over REST, so the highlight shows up first. Offline, a comment goes to an outbox with its
anchor and an idempotency key.

**Suggestions** operate on the real document. An insertion proposal inserts text with a
`suggest-insert` mark; a deletion proposal adds `suggest-delete` while leaving the text in place.
Accepting an insertion removes its proposal mark; rejecting it deletes the proposed range.
Accepting a deletion deletes the range; rejecting it removes the proposal mark. The backend stages
these commands separately and commits their operations and proposal metadata together before
returning success. The UI exposes explicit proposal controls rather than intercepting every typed
edit as a complete suggest-mode editor.

## Undo

CodeMirror's own history is off. It works on text ranges, so Ctrl-Z would also take back what other
people typed.

A CRDT operation cannot be inverted, since a deleted character has a tombstone and does not come
back. Restoring text inserts new characters with new identifiers, the same visible result with a
different identity. Yjs does the same.

A stack entry stores a character identifier, not an offset. Undoing formatting is not "remove the
mark" either: the inverse of "make this bold" is "restore what this range had before", which
differs if somebody else's mark of the same type was already there. Deleted text can still leave a
resolvable identity anchor. If that identity is missing, the entry is skipped rather than guessed
at. Deletion entries retain supported formatting; large undo operations are split to respect
transport limits.

## History and diffs

The operation log in Postgres is the source of truth. Snapshots are a cache: deleting all of them
changes only load time, which `snapshotsAreOnlyACache` verifies. Time travel is a replay,
`GET /api/documents/{id}/history/at?seq=`. Restore is recorded as new operations, so it appears in
the history itself.

Diffs compare two versions by character identity, which answers something a text diff cannot:

```
  delete a word, then type the same word again

  text diff      "the quick fox" vs "the quick fox"  ->  no change
  identity diff  1@40..1@44 gone, 2@81..2@85 new     ->  a change, by a named author
```

Restore uses a common prefix and suffix rather than a full Myers diff. It generates bounded insert
and delete operations, commits them before reporting success, and records the acting user.
Formatting is restored; comment and suggestion marks are excluded because their conversation
records have a separate lifecycle. Canonical replay reads the log in bounded pages. History
pagination and retention/compaction remain future work.

## Offline and reconnect

Editing continues into an account-scoped IndexedDB buffer that survives a tab close and drains on
reconnect. This guarantee depends on browser storage availability; storage failures are reported.

An operation leaves that buffer on the **write** acknowledgement, never the broadcast. If an
instance dies in between, the operation may be on other screens but not in the log, and the client
retransmits. A durable payload-identity claim rejects conflicting reuse of an operation ID before
speculative merge. Retrying an already committed operation produces another acknowledgment.

The reverse failure is silent. A broadcast to one client is lost; nothing errors and the client has
a hole it cannot see. Watching `server_seq` does not help, because that cursor advances on
acknowledgements, which go to everyone, so it looks current while the operation is missing. So the
acknowledgement carries the operation's identity, and the client checks whether it actually handled
`7@41`.

That check started as "does my replica contain character 7@41", which is exact for inserts and
always false for deletes and marks, whose identifiers never name a character. One backspace by
anybody set the gap flag and the client resynchronised from zero every 15 seconds. It is a bounded
window of handled identities now. A periodic reconciliation also retries aged unacknowledged
operations while the socket remains connected, so lost submissions and acknowledgments do not
require a disconnect to recover.

![Client connection states, resynchronization, pending work, authentication and recovery](docs/diagrams/connection-states.svg)

Expired sessions and denied access suspend editing and retain pending work; they require renewed
authentication or access. Recovery and snapshot resynchronization have explicit busy/error states.
The heartbeat is a backstop; the client also reacts to browser connectivity events and socket
errors. The application shell is cached for later offline reloads after a successful online load;
first-ever offline access and unavailable browser storage cannot provide the same guarantee.

## Cluster

Every backend instance is a CRDT replica. There is no application leader or required session
affinity; Postgres document-row locks serialize identity claims and canonical log sequence
assignment.

Redis is a fast path with no delivery guarantee. The log is authoritative and anti-entropy repairs
missed operations. The replay cursor tracks a contiguous prefix, and a local flush cannot skip an
earlier remote write. HTTP snapshots reconstruct committed state at their declared boundary;
buffered dependencies prevent incomplete states from being labeled as complete. `ClusterChaosIT`
pauses the Redis container mid-write, and `redisOutageIsRepairedByAntiEntropy` covers the repair. A deterministic
simulation drives the same scenarios from a seed instead of thread interleaving, so failures come
back reproducible.

## Auth

Production sign-in uses configured OpenID Connect providers, so the application never sees a
provider password. Local development can explicitly enable the separate name-only demo login.

```
browser --1--> /oauth2/authorization/google --> server --2--> Google
                                                               |
         <--4-- redirect ?code=... <-- server <--3-------------+
app      --5--> POST /api/session {code} --> server --> session token
```

**Identity is `(provider, subject)`, never the email.** `subject` is the provider's own identifier
and the only field OIDC promises is stable. Emails get changed and, at some providers, reassigned,
so keying on one would eventually hand somebody another person's documents.

**Step 5 keeps the token out of the URL.** The provider redirects to the server, the application
needs the token on another origin, and redirect targets land in browser history and in the
`Referer` of whatever loads next. What crosses instead is a single-use code: 192 random bits,
stored hashed, dead on first use, expiring after a minute. A fragment would still be readable by
any script on the page; a cookie would attach the token to requests the app never made.

Before this, `POST /api/login` took a name, minted a UUID and checked nothing, so "Anna" today and
"Anna" tomorrow were different users. `link` visibility grants EDITOR to anyone signed in, and
signing in cost nothing.

The session normally lives in `localStorage`, shared across tabs in the same browser. `?tab-session`
selects tab-local `sessionStorage`, useful when demonstrating separate people. The name-only demo
login requires `COLLABEDIT_DEV_LOGIN`; tab-local storage does not bypass authentication. When browser
storage is unavailable, the session uses a memory fallback.

WebSocket sessions retain the verified token deadline and enforce it on inbound and outbound
traffic. Unknown authorization pauses delivery rather than allowing confidential broadcasts.
Client replica identities are allocated durably in Postgres and belong to an account; old owned
identities remain valid for offline replay. Server identities use a separate negative namespace.
Historical identities with unambiguous account ownership are migrated; ambiguous identities are
reserved rather than assigned to another account and can require draft recovery.
Local sign-out removes the browser session but does not revoke copies of an already issued bearer
token; those remain valid until their original deadline.

Three holes turned up in review, all of them only visible after using the product for an afternoon:

- A 401 was handled in exactly one place, the document-list bootstrap. Elsewhere the editor told
  users to ask a colleague to re-share a document nobody had touched. All REST calls route through
  one handler now.
- The WebSocket authenticated once at `CONNECT` and was never re-checked, so a lapsed session kept
  working until the socket dropped, then reconnected with a dead token and retried behind "Working
  offline" forever.
- Signing out could not be followed by signing in as somebody else. Google gets
  `prompt=select_account`; GitHub has no equivalent, so switching there happens at GitHub.

## Authorization

Two doors, HTTP and WebSocket, one `PermissionService` behind both. The HTTP rule: declare what
access you need or your handler does not run.

![HTTP declarations, token checks and document capabilities enforce access; WebSocket frames are checked on entry and confidential delivery](docs/diagrams/authorization.svg)

1. **The default branch refuses**, so a new controller fails closed. `HistoryController` spent weeks
   serving the full content of any document and letting a reader overwrite it. It answers 500 rather
   than 404 because this is our bug, not a bad request.
2. **The document id comes from the declaration**, not from wherever the handler looks, which is why
   creating a comment is `POST /api/documents/{id}/comments`.
3. **The build enforces the same rule as the runtime.** An undeclared mapping fails
   `EndpointAuthorizationArchitectureTest`, and `publicEndpointsAreExactlyTheExpectedOnes` pins the
   exemption list by name.

The guard only checks that a decision was made, not that it was correct.
`@RequiresDocumentAccess(READ)` on a write endpoint passes.

`DocumentAccessInterceptor` checks every SEND and SUBSCRIBE frame, asking whether your role permits
*this operation* rather than whether you can reach the document, since comments and edits share a
channel. An unresolvable destination is refused, which it was not at first: Spring's simple broker
matches destinations as Ant patterns, so `SUBSCRIBE /topic/**` resolved to no document, passed
unchecked as a private queue, and then matched every document's broadcast.

## Tests

Current test inventory for the standard validation commands, as of 2026-10-14:

| Layer | Tests |
|---|---:|
| `crdt-core`: property, invariants, conformance vectors, snapshot bytes, marks, GC safety | 101 |
| `app`: unit/integration, deterministic simulation, cluster, security, presence, architecture | 1,504 (2 skipped) |
| `frontend`: CRDT agreement, editor bridge, snapshot budgets, undo, transport, UI helpers | 204 |
| end-to-end: configured browser scenarios, including built-container runs | 29 |

The app count includes 1,207 parameterized deterministic simulation cases. Those are seeds,
not additional assertions. Counts vary with Maven profiles and test filters; final run results and
commands are recorded in [docs/review-fix-status.md](docs/review-fix-status.md).

Property tests of the algorithm come first, then integration tests against real Postgres and Redis
via Testcontainers, then chaos and seeded simulation, then ordinary unit tests. The nightly workflow
is configured to run the load harness, JMH, and fan-out measurements; it does not establish that
the historical figures below describe the current build.

Earlier mutation checks found three tests that failed to detect broken implementations:

- Removing the Lamport clock bump left all fifteen property tests green. See
  [Lamport clock notes](#lamport-clock-notes).
- Removing the treap's aggregate update after a tombstone passed convergence, because every replica
  was wrong identically. The text agreed and only the positions were wrong.
- `garbageCollectionSparesMarkAnchors` passed with its mechanism off. The mark was anchored
  mid-text where an older rule already protected the character.

## Performance

The figures below are historical measurements from before the October 2026 review fixes. They
are retained to explain earlier algorithm choices, not as validated capacity or latency claims
for the current build. Durable per-operation payload claims, revised batching/replay, identity
retention, and updated measurement clients require fresh runs. This validation did not rerun the
load harness, JMH, or fan-out benchmarks.

One machine: MacBook, Apple Silicon, 10 cores, JDK 25-ea, 4 GB heap, Postgres and Redis in
containers alongside. Clients and server share one process, so these exclude real network latency.

Absolute times on this rig are only comparable against times measured the same way. The same code
and marks measured 0.28 ms alone and 0.039 ms inside the full suite, purely from JIT warm-up. That
is why there is no millisecond threshold in CI.

JMH, average operation time:

| Operation | Document | `ArrayBlockList` | `TreapBlockList` | Difference |
|---|---:|---:|---:|---:|
| Position to identifier | 1 000 | 0.367 µs | 0.065 µs | 5.6x |
| | 10 000 | 5.510 µs | 0.149 µs | 37x |
| | 100 000 | **60.2 µs** | **0.286 µs** | **210x** |
| Identifier to position | 1 000 | 0.187 µs | 0.068 µs | 2.7x |
| | 10 000 | 2.863 µs | 0.122 µs | 23x |
| | 100 000 | **40.3 µs** | **0.210 µs** | **192x** |
| Full insertion | 100 000 | 684 µs | 4.66 µs | 147x |
| Reading the whole text | 100 000 | 310 µs | 429 µs | **0.72x, treap slower** |

The shape matters more than the values. For ten times the document the naive scan grows 15x and
11x, the treap 2.3x and 1.9x. Reading the whole text is 40% slower in the treap, since walking a
tree has worse locality than iterating an array. Full text assembly remains linear and is used by
snapshots, previews, and editor synchronization; indexed lookups are also used during editing.

Propagation between clients, from a custom harness. Both ends share a clock, which is why it is not
Gatling. Each client makes 40 insertions at the *start* of the document; typing at the end
compresses into one block and skips the path that scales.

| Document | Clients | p50 | p95 | p99 | Convergence |
|---:|---:|---:|---:|---:|---:|
| empty | 2 | 27 ms | 29 ms | 30 ms | 55 ms |
| empty | 10 | 74 ms | 83 ms | 86 ms | 516 ms |
| empty | 25 | 114 ms | 154 ms | 165 ms | 513 ms |
| empty | 50 | 202 ms | 360 ms | 374 ms | 499 ms |
| 10 000 chars | 2 | 9.3 ms | 10 ms | 10 ms | 60 ms |
| 10 000 chars | 10 | 28 ms | 31 ms | 32 ms | 60 ms |
| 10 000 chars | 25 | 46 ms | 76 ms | 84 ms | 107 ms |
| 10 000 chars | 50 | 135 ms | 218 ms | 230 ms | 267 ms |
| 100 000 chars | 2 | 4.5 ms | 586 ms | 586 ms | 603 ms |
| 100 000 chars | 10 | 13 ms | 518 ms | 830 ms | 831 ms |
| 100 000 chars | 25 | 67 ms | 92 ms | 695 ms | 712 ms |
| 100 000 chars | 50 | 124 ms | 212 ms | 1299 ms | 1647 ms |

All twelve scenarios converged in that historical run. Document size barely affected its median
latency. Repeat runs of that version reproduced the table
within noise except p50 at 10 000 chars and 10 clients, which came out 28 ms once and 43 ms another
time, which is about how much to trust the second digit.

The fat p99 tails are rate limiting. The harness sends two hundred operations without pause and
exhausts the limiter's burst. My first explanation of the tail was wrong: I blamed the client's
hardcoded 1 s retry, and having the server send the exact wait made p99 *worse*, 872 ms to 1352 ms,
because the server states the time to one token while the client holds a whole buffer, so it
busy-polls. Exponential backoff brought it to 826 ms, inside this rig's noise. What bounds the tail
is refill rate times queued batch.

Mark layer, from `MarkCostMeasurement`: 1000 characters, all deleted, 100 marks.

| Text written | Without marks | With marks (before) | With marks (after) |
|---|---:|---:|---:|
| in one run | 1000 | **0** (0%) | **1000** (100%) |
| character by character | 1000 | 800 (80%) | **1000** (100%) |

Resolving marks runs on every keystroke. Replacing an `O(m²)` scan with a left-to-right sweep took
6400 marks from 62.1 ms to 4.72 ms, moving the usable limit from hundreds to thousands. Below about
a thousand marks it makes no difference, because the dominant cost there is resolving anchors
through the block index.

Snapshot sizes here were wrong for a long time. The published figure of 107 B for 50 000 characters
was measuring gzip on a repeated character, not the format. On prose it is 10 901 B, and a mark
costs 9 B.

RGA's interleaving anomaly is the algorithm's most-cited weakness, so I measured how often it fires.
Two authors typing 8 characters each at the same point, in isolation, exchanging shuffled: the worst
case.

| Cursor advances with probability | Interleaved | Median fragments | Worst |
|---:|---:|---:|---:|
| 1.00, ordinary typing | **0.0%** | 1 | 1 |
| 0.95 | 4.9% | 1 | 2 |
| 0.90 | 10.9% | 1 | 3 |
| 0.50 | 49.2% | 1 | 7 |
| 0.00, the hand-built case | **100.0%** | 8 | 8 |

The 0% for forward typing is structural. RGA only mixes siblings, meaning insertions sharing an
origin, and typing forward makes each character the origin of the next, so a run is a chain. Going
back to the same spot produces siblings. A test asserts this over 2000 trials. Fugue is the known
fix and stays rejected: the anomaly needs two people in the same spot at the same time with one of
them going back to edit, and usually costs one fragment.

The first version of that table reported 0% at p=0.90 and 100% at p=0.50, a cliff where the
mechanism predicts a curve. The trials were seeded `new Random(0)`, `new Random(1)` and so on, and
`java.util.Random` barely moves its first output across consecutive small seeds. Every trial made
the same decision at the first keystroke, which is the only one that matters. Seeds go through
splitmix64 now.

The degradation point I could not explain turned out to be my test client, not the system.
`TestStompClient` built a `StandardWebSocketClient` without configuring the JSR-356 container, whose
default text buffer is 8 KiB. Broadcasts are one operation per frame (~70 B) so nothing hit it, but
resynchronisation is chunked at 300 operations, about 15 KB. The recovery path was the only thing
the buffer blocked, which is why it looked like unrecoverable divergence with no error in the log.
After raising it, all twelve scenarios converged in the historical run and `BroadcastFanoutIT`
reported 3/3 at 2, 10, 25 and 50 clients. That result has not been re-established for the current
durability changes. Current synchronization and generated operations are split by transport
budgets rather than relying on a fixed operation count.

## Failure modes

| Scenario | Behaviour | Verified |
|---|---|---|
| **Connection lost mid-edit** | Continues into IndexedDB, drains on reconnect. | e2e network-cut and reload specs |
| **Instance dies between broadcast and write** | Client retransmits. | outbox release rule |
| **Replica lost or instance restart** | Rebuilt from the log: nearest snapshot plus tail. | `stateSurvivesReplicaLoss`, `snapshotsAreOnlyACache` |
| **Redis outage** | Editing continues, anti-entropy repairs the divergence. | `redisOutageIsRepairedByAntiEntropy` |
| **Broadcast to a client lost** | Client sees an ack for an operation it never got, resynchronises. | gap-detection tests |
| **Two people edit the same character** | Both kept, ordered by `(lamport, replicaId)`. | `concurrentEditOfSameCharacterKeepsBoth`, property `convergence` |
| **Two people move the same paragraph** | **Two copies.** Replicas agree, so the guarantee holds and the result is still wrong. | `concurrentMovesOfTheSameParagraphDuplicateIt` |
| **Late edit after payload GC** | Retained identities resolve the edit; legacy missing identities or clock-budget violations require recovery. | `lateInsertionSurvivesGcAndReload`, stale-anchor and clock-budget regressions |
| **Very large document** | Snapshot byte/structure limits are enforced; current latency and capacity remain unmeasured. | snapshot budget tests; historical JMH/load figures only |
| **Rate limit exceeded** | Frame refused with a retry delay; the client retries from its buffer. | transport retry and integration tests |
| **Deleting formatted text** | Formatting stays on what survived. | `garbageCollectionSparesMarkAnchors` |
| **Deleting commented text** | Thread stays, marked detached, quoting the original fragment. | `threadSurvivesDeletionOfAnchoredText` |
| **Access revoked mid-session** | Applies on every instance, 5 s cache TTL. Frames are re-checked individually, so the session survives and the permission does not. | `revokedAccessTerminatesLiveSubscription`, `PermissionClusterInvalidationIT` |

## Limitations

- **No paragraph identity, so no move operation.** Two people moving the same paragraph get two of
  it. Structure as marks over a flat sequence is what makes everything else merge cleanly, and also
  what makes this unfixable without rewriting that layer. The real fix is a tree CRDT for structure
  with a sequence CRDT inside each node.
- **A document open on three instances lives in three copies.** Per-replica memory has never been
  measured, so `max-loaded-documents: 200` is a guess. At scale this wants document-affinity
  routing.
- **The account model is thin.** No account page, no linking Google to GitHub, no revocation beyond
  the token's twelve-hour life, no account deletion.
- **Invitations are multi-use** until they expire or are revoked; they are not currently bound to
  a recipient account or verified email address.
- **`link` visibility grants edit to any authenticated account with the address**, which is the intended meaning. It
  used to be worse: the document list published those URLs to every signed-in user, so 132 of 133
  documents on a dev instance were world-editable.
- **GC retains identity metadata.** A silent or departed replica can delay payload reclamation for
  seven days. After reclamation, compact skeletons still consume memory and snapshots grow with
  editing history. The browser currently represents contentless blocks with bounded placeholders.
- **Large collections need pagination and retention policies.** Document lists, search, trash, and
  history have result limits; complete cursor pagination and operation/mark-history compaction
  remain unfinished. Queue bounds are counts rather than a full per-document byte budget.
- **Recovery preserves supported text formatting, not conversation records.** Comments and
  suggestion records are separate; their marks are excluded from recovered drafts and historical
  formatting restoration. Recovery can fail, in which case pending local work remains available.
- **Deployment validation is not a production capacity test.** The production profile and private
  management listener are covered. A seeded V13-to-V14 comment-retry migration fixture is tested;
  a populated upgrade fixture for the whole installation and a backup/restore drill remain undone.

## Running it

JDK 21, Node 22.12+, Docker.

```bash
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)
export COLLABEDIT_DEV_LOGIN=true            # local name-only demo; disable for production
docker compose --profile app up -d          # everything, on http://localhost:5173
```

Alternatively copy `.env.example` to `.env`, provide a signing secret, and enable demo login or fill
in an OIDC provider's credentials. The example leaves demo login disabled and the secret empty;
copying it alone does not create a working sign-in configuration.

To work on the code, run the dependencies and the two halves separately:

```bash
docker compose up -d postgres redis
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)
export COLLABEDIT_DEV_LOGIN=true            # or configure a provider

./mvnw -pl app spring-boot:run              # backend on :8090
cd frontend && npm install && npm run dev   # frontend on :5173
```

| Variable | Default | |
|---|---|---|
| `COLLABEDIT_AUTH_SECRET` | none in the application | Required for signing; local Compose has a demonstration-only fallback. |
| `COLLABEDIT_DEV_LOGIN` | `false` | Name-only login. 404 when off. |
| `GOOGLE_CLIENT_ID` / `_SECRET` | unset | Enables the Google button. Both halves needed. |
| `GITHUB_CLIENT_ID` / `_SECRET` | unset | Same, for GitHub. |
| `COLLABEDIT_APP_URL` | `http://localhost:5173` | Where sign-in returns to. |
| `COLLABEDIT_PORT` | `8090` | Must match the registered redirect URI. |

With no provider and no dev login, the login screen says there is no way in. Register the redirect
URI as `http://localhost:8090/login/oauth2/code/{google,github}`. That path is Spring Security's,
not ours, and pointing it at the app is the usual first mistake. When using the nginx frontend,
register the callback on that externally visible origin because nginx proxies the OAuth routes.

```bash
./mvnw install                         # everything, integration tests on real Postgres
./mvnw -pl crdt-core -Pthorough test   # 10 000 property tries, as nightly
cd frontend && npm test
cd frontend && npm run e2e             # two browsers, needs a running stack
```

A failing property prints its seed; `@Property(seed = "...")` replays it. If Testcontainers cannot
reach Colima, point `DOCKER_HOST` at its socket and use
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` for containers. Docker client/API
version configuration is a separate compatibility check when negotiation fails. The validation
record contains the environment and commands used for this review.

For deployment, the frontend resolves the backend at build time
([`config.ts`](frontend/src/config.ts)) and defaults to its own origin with nginx proxying `/api`
and `/ws`, since an `https:` page cannot open a `ws:` socket. Separate origins need `VITE_API_BASE`
at build time and `COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` on the backend.

Compose is a local-development configuration: published ports bind to loopback. For deployment
behind a TLS reverse proxy, combine it with `docker-compose.production.yml`, set a private
`COLLABEDIT_AUTH_SECRET`, `COLLABEDIT_DATABASE_PASSWORD`, and HTTPS `COLLABEDIT_APP_URL`, and
configure a provider and allowed WebSocket origins. The `production` profile refuses development
login and the demonstration signing secret. The production overlay serves metrics on port 8091
within the private Compose network without publishing that port. Do not expose it through the
public proxy. Changing the password variable does not rotate an existing
Postgres volume's credentials automatically.

Set `COLLABEDIT_ALLOWED_ORIGINS` to the external HTTPS origins. Keep nginx and the backend on a
private or loopback network, and configure the outer TLS proxy to overwrite `X-Forwarded-Proto`
and `X-Forwarded-Host`; nginx preserves those headers for OAuth redirects. Do not expose these
trusted proxy listeners directly to untrusted clients.

The nginx frontend proxies `/api`, `/ws`, `/oauth2`, and `/login/oauth2` to port 8090. When using
same-origin nginx login, register the callback on the externally visible frontend origin rather
than the direct backend origin.

Migration V16 invalidates derived snapshots so document state is rebuilt from the operation log
under the corrected replay rules. It preserves operations and document content but may increase
the first load time after upgrade. Garbage collection releases deleted text payloads while
retaining compact character identities needed to recognize old insert replays; monitor
`crdt_gc_retained_identity_blocks` for long-lived document growth.

Migration V17 adds durable payload identity claims before speculative operation merging. Claims
contain a SHA-256 fingerprint, have a document foreign key with cascading deletion, and are removed
in the same transaction that appends the corresponding canonical operation. This adds a database
transaction per newly submitted operation while preserving batched log writes; include that cost
in propagation-latency measurements.

Operation clocks may advance at most 100 million ticks ahead of the current replica and must
leave one million ticks below the codec ceiling. These bounds prevent one malformed edit from
poisoning every collaborator's next operation and accommodate the bounded offline operation
buffer. Exceptional offline work beyond this budget receives `RESYNC_REQUIRED`; preserve it
through recovery or export before discarding the local buffer. The Java and TypeScript wire
clock ceiling remains unchanged.

Format-5 snapshot decoding is bounded on both clients and servers: 16 MiB compressed, 64 MiB
decompressed, 16 MiB per text field, and 16,777,216 aggregate UTF-16 units including deleted-content
placeholders and mark JSON. Structural limits are 200,000 blocks, 100,000 marks, and 50,000 entries
per version vector. Invalid counters, flags, UTF-8, overlapping identities, truncated fields, and
trailing decoded payload bytes are rejected. The browser cancels gzip expansion at the byte limit
before allocating the complete result. Very large valid snapshots can exceed these supported load
limits: the server treats them as cache misses and replays the canonical log, while the browser
refuses the snapshot. Reducing the browser's deleted-content placeholder storage remains a separate
optimization; these limits bound its current allocation rather than changing snapshot format.

The baseline review and proposed improvement backlog are in [docs/app-review.md](docs/app-review.md).

## Observability

One keystroke produces a single trace across both services: `edit.submit` in the browser, then
`op.receive`, `crdt.merge` and `op.broadcast.local` on the server. Context travels in a STOMP frame
header, since the WebSocket handshake happens once per session and an HTTP header would describe
the session rather than the edit. The log write is attached by links rather than nesting, because
one write covers operations from many traces.

Metrics: active sessions, propagation latency percentiles, operations merged, conflicts resolved,
characters reclaimed by GC, cluster messages dropped, frames refused by reason. The
[dashboard](ops/grafana/dashboards/collabedit.json) is in the repo and each panel says what a bad
value looks like. A permanently zero count of gap repairs means the repair path has never run,
which is not the same as healthy.

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana on :3001
```

For container tracing, enable both profiles and build the browser export endpoint explicitly:

```bash
OTEL_SDK_DISABLED=false VITE_OTLP_ENDPOINT=http://localhost:4318 \
  docker compose --profile app --profile observability up -d --build
```

The default disables exporters when the collector is absent. Prometheus includes collaboration
alert rules in `ops/alerts.yml`; configure notification routing for a deployed monitoring stack.

---

MIT, see [`LICENSE`](LICENSE).
