# Collaborative Document Editor

![Two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

*Two browsers, one document: concurrent editing of the same paragraph, work continuing after the
network is cut, merging on reconnect, and a rollback. Recorded from
[a script](frontend/e2e/demo.spec.ts) that CI runs on every pull request, so the scenario cannot
quietly stop working while the image goes on showing it.*

![Bolding a range, typing inside it, commenting on a selection, deleting anchored text, restoring from the bin](docs/demo-v2.gif)

*The same thing one layer up. Anna bolds a fragment; Marek, not seeing it yet, types a word in the
middle of it, and the formatting covers the new text instead of leaving a hole. Then a comment on a
selection, deleting the anchored text (the thread stays, marked detached), and restoring a document
from the bin.*

Real-time collaborative editing: several people in one document at once, over a bad connection, in
the same paragraph, with no server deciding who wins.

The merge algorithm is an RGA-style CRDT **written from scratch, twice** — Java on the server and
TypeScript in the browser. It is not a wrapper around Yjs or Automerge. Formatting, comments and
suggestions all run on that same core rather than beside it.

**Contents**

- [Features](#features)
- [Architecture](#architecture)
- [How the merge works](#how-the-merge-works)
- [Anchors: one mechanism, four features](#anchors-one-mechanism-four-features)
- [Undo in a shared document](#undo-in-a-shared-document)
- [History, and a diff by identity](#history-and-a-diff-by-identity)
- [Offline, reconnect, and the gap you cannot see](#offline-reconnect-and-the-gap-you-cannot-see)
- [The cluster](#the-cluster)
- [Authorization](#authorization)
- [Running it](#running-it)
- [Tests](#tests)
- [Performance](#performance)
- [Failure modes](#failure-modes)
- [Limitations](#limitations)
- [Observability](#observability)

---

## Features

**Editing** — any number of people in one document; a keystroke is visible locally immediately and
reaches everyone else in about 130 ms with 50 clients connected. Offline editing into an IndexedDB
buffer that survives the tab closing. Undo and redo that take back **your** operations, never
someone else's. Live cursors and a participant list that works across backend instances. Large
pastes split into operations at the source, because nothing downstream is allowed to split one.

**Rich text** — bold, italic, underline, strikethrough, inline code, links; headings, block quotes
and lists as marks over a line rather than nodes in a tree. Formatting survives concurrent editing
inside its own range, which is [the interesting
one](#which-side-an-anchor-sits-on-decides-how-formatting-behaves).

**Comments** — threads anchored to a range; reply, resolve, reopen. Deleting the commented text
keeps the thread, marks it detached, and shows the quote as of when it was written. A comment
written with no connection is retried, and a retry does not create a second thread.

**Suggestions** — your edits become proposals drawn over the real text instead of changing it.
Accept or reject per suggestion; the resulting operations are generated server-side, so a suggester
can never write directly.

**History** — time travel to any point in the operation log, diff between two versions **by
character identity** rather than by string, and restore recorded as new operations rather than as a
rewrite of the past.

**Documents and access** — workspaces, document list, full-text search over content, trash with
restore. Four ordered roles (`viewer < commenter < editor < owner`), invitation links that carry a
role and are stored only as a hash, and revocation that takes effect mid-session on every instance.

---

## Architecture

![Two browsers and two backend instances, each holding an RGA replica, all appending to and replaying from one Postgres operation log](docs/diagrams/architecture.svg)

All four replicas are peers. A browser is not a thin client of an instance, and an instance is not an
authority over a browser — they run the same algorithm and merge each other's operations by the same
rule. Instances also gossip over Redis, which the [edit path](#the-path-of-a-single-edit) below shows
and [the cluster](#the-cluster) explains; it is left out here because it is a fast path rather than
part of the structure.

```
crdt-core/     the algorithm. A separate Maven module with ZERO Spring dependencies,
               so it can be tested and benchmarked in isolation and so the compiler
               stops the application layer leaking into it.
               Block, RgaDocument, MarkSet, Anchor, {Array,Treap}BlockList,
               OpCodec, SnapshotCodec, VersionVector + property tests + JMH

app/           Spring Boot 3, Java 21
  ws/          STOMP, presence, per-frame authorization, rate limiting
  auth/        roles, permissions, invitations, tokens
  domain/      the in-memory document replica
  persist/     operation log, snapshots, document catalog, search
  history/     time travel, diff, restore
  comments/    threads and messages (content outside the CRDT)
  suggestions/ accept and reject, server-side operation generation
  cluster/     Redis pub/sub, anti-entropy, permission invalidation

frontend/      React + TypeScript + Vite
  crdt/        the second RGA implementation + mark layer + undo
  transport/   STOMP client, offline buffer, gap detection, network simulator
  editor/      the CodeMirror 6 bridge, formatting, remote cursors
  ui/          document list, history, comments, suggestions, sharing

conformance/   test vectors and the mark-type table shared by both implementations
ops/           Prometheus / Tempo / Grafana / OTel Collector configuration
```

### The path of a single edit

![A keystroke painted locally, sent to an instance, merged, broadcast, published over Redis, appended to the log, and acknowledged by identity — with the outbox releasing on the write, not the broadcast](docs/diagrams/edit-path.svg)

### Two implementations, one corpus

The algorithm exists twice, in two languages, and they have to agree exactly — a browser and a
server that order two concurrent inserts differently produce a permanently split document.

Keeping them in step is a shared corpus in [`conformance/`](conformance/): operation sequences with
their expected resulting text and formatting, generated once and replayed by both test suites. A
change to the ordering rule in one language fails the other language's build.

The corpus does not cover everything, and the hole is worth being precise about. Vectors contain
operations that were **already created**, carrying concrete anchor sides, but "does bold grow when you
type at its end" is decided at *creation* time — so changing the expansion table on one side changes
no vector and the whole corpus stays green. That is why the table is a JSON file both sides read, with
a conformance test on each side, including one that walks the *other* way from the declared constants
back to the file: a type declared in Java and forgotten in the JSON left both suites green while the
client had no idea it existed.

---

## How the merge works

### The problem

Two people type into the same spot at the same time. Neither has seen the other. There is no
central sequencer to ask, and both must end up with the same document — not "eventually, after a
conflict dialog", but always, with no arbiter anywhere.

The naive answer is to send positions: *insert "!" at index 2*. That breaks the moment anything is
inserted earlier, because by the time the message arrives index 2 means a different place. Every
position-based approach ends up rewriting other people's messages to compensate. That is
Operational Transformation, and doing it correctly for N peers with no server is famously hard.

### Every character has an identifier

So we do not send positions. Each character gets an identifier when it is created and keeps it for
the life of the document:

```
Id = (replicaId, lamport)        printed as   1@7
```

`lamport` is a Lamport clock: bumped on every local edit, and bumped to
`max(local, incoming + 1)` on receipt of anyone else's operation. An insert names the character it
goes **after**, by identifier:

```
Insert { id: 1@7, originLeft: 1@6, text: "!" }
```

`originLeft` is a fact about the past that no later edit can change. That is the whole difference
from an index.

### Ordering two concurrent inserts

The insertion rule is: **find the origin, walk right while the block you meet has a higher
identifier than the one being inserted, and stop at the first lower one.** Identifiers compare by
`(lamport, replicaId)`, which is a total order, so it can never tie.

```
   both replicas hold "ok"; neither has seen the other

   Ann   Insert { id: 1@5, originLeft: k, text: "!" }
   Bo    Insert { id: 2@5, originLeft: k, text: "?" }

   Ann's replica receives 2@5           Bo's replica receives 1@5

     o  k  !                              o  k  ?
        ^  ^ 1@5                             ^  ^ 2@5
        origin                               origin

     walk right from k, meet 1@5          walk right from k, meet 2@5
     is 1@5 > 2@5?  no  -> stop here      is 2@5 > 1@5?  yes -> skip
                                          end of list    -> stop here

   both converge on     o  k  ?  !
                              ^  ^
                            2@5  1@5
```

Same result, opposite delivery order, no messages rewritten and nothing to roll back. Higher
identifiers sort to the left, which is arbitrary but has to be *decided*.

Two properties make the walk correct, and both rest on `lamport` being a real Lamport clock:

- Anything created causally after a block S has a higher timestamp than S, so skipping S skips its
  whole subtree. Concurrent runs never get shredded into each other.
- The first block past the origin's subtree has a *lower* timestamp than the origin, while the
  incoming block has a *higher* one — its author had seen the origin. So the scan stops on its own
  and never wanders out of the subtree.

### The document is a list of runs, not characters

Storing one object per character is wasteful and, worse, means one operation per keystroke. The
document is a list of **blocks**, each holding a whole run:

```
   Block{1@1, "Hello "}   Block{1@7, "world"}

   delete "wor" — a block is entirely alive or entirely dead, so it splits first

   Block{1@1, "Hello "}   Block{1@7, len 3, x deleted by 2@40}   Block{1@10, "ld"}
                                             |
                                             WHICH operation deleted it.
                                             GC tests this, not the insert.
```

Two invariants keep this honest:

1. **A block is entirely alive or entirely deleted.** Deleting part of one splits it first. The
   alternative — a per-character flag array — complicates every index operation and saves little.
2. **The insertion scan is coarse, by blocks rather than characters.** Safe because lamports inside a
   block increase and the characters are causally chained: if a block's first character outranks the
   incoming one, so does every other. And the scan never needs to stop mid-block, because if the
   origin *is* mid-block the block is split first anyway.

Typing appends into the previous block when the replica, the lamport and the origin all line up;
without that fast path run-length encoding would buy nothing, since every keystroke is still its own
operation on the wire. A deleted block keeps its identity and its length so it can still serve as an
origin, and may drop its `text` while keeping both.

### Two index implementations, one algorithm

`RgaDocument` does not know how the block list is stored. It goes through a `BlockList` interface,
and there are two of them:

| | Insert a block | Find by offset | Find by identifier |
|---|---|---|---|
| `ArrayBlockList` | O(n) | O(n) | O(n) |
| `TreapBlockList` | O(log n) | O(log n) | O(log n) |

The treap is a binary search tree keyed by document order, with a heap priority for balance, and
every node carries its subtree's visible and total length so a position lookup can descend instead
of scanning. The priority is not random: it is a splitmix64 hash of the block's identifier, so
every replica independently builds **the same tree shape** — which makes a failing test
reproducible instead of merely likely.

Both implementations pass the same property suite, which feeds them the same random operation
sequence and compares step by step. The companion property
`bothImplementationsAnswerQueriesIdentically` is the one that earns its keep: a mutation once left
the *text* identical in both and only the *positions* wrong — a document that looks healthy with
everybody's cursor in the wrong place. A comparison on text alone missed it.

The naive implementation came first, then the full test suite, then the JMH measurement, and only
then the tree. The commit history shows that order because it is more honest than dropping in a
clever structure on day one.

### Tombstones, and when one can be dropped

Tombstones can never be deleted on a whim: another replica may still be holding an operation whose
origin is that character. The condition for dropping one is **causal stability** — every replica
that could still refer to it has already seen the deletion. The server keeps the version vector
each connected replica reports and takes the component-wise minimum:

```
   replica A reports   { 1:120, 2:88, 3:200 }
   replica B reports   { 1:118, 2:91, 3:200 }
   replica C reports   { 1:120, 2:88, 3:196 }
   ------------------------------------------  component-wise minimum
   the meet            { 1:118, 2:88, 3:196 }
```

The test is on the **deletion**, not on the insertion. An audit found it applied to the insertion,
which is the same thing only when nothing was ever deleted quickly: a paragraph written last week and
deleted a second ago has an insertion far below the meet, so its tombstone was collectable
immediately while a peer 10 ms behind was still displaying the text. That is what `Block.deletedBy`
is for, and `GcSafetyTest` pins the scenario.

Two more things are held back from collection, both found the same way. **Anchors of buffered
operations**, because a mark waiting for one anchor still needs the other to exist — collecting it
stranded the operation in the pending buffer for the life of the process and the formatting silently
vanished. And **every character in a buffered delete range**, not just the first, because the range
walk goes block by block and removing anything in the middle breaks it permanently.

A replica offline for longer than the GC window (7 days) is refused with `RESYNC_REQUIRED` and its
local version saved as a separate "Recovered draft". No faked merge: where those characters belong is
genuinely gone.

### Why deduplication is not the version vector's job

The reflex is to keep a version vector and reject anything already seen. That is wrong with a Lamport
clock, because **per-replica counters are not contiguous** — the clock jumps whenever someone else's
operation arrives, so replica A may legitimately produce lamports 1, 2, 47, 48, 103. "I have
everything from A up to 48" cannot distinguish a hole left by a lost message from a hole left by a
clock jump.

Instead: an `Insert` is a duplicate if a character with that identifier is already in the structure —
exact, and free, because the structure indexes by identifier anyway. A `Delete` is not checked at all,
because setting a tombstone twice is a no-op.

The version vector still exists, for causal stability above and for the "what do you have" exchange on
reconnect, but it is not a correctness mechanism of the core. **Detecting gaps in delivery is the
transport's job, not the algorithm's.** The CRDT has to be correct under any order and any repetition,
and it is.

### What the Lamport clock is actually for

An earlier version of these notes claimed that without bumping the clock on receipt the implementation
**diverges**, and gave a counterexample. That claim was too strong and is withdrawn. I removed the
bump from production code (`clock = max(clock, 0)`) and ran a brute-force divergence search — 200 000
random scenarios, 3–5 replicas, 30 edits each, aggressive interleaving, every operation delivered
twice — and got **zero divergences and zero operations stuck in the buffer**. The integration function
is deterministic with respect to the *set* of present elements rather than the order they arrived in,
and that is enough for convergence.

The bump stays for an honestly named reason: it is a premise of the published RGA correctness proof,
and 200 000 random scenarios are evidence rather than a proof. What it does guarantee is the
structural property above — skipping a sibling skips its subtree, so a concurrently typed run stays
contiguous. That is an invariant about the linearisation, not about two replicas agreeing, and for a
long time nothing was checking it: removing the bump left every property test green. The fix is three
lines in `RgaInvariants`, the helper every property already calls after each step — assert that a
block's timestamp is above its origin's.

---

## Anchors: one mechanism, four features

Cursors, formatting, comment highlights and suggestions are all "a place in the document that has
to survive other people typing". They are all the same object:

```
Anchor { charId: Id, side: BEFORE | AFTER }
```

Not an offset — an offset stops pointing at the same place the moment somebody inserts text
earlier. An anchor is attached to a character, so it moves with the text.

### Which side an anchor sits on decides how formatting behaves

This is the heart of rich text in a CRDT and the easiest part to overlook. An anchor "after X" and
an anchor "before Y" point at the same gap in `…XY…` — until somebody inserts between X and Y.
Then they differ, and that difference is exactly what decides whether formatting grows:

```
   a mark ends between b and c in "abcd". Two ways to say that:

        end = after(b)                  end = before(c)

          a  b | c  d                     a  b | c  d

   now somebody types X into that gap:

          a  b | X  c  d                  a  b  X | c  d
                 ^ outside                      ^ inside
          the mark does not grow          the mark grows over X

   bold ends before(c)  ->  typing at the end of bold text is bold
   link ends after(b)   ->  typing after a link does not join the link
```

Same mechanism, one field different. The alternative is a separate "should this expand" flag, which
describes the same thing worse — because it is detached from where the anchor actually is.

The table of which types expand lives in
[`conformance/schema/mark-types.json`](conformance/schema/mark-types.json), read by both
implementations, with a test on each side that fails if they drift. An unknown type does not
expand: a mark that expands unexpectedly changes the look of text the user never formatted, while
one that fails to expand costs a repeated gesture.

### Formatting is a layer, not a character attribute

Marks are a separate CRDT layer over the character layer, in the style of Peritext. The character
layer never gained a field for bold, or for comments, or for suggestions. This is what the second
demo GIF shows:

```
   Ann bolds "llo" in "Hello world".
   Marek, who has not received the mark yet, types "XX" in the middle of it.

   a range between anchors             a per-character flag
   -----------------------             --------------------
   He[llo] world                       H e l l o   w o r l d
                                           B B B

   He[llXXo] world                     H e l l X X o   w o r l d
      bold covers XX                       B B _ _ B
                                                 the hole is permanent
```

Resolving marks to spans is a sweep over the block list. Because "the sweep agrees with itself on
every replica" is a test that passes when the sweep returns nothing at all, it is checked against
`NaiveMarkResolver` — an independent, obviously correct, far too slow implementation that resolves
anchors straight from the block list. Replacing `MarkSet.spans` with `return List.of()` left all
four of the original mark properties green. It does not survive the oracle.

Twelve mark types share the mechanism: six inline styles, `comment`, two suggestion types, and
`heading` / `quote` / `list` for block structure.

### A comment is half in the CRDT and half out

The common misreading of this codebase is "there is a CRDT, so the comments must be in it". They are
not. The **anchor** is a CRDT operation; the **conversation** is rows in Postgres. They need
different things: a range has to merge without an arbiter under concurrent edits, while a sentence
written by one person has a single author and an obvious order, so running it through a merge
algorithm buys nothing and costs a lot.

Two consequences are easy to miss in the code:

1. **The anchor is created after the thread**, because it needs the thread's id, so there is a window
   where a thread exists with no anchor. Accepted consequence of the split, not an oversight.
2. **The highlight and the content travel by different routes at different speeds.** The highlight is
   a CRDT operation and appears for everyone immediately; the conversation goes over REST and waits
   for the panel to refresh. It looks like an inconsistency and is not.

Offline, a comment goes to an outbox with its anchor and is retried on reconnect. A retry of one that
did arrive is recognised by an idempotency key and does not become a second thread — the offline path
here needs exactly-once, not merging.

### Suggestions are marks over real text

A suggestion does not modify the document and does not live in a side buffer. Typing in suggest
mode produces text that is really inserted, carrying a `suggest-insert` mark; deleting produces a
`suggest-delete` mark over text that is still there. Accepting means dropping the mark. Rejecting
means generating the operations that undo it — **server-side**, so a role that may suggest can
never write directly, whatever the client sends.

That is the payoff of the mark layer being general. Suggestions needed no new anchor mechanism, no
new merge rule and no change to the character layer.

---

## Undo in a shared document

CodeMirror's own history is switched off. It works on text, so an undo would also take back whatever
other people typed in the meantime — in a shared editor that is not an inconvenience, it is deleting
someone else's work.

Undo therefore operates on **your own operations**, and the difficulty is that in a CRDT an operation
cannot be inverted: a deleted character has a tombstone and does not come back to life. Restoring
text means inserting **new** characters with new identifiers. The visible effect is the same; the
identity is not. Yjs does the same thing.

A stack entry remembers a **character identifier**, not an offset, because an offset recorded at edit
time is stale by the time you press Ctrl-Z. Undoing a formatting change is not "remove the mark"
either: the inverse of "make this bold" is "put back whatever this range had before", which differs
whenever somebody else's mark of the same type was already there — removing would take away their
formatting too, and they never touched anything.

If the anchor has since been deleted by somebody else, the entry is **skipped** rather than guessed
at. Putting the text back "roughly there" would appear somewhere random with no explanation.

---

## History, and a diff by identity

The operation log in Postgres is the source of truth. Snapshots are a disposable cache — deleting
every snapshot in the database changes only how long a load takes, and there is a test that does
exactly that (`snapshotsAreOnlyACache`).

That buys time travel for free: `GET …/history/at?seq=` replays the log to any point. Restoring is
recorded as **new operations** rather than a rewrite, so the history of a restore is itself in the
history.

The diff endpoint compares two versions by **character identity**, which answers a question a text
diff cannot:

```
   delete a word, then type the same word again

   text diff        "the quick fox"  vs  "the quick fox"   →  no change
   identity diff    1@40..1@44 gone, 2@81..2@85 new        →  a change,
                                                              by a named author
```

Restore uses a common prefix and suffix rather than a full Myers diff. A restore is usually a
rollback of one recent edit, so prefix/suffix is short and cheap; Myers would produce smaller
operations at a cost in complexity that has not been worth paying.

---

## Offline, reconnect, and the gap you cannot see

Losing the connection is the normal case, not the exception. Editing continues into an IndexedDB
buffer that survives the tab being closed, and drains on reconnect.

What decides when an operation may leave that buffer is the interesting part. The release is on the
**write** acknowledgement, never on the broadcast. If an instance dies in between, the operation is
on other people's screens but not in the log — and because the buffer never released it, the client
retransmits.

The reverse failure is the one worth designing for, because it is silent. A broadcast to one client
is lost. Nothing errors; the client simply has a hole and does not know it. Watching `server_seq`
for gaps does not help, because the cursor advances on **acknowledgements**, which are broadcast to
everybody — so it looks perfectly current while the operation is missing. So the acknowledgement
carries the operation's **identity**, and the client asks a different question:

```
   ack for 7@41 arrives  →  have I actually handled the operation 7@41?
                            no  →  a broadcast was lost  →  resynchronise
```

That check was originally "does my replica contain a character 7@41", which is exact for inserts and
always false for deletes and marks — their identity comes from the same clock but never names a
character. So one backspace by anybody set the gap flag and the client resynchronised from zero every
15 seconds for ever, making the repair mechanism its own permanent load. It is now a bounded window
of handled identities, with the structural check kept as a second line.

![Client connection states, with the two exits: resynchronising on a detected gap, and a refusal past the collection window](docs/diagrams/connection-states.svg)

`Offline` is not a dead end — edits continue into IndexedDB and survive the tab closing. `Refused` is
the only state the client cannot recover from on its own.

The heartbeat is not what the user experiences. The client listens for the browser's
`online`/`offline` events and for socket errors, which fire immediately. The broker's timeout is
the backstop for a socket that is dead without saying so.

---

## The cluster

Every backend instance is an ordinary CRDT replica — a peer of the other instances and of the
browsers, as in the [architecture diagram](#architecture). No leader, no distributed lock, no
sticky sessions.

Redis is a fast path, not a channel with delivery guarantees. Losing a message is a **latency**
problem rather than a correctness one, because the log is authoritative and the anti-entropy sweep
repairs the difference. `ClusterChaosIT` pauses the Redis container mid-write to check that, and
`redisOutageIsRepairedByAntiEntropy` checks the repair specifically.

There is a deterministic simulation of the whole cluster as well: the same scenarios driven from a
seed instead of from thread interleaving, so a failure arrives with the number that reproduces it
rather than with "sometimes".

The cost of having no owner is on the [limitations](#limitations) list: a document open on three
instances lives in three copies.

---

## Authorization

Two doors — HTTP and WebSocket — and one `PermissionService` behind both. The interesting part is
the HTTP side, where the rule is **declare what you need, or you are not served**.

![Every request passes one interceptor: a handler declaring no access requirement is answered with 500 and never runs](docs/diagrams/authorization.svg)

Three things in that picture are the whole of it, and each answers a hole that actually shipped
here:

1. **The default branch refuses,** so a new controller starts *broken and loud* rather than open and
   quiet — which is what `HistoryController` was for a whole checkpoint, handing out the full content
   of any document and letting a reader **overwrite** it. 500 rather than 404 is deliberate: this is
   our bug, not a caller doing anything wrong, and it should read that way in a log.
2. **The document id comes from the declaration**, not from wherever the handler happens to look.
   That is why creating a comment is `POST /api/documents/{id}/comments` with the document in the
   path — a mechanism that resolves its subject from a declaration cannot be pointed at an endpoint
   that keeps its subject in the body.
3. **The build enforces the same rule as the runtime.** An undeclared mapping fails
   `EndpointAuthorizationArchitectureTest`, and `publicEndpointsAreExactlyTheExpectedOnes` pins the
   exemption list by name. The runtime guard alone would be a rule you discover by breaking
   production; the test alone would be a rule anyone can delete.

**What it does not claim.** The guard checks that a decision was *made*, not that it was the *right*
one. `@RequiresDocumentAccess(READ)` on something that writes is declared, consistent, and wrong.

On the WebSocket side, `DocumentAccessInterceptor` checks **every** SEND and SUBSCRIBE frame. It
asks not "do you have access to the document" but "does your role permit *this* operation" —
comments and edits travel the same channel, so the distinction has to descend to the operation type.
An unresolvable destination is refused, which it was not at first: Spring's simple broker matches
subscription destinations as **Ant patterns**, so `SUBSCRIBE /topic/**` resolved to no document,
passed unchecked as if it were a private queue, and then matched every document's broadcast.

---

## Running it

Requirements: JDK 21, Node 22.12+, Docker.

```bash
docker compose --profile app up -d      # everything, on http://localhost:3000
```

The first run takes a few minutes to build the images. To work on the code instead:

```bash
docker compose up -d postgres redis

# Required, with no default: the server refuses to start without it. A secret committed to the
# repository would let any reader mint a token for any user. Compose and CI set their own.
export COLLABEDIT_AUTH_SECRET=$(openssl rand -base64 32)

./mvnw -pl app spring-boot:run              # backend on :8080
cd frontend && npm install && npm run dev   # frontend on :5173
```

<details>
<summary>If you use colima rather than Docker Desktop</summary>

Testcontainers does not read the docker context, and Docker 29+ rejects the API version
docker-java negotiates by default. All three symptoms read as "no Docker":

```bash
# "Could not find a valid Docker environment"
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
# "error while creating mount source path ..." — the socket path inside the VM, for Ryuk
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
# "client version 1.32 is too old"
echo "api.version=1.44" > ~/.docker-java.properties
```

None of this is needed on GitHub runners.
</details>

Deploying somewhere that is not a laptop: the frontend resolves the backend address at build time
([`frontend/src/config.ts`](frontend/src/config.ts)) and defaults to the origin it was served from,
with nginx proxying `/api` and `/ws`. That is the only arrangement that works over TLS, since a
page served over `https:` may not open a `ws:` socket. For separate origins, set `VITE_API_BASE` at
build time and `COLLABEDIT_WEBSOCKET_ALLOWEDORIGINPATTERNS` on the backend.

Regenerating the pictures in this file — the diagrams come from mermaid sources in
[`docs/diagrams/`](docs/diagrams), the GIFs from a Playwright script:

```bash
./tools/render-diagrams.sh                 # docs/diagrams/*.mmd -> *.svg

./tools/reset-demo-data.sh
cd frontend && npm run demo
cd .. && ./tools/make-gif.sh core docs/demo.gif && ./tools/make-gif.sh product docs/demo-v2.gif
```

---

## Tests

```bash
./mvnw install                         # everything, integration tests on a real Postgres
./mvnw -pl crdt-core test              # property tests (1000 tries, random seed — it searches)
./mvnw -pl crdt-core -Pthorough test   # 10 000 tries, as in the nightly run
cd frontend && npm test                # client unit tests
cd frontend && npm run e2e             # two browsers, needs a running stack
```

A failing property prints its seed; `@Property(seed = "…")` replays it.

| Layer | Tests |
|---|---:|
| `crdt-core` — property, structural invariants, conformance vectors, snapshot bytes, marks, GC safety | 87 |
| `app` — integration on a real Postgres and Redis: cluster chaos, permissions, presence across instances, the architecture test, the frame interceptor | 227 |
| `frontend` — agreement with the Java implementation, the CodeMirror bridge, snapshot decoding, undo, gap detection, the offline store | 134 |
| end-to-end — two browser contexts, every spec failing on an uncaught console error | 21 |
| **total** | **469** |

The deterministic cluster simulation is counted separately: 1207 is a count of **seeds**, not of
distinct assertions, and folding it into the total would turn an honest number into a marketing one.
Its value is that a failure arrives with the seed that reproduces it.

The order of priority is property tests of the algorithm, then integration tests against a real
Postgres and Redis through Testcontainers rather than mocks, then chaos and seeded simulation, and
only then ordinary unit tests. Load harness, JMH and the fan-out measurement run nightly instead of
on every build — they take minutes and measure the machine as much as the system.

**The tests get checked too.** I mutate production code and check the suite goes red. That has found
three tests that verified nothing:

- Removing the Lamport clock bump left all fifteen property tests green, while four comments claimed
  they existed to catch exactly that. [Full story above.](#what-the-lamport-clock-is-actually-for)
- Removing the treap's aggregate update after a tombstone sailed through convergence, because every
  replica was wrong in the same way. The text agreed; only the positions were wrong.
- `garbageCollectionSparesMarkAnchors` passed with its mechanism switched off: I had anchored the
  mark mid-text, where an older rule already protected the character, so the test was checking the
  old rule and crediting the new one.

**A test written to check mechanism X may be passing thanks to mechanism Y**, and breaking X is the
only way to find out.

---

## Performance

All numbers from one machine: MacBook, Apple Silicon, 10 cores, JDK 25-ea, 4 GB heap, with Postgres
and Redis in containers alongside. Full tables, methodology, and the claims that turned out to be
wrong once measured, are in [`docs/performance.md`](docs/performance.md).

Index structure, JMH, at 100 000 characters:

| Operation | `ArrayBlockList` | `TreapBlockList` |
|---|---:|---:|
| Position → identifier | 60.2 µs | **0.286 µs** |
| Identifier → position | 40.3 µs | **0.210 µs** |
| Reading the whole text | 310 µs | 429 µs *(treap slower)* |

The shape matters more than the values: for a tenfold larger document the naive scan grows about
13×, the treap about 2×. Reading the whole text is 40% slower in the treap because walking a tree
has worse locality than iterating an array. That runs once per document open while lookup runs on
every keystroke, so it is an easy trade — but it is a trade, not a free win.

Propagation between clients, from the load harness. All twelve scenarios converge:

| Document | Clients | p50 | p99 |
|---:|---:|---:|---:|
| empty | 50 | 202 ms | 374 ms |
| 10 000 chars | 50 | 135 ms | 230 ms |
| 100 000 chars | 50 | 124 ms | 1299 ms |

Document size barely affects median latency. The p99 tail at 100 000 characters is the rate limiter
rather than the algorithm; `docs/performance.md` has the measurement that disproved my first
explanation of it, along with the time I blamed the system for a 8 KiB buffer in my own test client.

---

## Failure modes

What actually happens when something goes wrong. Rows naming a test have one that checks them.

| Scenario | Behaviour | Verified |
|---|---|---|
| **Connection lost mid-edit** | Editing continues into IndexedDB and drains on reconnect. | e2e network-cut and reload specs |
| **Instance dies between broadcast and write** | The operation is on other screens but not in the log, so the client retransmits — its buffer releases on the **write** ack. | outbox release rule, above |
| **Replica lost, or instance restart** | Rebuilt from the log: nearest snapshot plus tail. Deleting every snapshot changes only the time taken. | `stateSurvivesReplicaLoss`, `snapshotsAreOnlyACache` |
| **Redis outage** | Editing continues; instances diverge for the outage and anti-entropy pulls the difference from the log. | `redisOutageIsRepairedByAntiEntropy` |
| **Broadcast to a client lost** | The client sees an ack for an operation it never received and resynchronises. A `server_seq` cursor would look current despite the gap. | gap-detection tests |
| **Two people edit the same character** | Both kept, ordered by `(lamport, replicaId)`, no arbiter. | `concurrentEditOfSameCharacterKeepsBoth`, property `convergence` |
| **Two people move the same paragraph** | **Two copies of it.** The replicas agree, so the guarantee holds and the result is still wrong: a move is a delete plus an insert, because a paragraph has no identity in a flat sequence. | `concurrentMovesOfTheSameParagraphDuplicateIt` |
| **Client offline past the GC window (7 days)** | Refused with `RESYNC_REQUIRED`, local version kept as a "Recovered draft". No faked merge — where those characters belong is gone. | `staleOperationIsRejectedExplicitly` |
| **Very large document** | Latency barely moves after the treap. Tombstones grow with edit count and GC reclaims less than I assumed. | JMH, load harness |
| **Rate limit exceeded** | The frame is refused with the exact wait, and the client retries from its buffer. Unlike a permission refusal, this one is not silent: a silent one is data loss. | harness: 351 rejections, zero lost operations |
| **Deleting formatted text** | Formatting stays on what survived; mark anchors are tombstones held back from GC. | `garbageCollectionSparesMarkAnchors` |
| **Deleting commented text** | The thread stays, marked detached, quoting the fragment as of when it was written. | `threadSurvivesDeletionOfAnchoredText` |
| **Access revoked mid-session** | Takes effect on every instance, with the 5 s cache TTL as the floor. Subscriptions are re-checked per frame, so the session survives and the permission does not. | `revokedAccessTerminatesLiveSubscription`, `PermissionClusterInvalidationIT` |

---

## Limitations

Deliberate simplifications, and the ones that turned out worse than I assumed.

- **No paragraph identity, so no move operation.** Two people moving the same paragraph get two of
  it. Structure as marks over a flat sequence is what makes it merge cleanly with everything else,
  and also what makes this unfixable without rewriting the layer everything else stands on. The real
  fix is a tree CRDT for structure with a sequence CRDT inside each node.
- **A document open on three instances lives in three copies** — the declared cost of having no
  document owner. Per-replica memory has never been measured, so `max-loaded-documents: 200` is an
  order-of-magnitude guess rather than a capacity plan. At real scale this wants document-affinity
  routing or a measured eviction policy, and I would want the measurement first.
- **Auth is deliberately thin.** Passwordless login, a signed token, no accounts, no refresh
  rotation. The subject here is distributed data consistency, not identity management.
- **Invitations are multi-use** until they expire or are revoked, which is what "anyone with this
  link" means when there are no addressable users. A single-use invite bound to an email address
  would be the first thing to add.
- **The authorization guard checks that a decision was made, not that it was right.** A read
  capability declared on a write endpoint passes.
- **The performance numbers come from a single process,** with clients, server and database on one
  machine, so they exclude real network latency and real disk contention.
- **GC reclaims less than I assumed.** Causal stability needs every replica to have seen the
  deletion, so one client that closed its laptop holds the window open for everyone in that document
  until it times out.
- **A cluster message for a document whose replica is still loading is dropped,** and anti-entropy
  cannot detect it afterwards, because the fresh replica is labelled at log head so
  `head > appliedServerSeq` is never true. A client on that instance can stay permanently one
  operation behind. Known, reproduced under load, not fixed — it needs a design decision about the
  cluster load path rather than a patch.

---

## Observability

```bash
docker compose --profile observability up -d   # Prometheus, Tempo, Grafana on :3001
```

Use the backend started from Maven on the host, not `--profile app`: the containerised backend sets
`OTEL_SDK_DISABLED` unconditionally, so a browser span arrives with no server span to join and the
trace looks broken rather than disabled.

One keystroke produces a single trace across two services: `edit.submit` in the browser, then
`op.receive`, `crdt.merge` and `op.broadcast.local` on the server. The context travels in a STOMP frame
header, because the WebSocket handshake happens once per session and an HTTP header would describe the
session rather than the edit. The write to the log is attached by **links** rather than nesting, since
one write covers many operations from different traces.

Metrics: active sessions, propagation latency percentiles, operations merged, conflicts resolved,
characters reclaimed by GC, cluster messages dropped, frames refused by reason. The
[dashboard](ops/grafana/dashboards/collabedit.json) is a file in the repository, and each panel says
what a bad value looks like — including that a permanently zero count of gap repairs is not good news
but a sign the repair path has never run.

---

MIT — see [`LICENSE`](LICENSE).
