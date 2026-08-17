# Collaborative Document Editor

Real-time collaborative editing: several people in one document, over a bad connection, typing into
the same paragraph.

![Two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

*Concurrent editing, a network cut, merge on reconnect, a rollback. Recorded from
[a script](frontend/e2e/demo.spec.ts) CI runs on every PR, so it breaks the build if the scenario
breaks.*

The merge algorithm is an RGA-style CRDT, written from scratch twice: Java on the server,
TypeScript in the browser. A browser and a server that order two concurrent inserts differently
will split a document permanently, and having written both is how I keep them honest about it.
Formatting, comments and suggestions are built on that same core.

`docker compose --profile app up -d` starts the whole thing on :5173; [Running
it](#running-it) covers developing against it.

## Features

- **Editing**: unlimited participants, ~130 ms propagation at 50 clients, offline into IndexedDB
  that survives the tab closing, undo that takes back *your* operations only, cursors and presence
  across instances.
- **Rich text**: six inline styles plus headings, quotes and lists, as marks over a flat sequence
  instead of nodes in a tree.
- **Comments**: threads anchored to a range; delete the text and the thread stays, marked detached.
- **Suggestions**: edits as proposals over the real text, with the operations generated server-side
  so a suggester can never write directly.
- **History**: time travel to any point in the log, diff two versions *by character identity*,
  restore recorded as new operations.
- **Access**: workspaces, search, trash, four ordered roles, hashed invitation links, revocation
  that lands mid-session on every instance.
- **A diagnostics bar** in the app itself: sliders for latency, jitter and drop rate, and a button
  that cuts the network outright. Open a document in two windows, degrade one of them and watch
  them still agree. It is how the demo GIF above is recorded, and the fastest way to convince
  yourself the merge works without reading any of this.

## Architecture

![Two browsers and two backend instances, each holding an RGA replica, all appending to and replaying from one Postgres operation log](docs/diagrams/architecture.svg)

All four replicas are peers: the browsers run the same algorithm as the instances and merge each
other's operations by the same rule, and neither side has authority over the other. Instances also
gossip over Redis, which [the cluster](#the-cluster) explains. It is out of the diagram because it
only affects latency.

CodeMirror 6 is the view and nothing else. A document model of its own, which is what Slate or
ProseMirror would bring, would be a second source of truth next to the CRDT and the two would have
to be reconciled on every keystroke. Raw `contenteditable` was the other option, and is a
well-documented way to lose a week to browser differences. So the editor library holds no state: it
renders what the CRDT says and reports keystrokes back.

```
crdt-core/     the algorithm, as a Maven module with zero Spring dependencies, so it
               can be benchmarked in isolation and the compiler stops the application
               layer leaking in

app/           Spring Boot 3, Java 21
  ws/          STOMP, presence, per-frame authorization, rate limiting
  auth/        roles, permissions, invitations, tokens
  domain/      the in-memory document replica
  persist/     operation log, snapshots, catalog, search
  history/     time travel, diff, restore
  comments/    threads and messages (content outside the CRDT)
  suggestions/ accept and reject, server-side operation generation
  cluster/     Redis pub/sub, anti-entropy, permission invalidation

frontend/      React + TypeScript + Vite, CodeMirror 6 as the view only
  crdt/        the second RGA implementation + mark layer + undo
  transport/   STOMP client, offline buffer, gap detection, network simulator
  editor/      the CodeMirror 6 bridge, formatting, remote cursors
  ui/          document list, history, comments, suggestions, sharing

conformance/   vectors and the mark-type table, shared by both implementations
ops/           Prometheus / Tempo / Grafana / OTel Collector
```

![A keystroke painted locally, sent to an instance, merged, broadcast, published over Redis, appended to the log, and acknowledged by identity, with the outbox releasing on the write, not the broadcast](docs/diagrams/edit-path.svg)

The two implementations are kept in step by a shared corpus in [`conformance/`](conformance/):
operation sequences with their expected text and formatting, generated once and replayed by both
suites. Change the ordering rule in one language and the other language's build fails.

It has a hole. The vectors carry operations that already exist, so a rule applied at *creation*
time, like "does bold grow when you type at its end", changes no vector and no vector can catch it.
That rule lives in a JSON file both sides read, with a test each way. The second direction, from the
declared constants back to the file, exists because I declared a mark type in Java, forgot it in the
JSON, and both suites stayed green.

## How the merge works

Sending positions (*insert "!" at index 2*) breaks the moment anything is inserted earlier, because
by the time the message lands, index 2 means somewhere else. Every position-based approach ends up
rewriting other people's messages to compensate. That is Operational Transformation, and doing it
correctly for N peers with no server is famously hard.

So we do not send positions. Each character gets an identifier at creation and keeps it for life,
and an insert names the character it goes **after**:

```
Id = (replicaId, lamport)                       printed as  1@7
Insert { id: 1@7, originLeft: 1@6, text: "!" }
```

`lamport` is a Lamport clock, bumped on every local edit and to `max(local, incoming + 1)` when
someone else's operation arrives. `originLeft` names something that already happened, so no later
edit can invalidate it.

**Ordering two concurrent inserts.** Find the origin, walk right while the block you meet has a
higher identifier than the one being inserted, stop at the first lower one. Identifiers compare by
`(lamport, replicaId)`, a total order, so it can never tie.

```
  both replicas hold "ok"; neither has seen the other
  Ann inserts 1@5 "!" after k        Bo inserts 2@5 "?" after k

  Ann receives 2@5:  is 1@5 > 2@5? no  -> stop, giving  o k ? !
  Bo  receives 1@5:  is 2@5 > 1@5? yes -> skip, then end of list, giving  o k ? !
```

Same result from opposite delivery orders, with no messages rewritten and nothing rolled back.
Higher identifiers sort left, which is arbitrary, but both implementations have to choose the same
way. Termination rests on the clock being a real Lamport clock: anything created causally after a
block has a higher timestamp, so skipping a block skips its whole subtree, and the scan stops on its
own.

**The document is a list of runs, not characters.** One object per character is wasteful, and worse,
it means one operation per keystroke. Each block holds a whole run:

```
  Block{1@1, "Hello "}  Block{1@7, "world"}

  delete "wor": a block is entirely alive or entirely dead, so it splits first

  Block{1@1, "Hello "}  Block{1@7, len 3, deleted by 2@40}  Block{1@10, "ld"}
                                          ^ WHICH operation deleted it.
                                            GC tests this, not the insert.
```

A block is entirely alive or entirely deleted, so deleting part of one splits it first; a
per-character flag array complicates every index operation and saves little. The insertion scan is
coarse, by block: lamports inside a block increase and its characters are causally chained, so if
the first outranks the incoming one, so does every other. Typing appends into the previous block
when replica, lamport and origin all line up, and without that fast path run-length encoding buys
nothing. A deleted block keeps its identity and length so it can still serve as an origin.

<details>
<summary>Two index implementations, and the property that caught what comparing text could not</summary>

`RgaDocument` goes through a `BlockList` interface, and there are two:

| | Insert a block | Find by offset | Find by identifier |
|---|---|---|---|
| `ArrayBlockList` | O(n) | O(n) | O(n) |
| `TreapBlockList` | O(log n) | O(log n) | O(log n) |

The treap is a BST keyed by document order with a heap priority for balance, every node carrying its
subtree's visible and total length so a position lookup can descend instead of scan. The priority is
a splitmix64 hash of the block's identifier, not a random number, so every replica independently
builds the same tree shape and a failing test is reproducible rather than merely likely.

Both pass the same property suite, fed the same random sequence and compared step by step. The one
that has actually caught something is `bothImplementationsAnswerQueriesIdentically`: a mutation left
the *text* identical in both and only the *positions* wrong, so the document looked healthy with
everybody's cursor in the wrong place.

Naive implementation first, then the tests, then JMH, and only then the tree. The commit history
shows that order, which I would rather have than a clever structure dropped in on day one.

</details>

### Tombstones, and when one can be dropped

A tombstone can never be deleted on a whim, because another replica may still hold an operation
whose origin is that character. The condition is **causal stability**: every replica that could
still refer to it has already seen the deletion. The server keeps the version vector each connected
replica reports and takes the component-wise minimum.

```
  A { 1:120, 2:88, 3:200 }   B { 1:118, 2:91, 3:200 }   C { 1:120, 2:88, 3:196 }
  ---------------------------------------------------------- component-wise minimum
  the meet  { 1:118, 2:88, 3:196 }
```

The test is on the **deletion**, not the insertion. An audit found it applied to the insertion,
which is the same thing only when nothing was ever deleted quickly: a paragraph written last week
and deleted a second ago has an insertion far below the meet, so its tombstone was collectable
immediately while a peer 10 ms behind still displayed the text. That is what `Block.deletedBy` is
for, and `GcSafetyTest` pins the scenario.

Two more things are held back, both found the same way: anchors of buffered operations (collecting
one stranded the operation in the pending buffer for the life of the process, and the formatting
silently vanished), and every character in a buffered delete range, not just the first.

A replica offline longer than the GC window (7 days) is refused with `RESYNC_REQUIRED` and its local
version kept as a separate "Recovered draft". Nothing sensible can be merged there, because the
information about where those characters belong is gone.

### Two things I got wrong about the clock

**The version vector does not do deduplication.** The reflex is to reject anything already seen, but
per-replica counters are not contiguous: the clock jumps whenever someone else's operation arrives,
so replica A legitimately produces lamports 1, 2, 47, 48, 103, and "everything from A up to 48"
cannot tell a lost message from a jump. An `Insert` is a duplicate if that identifier is already in
the structure, which is exact and free because the structure indexes by identifier. A `Delete` is
not checked at all, since setting a tombstone twice is a no-op. Detecting gaps in delivery is the
transport's job.

**The clock does not buy convergence.** These notes used to claim, with a counterexample, that
without bumping it on receipt the implementation diverges. That was too strong and I have deleted
it. I removed the bump and ran 200 000 random scenarios, 3-5 replicas, 30 edits each, every
operation delivered twice: zero divergences. The integration function is deterministic with respect
to the *set* of elements present, whatever order they arrive in.

The bump stays anyway. It is a premise of the published RGA proof, 200 000 scenarios are evidence
rather than proof, and it does guarantee that a concurrently typed run stays contiguous. Nothing was
checking that either; removing the bump left every property test green. Three lines in
`RgaInvariants` assert it now.

## Anchors: one mechanism, four features

Cursors, formatting, comment highlights and suggestions are all "a place in the document that has to
survive other people typing", so they are the same object: `Anchor { charId: Id, side: BEFORE |
AFTER }`. Not an offset. An offset stops pointing at the same place the moment somebody inserts text
earlier; an anchor is attached to a character, so it moves with the text.

### Which side an anchor sits on decides how formatting behaves

An anchor "after X" and one "before Y" point at the same gap in `...XY...` until somebody inserts
between X and Y. That difference is what decides whether formatting grows:

```
  a mark ends between b and c in "abcd", then somebody types X into that gap:

    end = after(b)   ->   a b | X c d     X is outside, the mark does not grow
    end = before(c)  ->   a b X | c d     X is inside, the mark grows over it

  bold ends before(c)  ->  typing at the end of bold text is bold
  link ends after(b)   ->  typing after a link does not join the link
```

One field, not a separate "should this expand" flag, which would store the same decision in a second
place and let the two disagree. Which types expand lives in
[`conformance/schema/mark-types.json`](conformance/schema/mark-types.json), read by both
implementations, with a test on each side that fails if they drift. Unknown types default to not
expanding, because the failure is asymmetric: a mark that expands when it should not restyles text
nobody formatted, while one that fails to expand costs a repeated keystroke.

### Formatting is a layer, not a character attribute

Marks are a separate CRDT layer over the character layer, in the style of Peritext. The character
layer never gained a field for bold, or comments, or suggestions.

![Bolding a range, typing inside it, commenting on a selection, deleting anchored text, restoring from the bin](docs/demo-v2.gif)

*Anna bolds a fragment; Marek, who has not received it yet, types inside it, and the formatting
covers the new text instead of leaving a hole. Then a comment on a selection, deleting the anchored
text, and a restore from the bin.*

```
  Ann bolds "llo" in "Hello world". Marek, who has not received the mark, types "XX" inside it.

  a range between anchors        a per-character flag
  He[llXXo] world                H e l l X X o   w o r l d
     bold covers XX                  B B _ _ B
                                           ^ the hole is permanent
```

Resolving marks to spans is a sweep over the block list. "The sweep agrees with itself on every
replica" is a test that passes when the sweep returns nothing at all, so it is checked against
`NaiveMarkResolver`: an independent, obviously correct, far too slow implementation. Replacing
`MarkSet.spans` with `return List.of()` left all four of the original mark properties green, and the
oracle catches it immediately.

Twelve mark types share the mechanism: six inline styles, `comment`, two suggestion types, and
`heading` / `quote` / `list`.

A **comment** is half in and half out. The anchor is a CRDT operation; the conversation is rows in
Postgres. A range has to merge without an arbiter under concurrent edits, but a sentence written by
one person has a single author and an obvious order, so running it through a merge algorithm buys
nothing and costs a lot. Two consequences are easy to miss: the anchor is created *after* the
thread, because it needs the thread's id, so a thread briefly exists with no anchor; and the
highlight arrives over the CRDT while the conversation arrives over REST, so expect the highlight a
beat early. Offline, a comment goes to an outbox with its anchor and an idempotency key, because
this path needs exactly-once rather than merging.

A **suggestion** does not modify the document and does not live in a side buffer. Typing in suggest
mode inserts real text carrying a `suggest-insert` mark; deleting adds a `suggest-delete` mark over
text that is still there. Accepting drops the mark, and rejecting generates the undo operations
server-side, so a role that may suggest can never write directly whatever the client sends. That
needed no new anchor mechanism, no new merge rule and no change to the character layer, which is
most of the argument for making the mark layer general.

## Undo in a shared document

CodeMirror's own history is switched off. It works on text ranges, so Ctrl-Z would take back
whatever other people typed in the meantime, which in a shared editor means silently deleting a
colleague's paragraph.

Undo therefore operates on your own operations, and a CRDT operation cannot be inverted: a deleted
character has a tombstone and does not come back to life. Restoring text means inserting *new*
characters with new identifiers, so the visible effect is the same and the identity is not. Yjs does
the same thing. A stack entry remembers a character identifier, because an offset recorded at edit
time is stale by the time you press Ctrl-Z. Undoing formatting is not "remove the mark" either: the
inverse of "make this bold" is "restore whatever this range had before", which differs when somebody
else's mark of the same type was already there. If the anchor has since been deleted the entry is
skipped rather than guessed at, because text put back roughly there appears somewhere random.

## History, and a diff by identity

The operation log in Postgres is the source of truth. Snapshots are a disposable cache: deleting
every snapshot changes only how long a load takes, and `snapshotsAreOnlyACache` does exactly that.
Time travel is then a replay, `GET /history/at?seq=`, and a restore is recorded as new operations
rather than a rewrite, so the restore itself shows up in the history.

The diff endpoint compares two versions by character identity, which answers a question a text diff
cannot:

```
  delete a word, then type the same word again

  text diff      "the quick fox" vs "the quick fox"  ->  no change
  identity diff  1@40..1@44 gone, 2@81..2@85 new     ->  a change, by a named author
```

Restore uses a common prefix and suffix, not a full Myers diff: a restore is usually a rollback of
one recent edit, so prefix/suffix is short and cheap, and Myers would produce smaller operations at
a cost in complexity not worth paying yet.

## Offline, reconnect, and the gap you cannot see

Losing the connection is the normal case. Editing continues into an IndexedDB buffer that survives
the tab closing, and drains on reconnect.

The part that took thought is when an operation may leave that buffer. The release is on the
**write** acknowledgement, not the broadcast. If an instance dies in between, the operation is on
other people's screens but not in the log, and because the buffer never released it, the client
retransmits.

The reverse failure is harder, because it is silent. A broadcast to one client is lost; nothing
errors, and the client simply has a hole and does not know it. Watching `server_seq` for gaps does
not help, because the cursor advances on acknowledgements, which go to everybody, so it looks
current while the operation is missing. So the acknowledgement carries the operation's **identity**,
and the client asks whether it actually handled `7@41` rather than whether its cursor has passed it.

That check started as "does my replica contain a character 7@41", which is exact for inserts and
always false for deletes and marks, since their identity comes from the same clock but never names a
character. So one backspace by anybody set the gap flag, and the client resynchronised from zero
every 15 seconds for ever. The repair became the load. It is a bounded window of handled identities
now.

![Client connection states, with the two exits: resynchronising on a detected gap, and a refusal past the collection window](docs/diagrams/connection-states.svg)

`Refused` is the only state the client cannot leave on its own; everything else recovers, including
`Offline`. The heartbeat interval is not what the user feels either: the client listens for the
browser's `online`/`offline` events and for socket errors, which fire immediately, and the broker's
timeout is a backstop for a socket that has died without saying so.

## The cluster

Every backend instance is an ordinary CRDT replica, a peer of the other instances and of the
browsers. There is no leader, no distributed lock and no session affinity.

Redis is a fast path and nothing more; it makes no delivery guarantee. A lost message costs latency
rather than correctness, because the log is authoritative and the anti-entropy sweep pulls the
difference back. `ClusterChaosIT` pauses the Redis container mid-write to check that, and
`redisOutageIsRepairedByAntiEntropy` checks the repair specifically. There is also a deterministic
simulation of the whole cluster, driven from a seed instead of thread interleaving, so a failure
comes back with the seed that reproduces it instead of "sometimes". The cost of having no owner is
in [Limitations](#limitations): a document open on three instances lives in three copies.

## Who you are

Authorization is only as good as the identity under it, and for most of this project's life there
was none. `POST /api/login` took a name, minted a fresh `UUID`, handed back a token and checked
nothing, so "Anna" today and "Anna" tomorrow were different people. The role model on top was fine,
but `link` visibility grants EDITOR to "anyone signed in", and signing in cost nothing, so in
practice it granted EDITOR to anybody who asked.

Identity is federated now. An OpenID Connect provider vouches for the person, and this application
never sees a password.

```
browser --1--> /oauth2/authorization/google --> server --2--> Google
                                                               |
         <--4-- redirect ?code=... <-- server <--3-------------+
app      --5--> POST /api/session {code} --> server --> session token
```

**The identity is `(provider, subject)`, never the email.** `subject` is the provider's own
identifier and the only field OpenID Connect promises never changes. An email can be changed by its
owner and, at some providers, released and reassigned, so keying on it would eventually hand one
person another's documents. The email is stored and displayed, and nothing keys off it.

**Step 5 exists so the token never travels in a URL.** The provider redirects to the *server* and
the token is needed by the *application* on another origin, so something has to cross in a redirect,
and a redirect target lands in browser history and in the `Referer` of whatever loads next. What
crosses is a single-use code: 192 random bits, stored hashed, dead on first use, expired after a
minute. A token in the URL fragment stays out of the `Referer` but is still in `location`, readable
by any script on the page; a cookie attaches the token to requests the application never made, which
is the CSRF surface the design was avoiding.

The session lives in `localStorage`, once per browser. It was `sessionStorage`, per tab, so two tabs
could hold two people, which is the demo a collaborative editor gets judged on. `?tab-session` keeps
that behaviour for driving the demo under `COLLABEDIT_DEV_LOGIN`.

That forced a sign-out control to exist, since closing the tab had been the sign-out. Three related
holes came out of the same review:

- A 401 was noticed in exactly one place, the document-list bootstrap. Everywhere else the user got
  whatever that screen says when a request fails, and the editor's version told them to ask a
  colleague to re-share a document the colleague had not touched. Every REST call routes through one
  handler now.
- The WebSocket authenticates once, at `CONNECT`, and was never re-checked, so a lapsed session kept
  working until the socket dropped, then reconnected with the same dead token, was refused, and
  retried behind "Working offline" for ever.
- Signing out could not be followed by signing in as somebody else. Google gets
  `prompt=select_account` now; GitHub has no equivalent, so switching there happens at GitHub.

## Authorization

Two doors, HTTP and WebSocket, with one `PermissionService` behind both. The HTTP rule is: declare
what access you need, or your handler does not run.

![Every request passes one interceptor: a handler declaring no access requirement is answered with 500 and never runs](docs/diagrams/authorization.svg)

1. **The default branch refuses,** so a new controller fails closed and says so. Before that,
   `HistoryController` spent weeks handing out the full content of any document and letting a reader
   overwrite it. 500 rather than 404 is deliberate: this is our bug, not a caller doing anything
   wrong, and it should read that way in a log.
2. **The document id comes from the declaration**, not from wherever the handler happens to look,
   which is why creating a comment is `POST /api/documents/{id}/comments` with the document in the
   path. A mechanism that resolves its subject from a declaration cannot be pointed at an endpoint
   keeping its subject in the body.
3. **The build enforces the same rule as the runtime.** An undeclared mapping fails
   `EndpointAuthorizationArchitectureTest`, and `publicEndpointsAreExactlyTheExpectedOnes` pins the
   exemption list by name. Without the test the rule is something you discover by breaking
   production; without the guard it is a convention anyone can drop.

The guard checks that a decision was *made*, not that it was the *right* one:
`@RequiresDocumentAccess(READ)` on something that writes is declared, consistent and wrong.

On the WebSocket side, `DocumentAccessInterceptor` checks every SEND and SUBSCRIBE frame, asking not
"do you have access to the document" but "does your role permit *this* operation", because comments
and edits travel the same channel. An unresolvable destination is refused, which it was not at
first: Spring's simple broker matches subscription destinations as Ant patterns, so
`SUBSCRIBE /topic/**` resolved to no document, passed unchecked as if it were a private queue, and
then matched every document's broadcast.

## Tests

| Layer | Tests |
|---|---:|
| `crdt-core`: property, structural invariants, conformance vectors, snapshot bytes, marks, GC safety | 87 |
| `app`: integration on a real Postgres and Redis, cluster chaos, permissions, presence across instances, the architecture test, the frame interceptor | 227 |
| `frontend`: agreement with the Java implementation, the CodeMirror bridge, snapshot decoding, undo, gap detection, the offline store | 134 |
| end-to-end: two browser contexts, every spec failing on an uncaught console error | 21 |
| **total** | **469** |

The deterministic cluster simulation is counted separately: 1207 is a count of *seeds*, not of
distinct assertions, so adding it to the total would inflate the number.

Priority order is property tests of the algorithm, then integration tests against a real Postgres
and Redis through Testcontainers instead of mocks, then chaos and seeded simulation, and only then
ordinary unit tests. Load harness, JMH and the fan-out measurement run nightly, since they take
minutes and measure the machine as much as the system.

The tests get checked too. I mutate production code and check the suite goes red, which has found
three tests that verified nothing:

- Removing the Lamport clock bump left all fifteen property tests green, even though catching
  exactly that was why several were written.
  [Full story above.](#two-things-i-got-wrong-about-the-clock)
- Removing the treap's aggregate update after a tombstone sailed through convergence, because every
  replica was wrong in the same way. The text agreed; only the positions were wrong.
- `garbageCollectionSparesMarkAnchors` passed with its mechanism switched off. I had anchored the
  mark mid-text, where an older rule already protected the character, so the test was checking the
  old rule and crediting the new one.

In all three the test passed because of some other mechanism, and the only way to find that out was
to break the one it named.

## Performance

One machine: MacBook, Apple Silicon (aarch64), 10 cores, JDK 25-ea, 4 GB heap, with Postgres and
Redis in containers alongside. A number without its machine is useless, so it is stated next to
every table. None of them are stamped with the date they were run, which I keep meaning to fix.

One caveat applies to every millisecond here. The same code, same marks, same machine measured
0.28 ms for 100 marks when the test ran alone and 0.039 ms inside the full suite: a sevenfold
difference from nothing but JIT warm-up. Absolute times on this rig are comparable only against
other times measured the same way, which is why each table says how it was run. It is also why
there is no millisecond threshold in CI.

### Index structure

JMH (`./mvnw -pl crdt-core test-compile exec:exec@run-benchmarks`), average operation time, 3
warm-up and 5 measurement iterations of 2 s.

| Operation | Document | `ArrayBlockList` | `TreapBlockList` | Difference |
|---|---:|---:|---:|---:|
| Position to identifier | 1 000 | 0.367 µs | 0.065 µs | 5.6x |
| | 10 000 | 5.510 µs | 0.149 µs | 37x |
| | 100 000 | **60.2 µs** | **0.286 µs** | **210x** |
| Identifier to position | 1 000 | 0.187 µs | 0.068 µs | 2.7x |
| | 10 000 | 2.863 µs | 0.122 µs | 23x |
| | 100 000 | **40.3 µs** | **0.210 µs** | **192x** |
| Full insertion | 100 000 | 684 µs | 4.66 µs | 147x |
| Reading the whole text | 100 000 | 310 µs | 429 µs | **0.72x (treap slower)** |

The shape matters more than the values: for a tenfold larger document the naive scan grows 15x and
11x, the treap 2.3x and 1.9x. Reading the whole text is 40% slower in the treap, and that is not
measurement error: walking a tree has worse locality than iterating an array. That runs once per
document open while lookup runs on every keystroke, so the trade is worth making, and the row is
here because it is still a trade.

### Propagation between clients

A custom harness rather than Gatling, for the reason recorded in `LoadHarnessIT`: the quantity of
interest is the gap between events at two *different* clients, so both ends must share one clock.
Clients and server run in one process and network latency is ~0, so these describe the cost of the
system, not the experience of a user over the internet. Each client makes 40 insertions at the
*start* of the document, since typing continuously compresses into one block and bypasses the path
that scales with size.

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

All twelve converge. Document size barely affects median latency; after the treap, the data
structure stopped being the bottleneck. Repeating the table reproduced it within noise except for
p50 at 10 000 characters and 10 clients, which came out 28 ms once and 43 ms another time. That
spread is the honest measure of how much to trust the second significant digit.

The fat p99 tails are the price of rate limiting. The harness seeds the 100 000-character document
with two hundred operations sent without pause, which exhausts the limiter's burst. Nothing is
lost, the number is worse, and it stays.

<details>
<summary>My first explanation of that tail was wrong, and fixing what I blamed made it worse</summary>

It used to read: *"rejected operations are retried after a second, and that second is what the p99
shows"*. Reasonable-sounding: the client waited a hardcoded 1000 ms while the bucket refills at
60/s, so the real time to one token is ~17 ms, and a sixtyfold overestimate looks like an obvious
cause. So I made the server send back the exact wait, which the bucket knows anyway.

| 100 000 chars, 50 clients | p99 | rejections |
|---|---:|---:|
| before (fixed 1 s) | 872 ms | 1523 |
| `retry-after` alone | **1352 ms** | 1614 |
| `retry-after` + exponential backoff | 826 ms | 1368 |

**The server-supplied wait alone made the tail 55% worse.** The server states the time to one
token, while the client holds dozens of operations in its buffer, so resending the whole buffer
every 17 ms is busy-polling: it creates no tokens and does create rejections. Exponential backoff
removes the regression, and that is where the gain ends, since 826 against 872 ms sits inside this
rig's spread. What actually bounds the tail is the refill rate times the queued batch: a client
with N operations behind an empty bucket waits N/60 seconds however often it asks.

What the change does buy, since it does not buy latency: a guessed constant leaves the client, the
server states a value only it knows, and rejections drop ~10%. It is recorded because the claim
"it is that one second" was published here and was untrue.

</details>

The harness also generates the full traffic mix. Earlier runs sent text operations only, no
presence and no formatting, so they described a lighter system than the browser produces. The
cursor is the most frequent event in the system and was not being sent at all. It now fires every
other keystroke, and formatting every tenth edit.

### The cost of the mark layer

Every mark pins two tombstones, so formatting can block the garbage collector. Measured by
[`MarkCostMeasurement`](crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java): 1000
characters, all text deleted, 100 marks (200 anchors), GC with a fully stable version vector.

| How the text was written | Without marks | With marks (before) | With marks (after) |
|---|---:|---:|---:|
| in one run (1 run-length block) | 1000 | **0** (0%) | **1000** (100%) |
| character by character (1000 blocks) | 1000 | 800 (80%) | **1000** (100%) |

The middle column is what forced the change, and it was worse than predicted. GC worked on blocks,
not characters, so a block with even one anchored character stayed whole; text written in one run
is one block, so a single anchor rescued the entire document. The predicted cost was two tombstones
per mark. The real cost was one whole block per mark, and block size depends on typing history, not
on formatting.

The fix was taking literally a sentence that had been in the tombstone design from the start. All
three reasons to keep a tombstone (being an origin, being an anchor, causal stability) need a
character's **identity**, never its content. So a protected block now drops its content and stays:
it keeps its identifier, length and position, and loses only characters nobody can see. The mistake
was never in the protection conditions, but in treating "keep the identity" and "keep everything"
as one requirement.

That cost one bit per block in the snapshot format, version 2 to 3, and the migration was free: a
snapshot in an unknown version is ignored rather than migrated, so state rebuilds from the log.
What it does not fix is the block objects themselves, which still accumulate in a long-edited
document, and that is now the dominant remaining cost.

<details>
<summary>The snapshot sizes published here were a property of the test input, not the format</summary>

The line used to read "one bit per block: **107 B to 108 B** at 50 000 characters". A hundred and
seven bytes for fifty thousand characters is not a fact about the snapshot format.
`MarkCostMeasurement` built its document by repeating a single character and `SnapshotCodec` gzips
the result. Gzip of 50 000 identical characters is around 80 bytes; of 50 000 characters of prose,
around 25 000. Roughly 300x, all of it a property of the input.

The same file already named that exact mistake about a different measurement further down, and the
lesson never came back to correct this one. Two paragraphs sat in one document, one explaining why
the other was wrong.

Re-measuring on prose then found a second version of the same error. The first attempt used a cycle
of thirty-four words and reported 706 B, a ratio of 71:1 where real English gzips at roughly 3:1: a
short repeating vocabulary sits entirely inside the compressor's window.

| 50 000 characters | snapshot | ratio |
|---|---:|---:|
| `"x"` repeated (what used to be published) | 107 B | ~600:1 |
| thirty-four words cycled (first attempt) | 706 B | 71:1 |
| **eighty words, pseudo-random order** | **10 901 B** | **4.6:1** |

`MarkCostMeasurement.prose` now draws from an eighty-word vocabulary in deterministic splitmix64
order and prints its own compression ratio next to the byte count, because a number carrying its
caveat is quotable and one needing a footnote is not. With 500 marks over that text the snapshot is
15 779 B, so the per-mark overhead is **9 B** rather than the 8 B measured through degenerate
input. The delta was the part that was never wrong, since a difference between two snapshots of the
same text cancels the text out. Still not real English at 4.6:1 against roughly 3:1, so the
baseline remains an underestimate, which is why the ratio is printed rather than described.

</details>

### Resolving marks, on every keystroke

Computing appearance on a 50 000-character document, median of 11 samples:

| Marks | `O(m²)` before | sweep after | Speed-up |
|---:|---:|---:|---:|
| 100 | 0.39 ms | 0.27 ms | 1.5x |
| 400 | 0.58 ms | 0.49 ms | 1.2x |
| 1600 | 4.18 ms | 1.49 ms | 2.8x |
| 6400 | **62.1 ms** | **4.72 ms** | **13x** |

The old version walked every mark at every segment; the segments are now walked left to right with
the active set maintained incrementally, each range entering and leaving once. The usable limit
moved from hundreds of marks to thousands.

Two things that measurement showed are more interesting than the speed-up. At 100 and 400 marks
there is practically no difference, because below roughly a thousand the dominant cost is resolving
anchors through the block index, two lookups per mark: the complexity fix starts paying exactly
where the limit already was, and not a bit earlier. And the previous measurement was far too noisy
to compare anything with, timing a single call and giving 0.8 ms once and 4.7 ms another time at
400 marks.

<details>
<summary>A row of that table was measuring two things at once</summary>

It also carried a "growth per 4x marks" row, quoting x3.1 to x3.2 for the sweep. That row is gone,
because the series it summarised was not comparable with itself. Marks were laid every
`50000 / markCount` characters and each covered a fixed 30, which is fine while the stride exceeds
30 characters. At 6400 marks the stride is 7, so ranges overlapped four to five deep and, all
carrying the same type and value, `mergeAdjacent` folded the document into one span. The last row
measured
6400 ranges to 1 span while the row above measured 1600 ranges to 1600 spans, and the label said
only "x4 marks". The row had been added precisely to make the complexity difference visible, and
was the one row that was not comparable.

The before/after columns stand, since both implementations were fed identical input, so the 13x at
6400 is real. What does not stand is reading a complexity *shape* off that series. Re-measured with
width capped at `stride - 1` so ranges stay disjoint:

| Marks | Spans | Resolve | Growth per 4x marks |
|---:|---:|---:|---:|
| 100 | 100 | 0.28 ms | |
| 400 | 400 | 0.48 ms | x1.7 |
| 1600 | 1600 | 1.43 ms | x3.0 |
| 6400 | 6400 | 3.27 ms | x2.3 |

Every ratio is at or below x4 for a x4 in marks, so sub-linear in this range, because a fixed cost
independent of mark count still dominates. `O(m²)` would predict x16. The test asserts
`spans == markCount` at every step, so overlapping ranges now fail as a broken measurement rather
than reading as a complexity regression.

Overlap depth is a separate variable, and a cheap one. Holding marks at 1600 and varying width
gives 1.44, 1.21, 1.07 and 1.30 ms at depths 1, 2, 4 and 8: flat within noise. The sweep's cost is
driven by how many marks there are, not how deeply they stack, which is what the per-type `TreeSet`
predicts, since resolving a segment reads only the front of each type.

</details>

### How often the interleaving anomaly fires

RGA's interleaving anomaly is the most-cited weakness of the algorithm this project chose.
`RgaInterleavingTest` proves it exists by building the case by hand. Fugue is the known fix and was
declined on budget. Neither said how often a real session hits it.

[`InterleavingMeasurement`](crdt-core/src/test/java/dev/collabedit/crdt/InterleavingMeasurement.java):
two authors typing 8 characters each at the same point, in complete isolation, exchanging in
shuffled order. That is the worst case, so every rate is an upper bound. The metric is fragments,
how many pieces an author's writing is broken into, where one means it survived intact.

The variable is not author count or document size. It is whether the cursor moves forward. RGA
orders siblings, meaning insertions sharing one origin, by timestamp, and that is the only place
two authors can mix. Typing forward makes each character the origin of the next, so a run is a
chain, and a chain cannot be broken by anything arriving concurrently. Going back to the same spot
produces nothing but siblings.

| Cursor advances with probability | Scenarios interleaved | Median fragments | Worst seen |
|---:|---:|---:|---:|
| 1.00, ordinary typing | **0.0%** | 1 | 1 |
| 0.99 | 1.2% | 1 | 2 |
| 0.95 | 4.9% | 1 | 2 |
| 0.90 | 10.9% | 1 | 3 |
| 0.75 | 25.8% | 1 | 4 |
| 0.50 | 49.2% | 1 | 7 |
| 0.25 | 74.3% | 2 | 8 |
| 0.00, the hand-built case | **100.0%** | 8 | 8 |

More authors meeting at one point, at 90% forward: 2 authors 10.9%, 3 authors 20.3%, 5 authors
35.9%, 8 authors 52.4%, with worst-case 3 fragments throughout.

The 0.0% in the first row is structural, not statistical, and it is the load-bearing result. A
separate test asserts it over 2000 trials, so if it ever stops holding, the failure is a merge bug
rather than a shifted percentage. Fugue stays rejected, now for a measured reason rather than a
budgetary one: the anomaly needs two people in the same spot, at the same time, with one of them
going back to fix something, and when it fires the damage is usually one extra fragment rather than
the shredding the hand-built case shows. That case is p = 0, which is nobody's typing.

<details>
<summary>The first version of this table was an artefact of java.util.Random</summary>

It reported 0% at p = 0.90 and 100% at p = 0.50: a cliff where the mechanism predicts a smooth
curve. Chasing it found nothing wrong with the merge rule. The fault was seeding trials with
`new Random(0)`, `new Random(1)`, and so on.

```
new Random(0).nextDouble = 0.730968
new Random(1).nextDouble = 0.730878
new Random(2).nextDouble = 0.731147
new Random(9).nextDouble = 0.730162
```

`java.util.Random` is a linear congruential generator whose *first* output barely moves across
consecutive small seeds. Every trial made the same decision at the first keystroke, and the first
keystroke is the only one that matters here, because it decides whether an author gets two children
of the shared origin. Above 0.731 the answer was always "advance" and nothing interleaved; below
it, always "stay" and everything did. Two thousand trials per row, to three significant figures,
describing a property of the random number generator. The seed goes through splitmix64 now, the
same mixing function `TreapBlockList` uses for heap priorities, for the same reason.

</details>

### The degradation point was my rig, not the system

This section used to end with "I do not know whether the cause lies in the system or in the
measurement rig". It was the rig. `TestStompClient` built a `StandardWebSocketClient` without
configuring the JSR-356 container, whose default text buffer is 8 KiB, eight times smaller than the
frame limit this system declares. Any larger message never reached the test client.

Why that presented as unrecoverable divergence rather than an ordinary dropped frame: broadcasts go
one operation per frame (~70 B), so the threshold was unreachable and the mechanism looked healthy;
when an operation was lost the client correctly detected the gap and asked to resynchronise; and
the resynchronisation response is chunked at 300 operations, about 15 KB, above 8 KiB. The recovery
path was the only thing the buffer did not let through. Hence the exact symptom recorded here: a
server holding every operation, some clients permanently a few operations behind, and no error in
the log. A "fix" shortening the resynchronisation chunk would have repaired the rig and changed
nothing in the system.

After raising the rig's buffer all twelve scenarios converge, and `BroadcastFanoutIT` gives 3/3
repeatable convergences at 2, 10, 25 and 50 clients, including on the code from *before* broadcast
coalescing. Coalescing is therefore justified by latency, not by fixing convergence. What this does
not prove: clients and server still share one process, so the numbers exclude real network latency.
Splitting the rig across machines remains undone, but is no longer needed to answer "system or
rig".

## Failure modes

Rows naming a test have one that checks them.

| Scenario | Behaviour | Verified |
|---|---|---|
| **Connection lost mid-edit** | Editing continues into IndexedDB and drains on reconnect. | e2e network-cut and reload specs |
| **Instance dies between broadcast and write** | The operation is on other screens but not in the log, so the client retransmits, because its buffer releases on the **write** ack. | outbox release rule, above |
| **Replica lost, or instance restart** | Rebuilt from the log: nearest snapshot plus tail. Deleting every snapshot changes only the time taken. | `stateSurvivesReplicaLoss`, `snapshotsAreOnlyACache` |
| **Redis outage** | Editing continues; instances diverge for the outage and anti-entropy pulls the difference from the log. | `redisOutageIsRepairedByAntiEntropy` |
| **Broadcast to a client lost** | The client sees an ack for an operation it never received and resynchronises. A `server_seq` cursor would look current despite the gap. | gap-detection tests |
| **Two people edit the same character** | Both kept, ordered by `(lamport, replicaId)`, no arbiter. | `concurrentEditOfSameCharacterKeepsBoth`, property `convergence` |
| **Two people move the same paragraph** | **Two copies of it.** The replicas agree, so the guarantee holds and the result is still wrong: a move is a delete plus an insert, because a paragraph has no identity in a flat sequence. | `concurrentMovesOfTheSameParagraphDuplicateIt` |
| **Client offline past the GC window (7 days)** | Refused with `RESYNC_REQUIRED`, local version kept as a "Recovered draft". Nothing sensible can be merged, because where those characters belong is gone. | `staleOperationIsRejectedExplicitly` |
| **Very large document** | Latency barely moves after the treap. Tombstones grow with edit count and GC reclaims less than I assumed. | JMH, load harness |
| **Rate limit exceeded** | The frame is refused with the exact wait, and the client retries from its buffer. The refusal is explicit, unlike a permission refusal; dropping the frame silently would be data loss. | harness: 351 rejections, zero lost operations |
| **Deleting formatted text** | Formatting stays on what survived; mark anchors are tombstones held back from GC. | `garbageCollectionSparesMarkAnchors` |
| **Deleting commented text** | The thread stays, marked detached, quoting the fragment as of when it was written. | `threadSurvivesDeletionOfAnchoredText` |
| **Access revoked mid-session** | Takes effect on every instance, 5 s cache TTL as the floor; frames are re-checked individually, so the session survives and the permission does not. The client half was wrong until a review found it: the banner sat above a still-writable editor, and refused keystrokes counted as unsaved, so the indicator read "Saving" while the work was discarded. | `revokedAccessTerminatesLiveSubscription`, `PermissionClusterInvalidationIT` |

## Limitations

Deliberate simplifications, and the ones that turned out worse than I assumed.

- **No paragraph identity, so no move operation.** Two people moving the same paragraph get two of
  it. Structure as marks over a flat sequence is what makes it merge cleanly with everything else,
  and equally what makes this unfixable without rewriting the layer the rest stands on. The real fix
  is a tree CRDT for structure with a sequence CRDT inside each node.
- **A document open on three instances lives in three copies,** the declared cost of having no
  owner. Per-replica memory has never been measured, so `max-loaded-documents: 200` is an
  order-of-magnitude guess, not a capacity plan. At scale this wants document-affinity routing, but
  I would want the measurement first.
- **Identity is federated; the account model around it is not.** No account page, no linking Google
  to GitHub (sign in with the other and you are a different person), no revocation beyond the
  token's twelve-hour life, no account deletion.
- **Invitations are multi-use** until they expire or are revoked, which is what "anyone with this
  link" means. Now that every user arrives with a verified email, an invitation bound to an address
  is the obvious next step, and was impossible before.
- **`link` visibility grants edit to anyone holding the address**, the mode working as intended,
  since possession of an unguessable URL *is* the credential. Worth recording is what it used to
  mean: the document list published those URLs to every signed-in user, so "anyone with the link"
  was "everyone", and 132 of 133 documents on a dev instance were world-editable. Listing and access
  are separate questions now.
- **The authorization guard checks that a decision was made, not that it was right.**
- **The performance numbers come from a single process,** so they exclude real network latency and
  disk contention.
- **GC reclaims less than I assumed.** Causal stability needs every replica to have seen the
  deletion, so one client that closed its laptop holds the window open for everyone in that
  document.
- **A cluster message for a document whose replica is still loading is dropped,** and anti-entropy
  cannot find it afterwards, because the fresh replica is labelled at log head so
  `head > appliedServerSeq` never fires. A client there can stay permanently one operation behind.
  Reproduced under load, not fixed. It needs a decision about how documents load in the cluster
  rather than a patch, and I have not made it yet.

## Running it

JDK 21, Node 22.12+, Docker.

```bash
docker compose --profile app up -d          # everything, on http://localhost:5173
```

To work on the code instead, run the dependencies and the two halves separately:

```bash
docker compose up -d postgres redis
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)   # no default; see below
export COLLABEDIT_DEV_LOGIN=true                           # or configure a provider

./mvnw -pl app spring-boot:run              # backend on :8090
cd frontend && npm install && npm run dev   # frontend on :5173
```

| Variable | Default | |
|---|---|---|
| `COLLABEDIT_AUTH_SECRET` | *none, refuses to start* | Signs session tokens. A secret committed here would let any reader mint a token for any user, so there is no fallback. |
| `COLLABEDIT_DEV_LOGIN` | `false` | Serves the name-only login. 404 when off. |
| `GOOGLE_CLIENT_ID` / `_SECRET` | unset | Enables the Google button. Both halves required, or it advertises a button that fails at the token exchange. |
| `GITHUB_CLIENT_ID` / `_SECRET` | unset | Same, for GitHub. |
| `COLLABEDIT_APP_URL` | `localhost:5173` | Where a finished sign-in returns to. |
| `COLLABEDIT_PORT` | `8090` | Must match the registered redirect URI. |

With no provider and no dev login the app starts and the login screen says there is no way in. That
is intended. Register the redirect URI as `http://localhost:8090/login/oauth2/code/{google,github}`.
That path is Spring Security's, not ours, and pointing it at the app instead is the usual first
mistake. `cp .env.example .env` if you prefer a file; Compose reads it and it is gitignored.

```bash
./mvnw install                         # everything, integration tests on a real Postgres
./mvnw -pl crdt-core -Pthorough test   # 10 000 property tries, as in the nightly run
cd frontend && npm test                # client unit tests
cd frontend && npm run e2e             # two browsers, needs a running stack
```

A failing property prints its seed; `@Property(seed = "...")` replays it. On colima rather than
Docker Desktop, Testcontainers needs `DOCKER_HOST`,
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` and `api.version=1.44` in
`~/.docker-java.properties`; all three failures read as "no Docker".

Off a laptop, the frontend resolves the backend at build time
([`config.ts`](frontend/src/config.ts)) and defaults to its own origin with nginx proxying `/api`
and `/ws`. That is the only arrangement that works over TLS, since an `https:` page may not open a
`ws:` socket. Separate origins need `VITE_API_BASE` at build time and
`COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` on the backend.

## Observability

One keystroke produces a single trace across two services: `edit.submit` in the browser, then
`op.receive`, `crdt.merge` and `op.broadcast.local` on the server. The context travels in a STOMP
frame header, because the WebSocket handshake happens once per session and an HTTP header would
describe the session rather than the edit. The write to the log is attached by **links** instead of
nesting, since one write covers many operations from different traces.

Metrics: active sessions, propagation latency percentiles, operations merged, conflicts resolved,
characters reclaimed by GC, cluster messages dropped, frames refused by reason. The
[dashboard](ops/grafana/dashboards/collabedit.json) is a file in the repository, and each panel says
what a bad value looks like. The one that catches people out: a permanently zero count of gap
repairs means the repair path has never run, which is not the same as healthy.

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana on :3001
```

Point it at a backend started from Maven, not `--profile app`: the containerised one sets
`OTEL_SDK_DISABLED`, so a browser span arrives with no server span to join and the trace looks
broken rather than disabled.

---

MIT, see [`LICENSE`](LICENSE).
