# Collaborative Document Editor

Real-time collaborative text editing. Several people in one document, over a bad connection, typing
into the same paragraph.

![Two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

The merge algorithm is an RGA-style CRDT, written from scratch twice: Java on the server,
TypeScript in the browser. Formatting, comments and suggestions are built on the same core.

`docker compose --profile app up -d` starts everything on :5173. See [Running it](#running-it).

## Features

- **Editing** by any number of participants, ~130 ms propagation at 50 clients. Cursors and
  presence across instances.
- **Offline editing** into IndexedDB, surviving a tab close, draining on reconnect.
- **Undo** that takes back only your own operations.
- **Rich text**: six inline styles, plus headings, quotes and lists.
- **Comments** anchored to a range. Delete the text and the thread stays, marked detached.
- **Suggestions**: edits as proposals over the real text. Operations are generated server-side, so
  a suggester cannot write directly.
- **History**: time travel to any point, diff two versions by character identity, restore.
- **Access control**: workspaces, search, trash, four roles, invitation links, revocation that
  lands mid-session.
- **A diagnostics bar** in the app with latency, jitter and drop-rate sliders and a button that
  cuts the network. Open a document in two windows and break one of them.

## Architecture

![Two browsers and two backend instances, each holding an RGA replica, all appending to and replaying from one Postgres operation log](docs/diagrams/architecture.svg)

All four replicas are peers. Browsers run the same algorithm as the instances and merge by the same
rule; neither side is an authority. Instances also gossip over Redis, covered under
[Cluster](#cluster).

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

`RgaDocument` reaches the block list through an interface with two implementations. `ArrayBlockList`
is O(n) for everything; `TreapBlockList` is O(log n), a BST keyed by document order with heap
priorities for balance, each node carrying its subtree's visible and total length. Priorities are a
splitmix64 hash of the block identifier rather than random, so every replica builds the same tree
and a failing test reproduces.

Naive implementation first, then tests, then JMH, then the tree. The commit history shows that
order.

### Tombstones and GC

A tombstone cannot be dropped on a whim, because another replica may still hold an operation whose
origin is that character. The condition is causal stability: every replica that could still refer
to it has seen the deletion. The server takes the component-wise minimum of the version vectors
connected replicas report.

```
  A { 1:120, 2:88, 3:200 }   B { 1:118, 2:91, 3:200 }   C { 1:120, 2:88, 3:196 }
  meet: { 1:118, 2:88, 3:196 }
```

The test is on the **deletion**, not the insertion. An audit found it applied to the insertion,
which is only equivalent when nothing is deleted soon after being written. A paragraph written last
week and deleted a second ago has an insertion far below the meet, so its tombstone looked
collectable while a peer 10 ms behind still displayed the text. That is what `Block.deletedBy` is
for; `GcSafetyTest` pins it.

Two more things are held back, both found the same way: anchors of buffered operations, and every
character in a buffered delete range rather than just the first.

A replica offline past the GC window (7 days) is refused with `RESYNC_REQUIRED` and its local
version kept as a "Recovered draft".

Protected blocks drop their content and keep their identity. All three reasons to keep a tombstone
(origin, anchor, causal stability) need only the identifier, never the characters. Before that, GC
worked on whole blocks, so one anchored character kept a block alive, and text written in one run
is one block: a single mark could rescue an entire document from collection.

### Lamport clock notes

**The version vector does not deduplicate.** Per-replica counters are not contiguous, since the
clock jumps whenever someone else's operation arrives. Replica A legitimately produces lamports
1, 2, 47, 48, 103, and "everything from A up to 48" cannot distinguish a lost message from a jump.
An `Insert` is a duplicate if its identifier is already in the structure. A `Delete` is not checked
at all, since setting a tombstone twice is a no-op. Detecting gaps is the transport's job.

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

**Suggestions** do not modify the document or live in a side buffer. Typing in suggest mode inserts
real text carrying a `suggest-insert` mark; deleting adds `suggest-delete` over text that stays.
Accepting drops the mark. Rejecting generates the undo operations server-side. No new anchor
mechanism, no new merge rule, no change to the character layer.

## Undo

CodeMirror's own history is off. It works on text ranges, so Ctrl-Z would also take back what other
people typed.

A CRDT operation cannot be inverted, since a deleted character has a tombstone and does not come
back. Restoring text inserts new characters with new identifiers, the same visible result with a
different identity. Yjs does the same.

A stack entry stores a character identifier, not an offset. Undoing formatting is not "remove the
mark" either: the inverse of "make this bold" is "restore what this range had before", which
differs if somebody else's mark of the same type was already there. If the anchor has since been
deleted the entry is skipped rather than guessed at.

## History and diffs

The operation log in Postgres is the source of truth. Snapshots are a cache: deleting all of them
changes only load time, which `snapshotsAreOnlyACache` verifies. Time travel is a replay,
`GET /history/at?seq=`. Restore is recorded as new operations, so it appears in the history itself.

Diffs compare two versions by character identity, which answers something a text diff cannot:

```
  delete a word, then type the same word again

  text diff      "the quick fox" vs "the quick fox"  ->  no change
  identity diff  1@40..1@44 gone, 2@81..2@85 new     ->  a change, by a named author
```

Restore uses a common prefix and suffix rather than a full Myers diff. A restore is usually a
rollback of one recent edit, so the differing region is short.

## Offline and reconnect

Editing continues into an IndexedDB buffer that survives a tab close and drains on reconnect.

An operation leaves that buffer on the **write** acknowledgement, never the broadcast. If an
instance dies in between, the operation is on other screens but not in the log, and the client
retransmits.

The reverse failure is silent. A broadcast to one client is lost; nothing errors and the client has
a hole it cannot see. Watching `server_seq` does not help, because that cursor advances on
acknowledgements, which go to everyone, so it looks current while the operation is missing. So the
acknowledgement carries the operation's identity, and the client checks whether it actually handled
`7@41`.

That check started as "does my replica contain character 7@41", which is exact for inserts and
always false for deletes and marks, whose identifiers never name a character. One backspace by
anybody set the gap flag and the client resynchronised from zero every 15 seconds. It is a bounded
window of handled identities now.

![Client connection states, with the two exits: resynchronising on a detected gap, and a refusal past the collection window](docs/diagrams/connection-states.svg)

`Refused` is the only state a client cannot leave on its own. The heartbeat is a backstop; the
client reacts to the browser's `online`/`offline` events and socket errors, which fire immediately.

## Cluster

Every backend instance is an ordinary CRDT replica and a peer of the others and of the browsers. No
leader, no distributed lock, no session affinity.

Redis is a fast path with no delivery guarantee. A lost message costs latency, not correctness: the
log is authoritative and anti-entropy pulls the difference back. `ClusterChaosIT` pauses the Redis
container mid-write, and `redisOutageIsRepairedByAntiEntropy` covers the repair. A deterministic
simulation drives the same scenarios from a seed instead of thread interleaving, so failures come
back reproducible.

## Auth

Sign-in is federated through an OpenID Connect provider, so the application never sees a password.

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

The session lives in `localStorage`, once per browser. It used to be `sessionStorage`, per tab, so
two tabs could hold two people, which is the demo this project gets judged on. `?tab-session` keeps
that for the demo under `COLLABEDIT_DEV_LOGIN`.

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

![Every request passes one interceptor: a handler declaring no access requirement is answered with 500 and never runs](docs/diagrams/authorization.svg)

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

| Layer | Tests |
|---|---:|
| `crdt-core`: property, invariants, conformance vectors, snapshot bytes, marks, GC safety | 87 |
| `app`: integration on real Postgres and Redis, cluster chaos, permissions, presence, architecture | 227 |
| `frontend`: agreement with Java, CodeMirror bridge, snapshot decoding, undo, gap detection | 134 |
| end-to-end: two browser contexts, failing on any uncaught console error | 21 |
| **total** | **469** |

The deterministic cluster simulation is counted separately. Its 1207 is a count of seeds, not
assertions.

Property tests of the algorithm come first, then integration tests against real Postgres and Redis
via Testcontainers, then chaos and seeded simulation, then ordinary unit tests. The load harness,
JMH and the fan-out measurement run nightly.

I also mutate production code to check the suite goes red. That found three tests verifying
nothing:

- Removing the Lamport clock bump left all fifteen property tests green. See
  [Lamport clock notes](#lamport-clock-notes).
- Removing the treap's aggregate update after a tombstone passed convergence, because every replica
  was wrong identically. The text agreed and only the positions were wrong.
- `garbageCollectionSparesMarkAnchors` passed with its mechanism off. The mark was anchored
  mid-text where an older rule already protected the character.

## Performance

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
tree has worse locality than iterating an array. That runs once per document open, lookup runs on
every keystroke.

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

All twelve converge. Document size barely affects median latency. Repeat runs reproduced the table
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
After raising it, all twelve scenarios converge and `BroadcastFanoutIT` gives 3/3 at 2, 10, 25 and
50 clients.

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
| **Client offline past the GC window** | Refused, and the local version is kept separately. | `staleOperationIsRejectedExplicitly` |
| **Very large document** | Latency barely moves. Tombstones grow with edit count. | JMH, load harness |
| **Rate limit exceeded** | Frame refused with the exact wait, client retries from its buffer. | harness: 351 rejections, zero lost operations |
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
- **Invitations are multi-use** until they expire or are revoked. Now that every user arrives with
  a verified email, binding an invitation to an address is the obvious next step.
- **`link` visibility grants edit to anyone with the address**, which is the intended meaning. It
  used to be worse: the document list published those URLs to every signed-in user, so 132 of 133
  documents on a dev instance were world-editable.
- **GC reclaims less than I assumed.** Causal stability needs every replica to have seen the
  deletion, so one client that closed a laptop holds the window open for everyone in that document.
- **A cluster message for a document whose replica is still loading is dropped**, and anti-entropy
  cannot find it afterwards, because the fresh replica is labelled at log head so
  `head > appliedServerSeq` never fires. A client there stays permanently one operation behind.
  Reproduced under load, not fixed.

## Running it

JDK 21, Node 22.12+, Docker.

```bash
docker compose --profile app up -d          # everything, on http://localhost:5173
```

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
| `COLLABEDIT_AUTH_SECRET` | none, refuses to start | Signs session tokens. No fallback on purpose. |
| `COLLABEDIT_DEV_LOGIN` | `false` | Name-only login. 404 when off. |
| `GOOGLE_CLIENT_ID` / `_SECRET` | unset | Enables the Google button. Both halves needed. |
| `GITHUB_CLIENT_ID` / `_SECRET` | unset | Same, for GitHub. |
| `COLLABEDIT_APP_URL` | `localhost:5173` | Where sign-in returns to. |
| `COLLABEDIT_PORT` | `8090` | Must match the registered redirect URI. |

With no provider and no dev login, the login screen says there is no way in. Register the redirect
URI as `http://localhost:8090/login/oauth2/code/{google,github}` — that path is Spring Security's,
not ours, and pointing it at the app is the usual first mistake. `cp .env.example .env` works too.

```bash
./mvnw install                         # everything, integration tests on real Postgres
./mvnw -pl crdt-core -Pthorough test   # 10 000 property tries, as nightly
cd frontend && npm test
cd frontend && npm run e2e             # two browsers, needs a running stack
```

A failing property prints its seed; `@Property(seed = "...")` replays it. On colima, Testcontainers
needs `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` and
`api.version=1.44` in `~/.docker-java.properties`. All three failures look like "no Docker".

For deployment, the frontend resolves the backend at build time
([`config.ts`](frontend/src/config.ts)) and defaults to its own origin with nginx proxying `/api`
and `/ws`, since an `https:` page cannot open a `ws:` socket. Separate origins need `VITE_API_BASE`
at build time and `COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` on the backend.

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

Point it at a backend started from Maven, not `--profile app`: the containerised one sets
`OTEL_SDK_DISABLED`, so a browser span arrives with no server span to join.

---

MIT, see [`LICENSE`](LICENSE).
