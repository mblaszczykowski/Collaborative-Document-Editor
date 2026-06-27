# Collaborative Document Editor

![Two people editing at once, one loses the network, changes merge on reconnect, a version is rolled back](docs/demo.gif)

*Concurrent editing of the same paragraph, work continuing after the network is cut, merging on
reconnect, a rollback. Recorded from [a script](frontend/e2e/demo.spec.ts) that CI runs on every
pull request, so the scenario cannot quietly stop working while the image goes on showing it.*

![Bolding a range, typing inside it, commenting on a selection, deleting anchored text, restoring from the bin](docs/demo-v2.gif)

*One layer up. Anna bolds a fragment; Marek, not seeing it yet, types inside it, and the formatting
covers the new text instead of leaving a hole. Then a comment on a selection, deleting the anchored
text, and a restore from the bin.*

Real-time collaborative editing: several people in one document at once, over a bad connection, in
the same paragraph, with no server deciding who wins.

The merge algorithm is an RGA-style CRDT **written from scratch, twice** — Java on the server,
TypeScript in the browser. Not a wrapper around Yjs or Automerge. Formatting, comments and
suggestions all run on that same core instead of beside it.

**Contents**

- [Features](#features) · [Architecture](#architecture) · [How the merge works](#how-the-merge-works)
- [Anchors: one mechanism, four features](#anchors-one-mechanism-four-features) · [Undo](#undo-in-a-shared-document) · [History](#history-and-a-diff-by-identity)
- [Offline and reconnect](#offline-reconnect-and-the-gap-you-cannot-see) · [The cluster](#the-cluster) · [Who you are](#who-you-are) · [Authorization](#authorization)
- [Tests](#tests) · [Performance](#performance) · [Failure modes](#failure-modes) · [Limitations](#limitations)
- Running it, configuration, observability: [`docs/RUNNING.md`](docs/RUNNING.md)

---

## Features

**Editing** — any number of people per document, ~130 ms propagation with 50 clients connected,
offline into an IndexedDB buffer that survives the tab closing, undo that takes back *your*
operations and nobody else's, live cursors and presence across backend instances.

**Rich text** — six inline styles plus headings, quotes and lists, all as marks over a flat
sequence instead of nodes in a tree. Formatting survives concurrent editing inside its own range,
which is [the interesting one](#which-side-an-anchor-sits-on-decides-how-formatting-behaves).

**Comments** — threads anchored to a range. Delete the commented text and the thread stays, marked
detached, quoting the fragment as of when it was written.

**Suggestions** — edits as proposals drawn over the real text, accepted or rejected one at a time.
The resulting operations are generated server-side, so a suggester can never write directly.

**History** — time travel to any point in the log, diff two versions *by character identity*, and
restore recorded as new operations instead of a rewrite of the past.

**Documents and access** — workspaces, search, trash, four ordered roles
(`viewer < commenter < editor < owner`), invitation links stored only as a hash, revocation that
lands mid-session on every instance.

---

## Architecture

![Two browsers and two backend instances, each holding an RGA replica, all appending to and replaying from one Postgres operation log](docs/diagrams/architecture.svg)

All four replicas are peers. A browser is not a thin client of an instance, and an instance is not
an authority over a browser: they run the same algorithm and merge each other's operations by the
same rule. Instances also gossip over Redis, which [the cluster](#the-cluster) explains. It is left
out of the diagram because it is a fast path, not part of the structure.

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

The algorithm exists twice, and the two have to agree exactly: a browser and a server that order two
concurrent inserts differently produce a permanently split document. Keeping them in step is a
shared corpus in [`conformance/`](conformance/) — operation sequences with their expected text and
formatting, generated once and replayed by both suites. Change the ordering rule in one language and
the other language's build fails.

The corpus has a hole worth naming. Vectors contain operations that were *already created*, carrying
concrete anchor sides, but "does bold grow when you type at its end" is decided at *creation* time.
Changing the expansion table on one side changes no vector, and the whole corpus stays green. So the
table is a JSON file both sides read, with a test on each side, including one that walks the other
way — from the declared constants back to the file. A type declared in Java and forgotten in the
JSON had left both suites green while the client had no idea it existed.

---

## How the merge works

Two people type into the same spot at once. Neither has seen the other, there is no central
sequencer to ask, and both must end up with the same document. Not "eventually, after a conflict
dialog". Always.

Sending positions (*insert "!" at index 2*) breaks the moment anything is inserted earlier, because
by the time the message lands, index 2 means somewhere else. Every position-based approach ends up
rewriting other people's messages to compensate. That is Operational Transformation, and doing it
correctly for N peers with no server is famously hard.

### Every character has an identifier

So we do not send positions. Each character gets an identifier at creation and keeps it for life:

```
Id = (replicaId, lamport)        printed as   1@7
```

`lamport` is a Lamport clock: bumped on every local edit, and bumped to `max(local, incoming + 1)`
when someone else's operation arrives. An insert names the character it goes **after**, by
identifier:

```
Insert { id: 1@7, originLeft: 1@6, text: "!" }
```

`originLeft` is a fact about the past that no later edit can change. That is the whole difference
from an index.

### Ordering two concurrent inserts

Find the origin, walk right while the block you meet has a higher identifier than the one being
inserted, stop at the first lower one. Identifiers compare by `(lamport, replicaId)`, a total order,
so it can never tie.

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

Same result, opposite delivery order, no messages rewritten, nothing to roll back. Higher
identifiers sort left, which is arbitrary but has to be *decided*.

Two properties make the walk correct, and both rest on `lamport` being a real Lamport clock.
Anything created causally after a block S has a higher timestamp than S, so skipping S skips its
whole subtree and concurrent runs never get shredded into each other. And the first block past the
origin's subtree has a *lower* timestamp than the origin, while the incoming block has a higher one
(its author had seen the origin), so the scan stops on its own instead of wandering out of the
subtree.

### The document is a list of runs, not characters

One object per character is wasteful, and worse, it means one operation per keystroke. The document
is a list of **blocks**, each holding a whole run:

```
   Block{1@1, "Hello "}   Block{1@7, "world"}

   delete "wor" — a block is entirely alive or entirely dead, so it splits first

   Block{1@1, "Hello "}   Block{1@7, len 3, x deleted by 2@40}   Block{1@10, "ld"}
                                             |
                                             WHICH operation deleted it.
                                             GC tests this, not the insert.
```

Two invariants keep it honest. A block is entirely alive or entirely deleted, so deleting part of
one splits it first; the alternative, a per-character flag array, complicates every index operation
and saves little. And the insertion scan is coarse, by blocks: safe because lamports inside a block
increase and the characters are causally chained, so if a block's first character outranks the
incoming one, so does every other. The scan never needs to stop mid-block either, because if the
origin *is* mid-block, the block was split first anyway.

Typing appends into the previous block when the replica, the lamport and the origin all line up.
Without that fast path, run-length encoding would buy nothing, since every keystroke is still its
own operation on the wire. A deleted block keeps its identity and its length so it can still serve
as an origin, and may drop its `text` while keeping both.

### Two index implementations, one algorithm

`RgaDocument` does not know how the block list is stored. It goes through a `BlockList` interface,
and there are two:

| | Insert a block | Find by offset | Find by identifier |
|---|---|---|---|
| `ArrayBlockList` | O(n) | O(n) | O(n) |
| `TreapBlockList` | O(log n) | O(log n) | O(log n) |

The treap is a BST keyed by document order with a heap priority for balance, every node carrying its
subtree's visible and total length so a position lookup can descend instead of scan. The priority is
a splitmix64 hash of the block's identifier, not a random number, so every replica independently
builds the same tree shape and a failing test is reproducible instead of merely likely.

Both pass the same property suite, fed the same random sequence and compared step by step.
`bothImplementationsAnswerQueriesIdentically` is the one that earns its keep: a mutation once left
the *text* identical in both and only the *positions* wrong, which is a document that looks healthy
with everybody's cursor in the wrong place.

Naive implementation first, then the tests, then JMH, and only then the tree. The commit history
shows that order because it is more honest than dropping in a clever structure on day one.

### Tombstones, and when one can be dropped

A tombstone can never be deleted on a whim, because another replica may still hold an operation
whose origin is that character. The condition is **causal stability**: every replica that could
still refer to it has already seen the deletion. The server keeps the version vector each connected
replica reports and takes the component-wise minimum.

```
   replica A reports   { 1:120, 2:88, 3:200 }
   replica B reports   { 1:118, 2:91, 3:200 }
   replica C reports   { 1:120, 2:88, 3:196 }
   ------------------------------------------  component-wise minimum
   the meet            { 1:118, 2:88, 3:196 }
```

The test is on the **deletion**, not the insertion. An audit found it applied to the insertion,
which is the same thing only when nothing was ever deleted quickly: a paragraph written last week
and deleted a second ago has an insertion far below the meet, so its tombstone was collectable
immediately while a peer 10 ms behind still displayed the text. That is what `Block.deletedBy` is
for, and `GcSafetyTest` pins the scenario.

Two more things are held back, both found the same way. Anchors of buffered operations, since a mark
waiting on one anchor still needs the other to exist; collecting it stranded the operation in the
pending buffer for the life of the process and the formatting silently vanished. And every character
in a buffered delete range, not just the first, because the range walk goes block by block and
removing anything in the middle breaks it permanently.

A replica offline longer than the GC window (7 days) is refused with `RESYNC_REQUIRED` and its local
version kept as a separate "Recovered draft". No faked merge: where those characters belong is
genuinely gone.

### Why deduplication is not the version vector's job

The reflex is to keep a version vector and reject anything already seen. With a Lamport clock that
is wrong, because per-replica counters are not contiguous: the clock jumps whenever someone else's
operation arrives, so replica A may legitimately produce lamports 1, 2, 47, 48, 103. "I have
everything from A up to 48" cannot tell a hole left by a lost message from a hole left by a jump.

Instead, an `Insert` is a duplicate if a character with that identifier is already in the structure.
Exact, and free, since the structure indexes by identifier anyway. A `Delete` is not checked at all,
because setting a tombstone twice is a no-op.

The version vector still exists, for causal stability and for the "what do you have" exchange on
reconnect, but it is not a correctness mechanism of the core. Detecting gaps in delivery is the
transport's job. The CRDT has to be correct under any order and any repetition.

### What the Lamport clock is actually for

An earlier version of these notes claimed that without bumping the clock on receipt the
implementation **diverges**, and gave a counterexample. Too strong, and I withdraw it. I removed the
bump from production code (`clock = max(clock, 0)`) and ran a brute-force divergence search: 200 000
random scenarios, 3–5 replicas, 30 edits each, aggressive interleaving, every operation delivered
twice. Zero divergences, zero operations stuck in the buffer. The integration function is
deterministic with respect to the *set* of present elements, not the order they arrived in, and that
is enough for convergence.

The bump stays for an honestly named reason: it is a premise of the published RGA correctness proof,
and 200 000 scenarios are evidence, not a proof. What it does guarantee is the structural property
above, that skipping a sibling skips its subtree, so a concurrently typed run stays contiguous. That
is an invariant about the linearisation, not about two replicas agreeing, and nothing checked it for
a long time — removing the bump left every property test green. The fix is three lines in
`RgaInvariants`, the helper every property already calls after each step: assert a block's timestamp
is above its origin's.

---

## Anchors: one mechanism, four features

Cursors, formatting, comment highlights and suggestions are all "a place in the document that has to
survive other people typing". They are the same object:

```
Anchor { charId: Id, side: BEFORE | AFTER }
```

Not an offset. An offset stops pointing at the same place the moment somebody inserts text earlier;
an anchor is attached to a character, so it moves with the text.

### Which side an anchor sits on decides how formatting behaves

This is the heart of rich text in a CRDT and the easiest part to overlook. An anchor "after X" and
an anchor "before Y" point at the same gap in `…XY…` until somebody inserts between X and Y. Then
they differ, and that difference is exactly what decides whether formatting grows:

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
describes the same thing worse because it is detached from where the anchor actually is.

Which types expand lives in
[`conformance/schema/mark-types.json`](conformance/schema/mark-types.json), read by both
implementations, with a test on each side that fails if they drift. An unknown type does not expand:
a mark that expands unexpectedly restyles text the user never formatted, while one that fails to
expand costs a repeated gesture.

### Formatting is a layer, not a character attribute

Marks are a separate CRDT layer over the character layer, in the style of Peritext. The character
layer never gained a field for bold, or comments, or suggestions. This is what the second demo GIF
shows:

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

Resolving marks to spans is a sweep over the block list. "The sweep agrees with itself on every
replica" is a test that passes when the sweep returns nothing at all, so it is checked against
`NaiveMarkResolver`: an independent, obviously correct, far too slow implementation that resolves
anchors straight from the block list. Replacing `MarkSet.spans` with `return List.of()` left all
four original mark properties green. It does not survive the oracle.

Twelve mark types share the mechanism: six inline styles, `comment`, two suggestion types, and
`heading` / `quote` / `list`.

### A comment is half in the CRDT and half out

The common misreading of this codebase is "there is a CRDT, so the comments must be in it". They are
not. The **anchor** is a CRDT operation; the **conversation** is rows in Postgres. A range has to
merge without an arbiter under concurrent edits; a sentence written by one person has a single
author and an obvious order, so running it through a merge algorithm buys nothing and costs a lot.

Two consequences are easy to miss in the code. The anchor is created *after* the thread, because it
needs the thread's id, so there is a window where a thread exists with no anchor. And the highlight
and the content travel by different routes at different speeds: the highlight is a CRDT operation
and appears for everyone at once, while the conversation goes over REST and waits for the panel to
refresh. It looks like an inconsistency and is not.

Offline, a comment goes to an outbox with its anchor and is retried on reconnect. An idempotency key
stops a retry of one that did arrive becoming a second thread. This path needs exactly-once, not
merging.

### Suggestions are marks over real text

A suggestion does not modify the document and does not live in a side buffer. Typing in suggest mode
inserts real text carrying a `suggest-insert` mark; deleting adds a `suggest-delete` mark over text
that is still there. Accepting drops the mark. Rejecting generates the operations that undo it,
**server-side**, so a role that may suggest can never write directly whatever the client sends.

That is the payoff of a general mark layer: no new anchor mechanism, no new merge rule, no change to
the character layer.

---

## Undo in a shared document

CodeMirror's own history is switched off. It works on text, so an undo would also take back whatever
other people typed in the meantime. In a shared editor that is not an inconvenience, it is deleting
someone else's work.

Undo therefore operates on your own operations, and the difficulty is that a CRDT operation cannot
be inverted: a deleted character has a tombstone and does not come back to life. Restoring text
means inserting *new* characters with new identifiers. The visible effect is the same; the identity
is not. Yjs does the same thing.

A stack entry remembers a character identifier, not an offset, because an offset recorded at edit
time is stale by the time you press Ctrl-Z. Undoing a formatting change is not "remove the mark"
either: the inverse of "make this bold" is "put back whatever this range had before", which differs
whenever somebody else's mark of the same type was already there. Removing would take away their
formatting too, and they never touched anything.

If the anchor has since been deleted by somebody else, the entry is skipped rather than guessed at.
Putting the text back "roughly there" would appear somewhere random with no explanation.

---

## History, and a diff by identity

The operation log in Postgres is the source of truth. Snapshots are a disposable cache — deleting
every snapshot in the database changes only how long a load takes, and `snapshotsAreOnlyACache` does
exactly that. Time travel comes free: `GET …/history/at?seq=` replays the log to any point. Restore
is recorded as new operations instead of a rewrite, so the history of a restore is itself in the
history.

The diff endpoint compares two versions by character identity, which answers a question a text diff
cannot:

```
   delete a word, then type the same word again

   text diff        "the quick fox"  vs  "the quick fox"   →  no change
   identity diff    1@40..1@44 gone, 2@81..2@85 new        →  a change,
                                                              by a named author
```

Restore uses a common prefix and suffix, not a full Myers diff. A restore is usually a rollback of
one recent edit, so prefix/suffix is short and cheap; Myers would produce smaller operations at a
cost in complexity that has not been worth paying.

---

## Offline, reconnect, and the gap you cannot see

Losing the connection is the normal case. Editing continues into an IndexedDB buffer that survives
the tab closing, and drains on reconnect.

What decides when an operation may leave that buffer is the interesting part. The release is on the
**write** acknowledgement, never on the broadcast. If an instance dies in between, the operation is
on other people's screens but not in the log, and because the buffer never released it, the client
retransmits.

The reverse failure is the one worth designing for, because it is silent. A broadcast to one client
is lost. Nothing errors; the client simply has a hole and does not know it. Watching `server_seq`
for gaps does not help, because the cursor advances on acknowledgements, which go to everybody, so
it looks perfectly current while the operation is missing. So the acknowledgement carries the
operation's **identity**, and the client asks a different question:

```
   ack for 7@41 arrives  →  have I actually handled the operation 7@41?
                            no  →  a broadcast was lost  →  resynchronise
```

That check started as "does my replica contain a character 7@41", which is exact for inserts and
always false for deletes and marks, whose identity comes from the same clock but never names a
character. One backspace by anybody set the gap flag, and the client resynchronised from zero every
15 seconds for ever, making the repair mechanism its own permanent load. It is a bounded window of
handled identities now, with the structural check kept as a second line.

![Client connection states, with the two exits: resynchronising on a detected gap, and a refusal past the collection window](docs/diagrams/connection-states.svg)

`Offline` is not a dead end. `Refused` is the only state the client cannot recover from on its own.
The heartbeat is not what the user experiences: the client listens for the browser's
`online`/`offline` events and for socket errors, which fire immediately, and the broker's timeout is
the backstop for a socket that is dead without saying so.

---

## The cluster

Every backend instance is an ordinary CRDT replica, a peer of the other instances and of the
browsers. No leader, no distributed lock, no sticky sessions.

Redis is a fast path, not a channel with delivery guarantees. Losing a message is a latency problem,
not a correctness one, because the log is authoritative and the anti-entropy sweep repairs the
difference. `ClusterChaosIT` pauses the Redis container mid-write to check that, and
`redisOutageIsRepairedByAntiEntropy` checks the repair specifically. There is also a deterministic
simulation of the whole cluster: the same scenarios driven from a seed instead of thread
interleaving, so a failure arrives with the number that reproduces it rather than with "sometimes".

The cost of having no owner is on the [limitations](#limitations) list: a document open on three
instances lives in three copies.

---

## Who you are

Authorization is only as good as the identity under it, and for most of this project's life there
was none. `POST /api/login` took a name, minted a fresh `UUID`, handed back a token, and checked
nothing. So "Anna" today and "Anna" tomorrow were different people, and documents, memberships and
comments belonged to an identity that stopped existing when the tab closed — sharing works by
invitation *link* partly because there was nobody stable to name. Worse, every rule above it rested
on an unverified claim: `link` visibility grants EDITOR to "anyone signed in", and signing in cost
nothing. The role model underneath was fine. It was resting on air.

So identity is federated. An OpenID Connect provider vouches for the person, and this application
never sees a password.

```
browser ──1── /oauth2/authorization/google ──▶ server ──2──▶ Google
                                                              │
                    ┌──── 4. redirect ?code=… ◀── server ◀──3─┘  (code exchanged,
                    │                                              identity established)
                    ▼
       app ──5── POST /api/session {code} ──▶ server ──▶ session token
```

Two decisions in that picture are worth the words.

**The identity is `(provider, subject)`, never the email.** `subject` is the provider's own
identifier for the account and the only field OpenID Connect promises never changes. An email can be
changed by its owner and, at some providers, released and reassigned, so keying on it would
eventually hand one person another's documents. The email is stored and shown. It identifies nobody.

**Step 5 exists so the token never travels in a URL.** The provider redirects to the *server*, and
the token is needed by the *application* on another origin, so something has to cross in a redirect
— and a redirect target lands in browser history and in the `Referer` of whatever the page loads
next. What crosses is a single-use code: 192 random bits, stored hashed, dead on first use, expired
after a minute. `V13__federated_identity.sql` writes out why the two obvious alternatives, a token
in a fragment and a cookie, are worse here.

The session lives in `localStorage`, once per browser. It was `sessionStorage`, per tab, so two tabs
could hold two people — the demo a collaborative editor is judged on. Federated sign-in removed that
benefit and left the cost, since a second identity used to be a typed name and is now a round trip
to a provider that signs you back into the same account. `?tab-session` keeps the old behaviour for
driving the demo under `COLLABEDIT_DEV_LOGIN`.

That is what forced a sign-out control to exist; there had never been one, because closing the tab
was the sign-out. Three related holes came out of the same review, all invisible until somebody sat
with the product for an afternoon:

- A 401 was noticed in exactly one place, the document-list bootstrap. Everywhere else the user got
  whatever that screen says when a request fails, and the editor's version told them to ask a
  colleague to re-share a document the colleague had not touched. Every REST call routes through one
  handler now.
- The WebSocket authenticates once, at `CONNECT`, and is never re-checked, so a lapsed session kept
  working until the socket dropped — then reconnected with the same dead token, was refused, and
  retried behind "Working offline" for ever. A refused `CONNECT` now asks one cheap authenticated
  question and lets that handler decide.
- Signing out could not be followed by signing in as somebody else. Google gets
  `prompt=select_account` now; GitHub has no equivalent, so switching there still happens at GitHub.

Deliberately still simple: no account page, no linking a Google account to a GitHub one, and no
revocation beyond the token's twelve-hour life, since tokens are signed rather than stored. The
development login survives too — off by default, 404 when off — because a contributor without an
OAuth client of their own still has to be able to run the thing.

---

## Authorization

Two doors, HTTP and WebSocket, and one `PermissionService` behind both. The interesting part is the
HTTP side, where the rule is: declare what you need, or you are not served.

![Every request passes one interceptor: a handler declaring no access requirement is answered with 500 and never runs](docs/diagrams/authorization.svg)

Three things in that picture are the whole of it, and each answers a hole that actually shipped here.

1. **The default branch refuses,** so a new controller starts broken and loud instead of open and
   quiet. That is what `HistoryController` was for a whole checkpoint, handing out the full content
   of any document and letting a reader overwrite it. 500 rather than 404 is deliberate: this is our
   bug, not a caller doing anything wrong, and it should read that way in a log.
2. **The document id comes from the declaration**, not from wherever the handler happens to look.
   That is why creating a comment is `POST /api/documents/{id}/comments` with the document in the
   path. A mechanism that resolves its subject from a declaration cannot be pointed at an endpoint
   that keeps its subject in the body.
3. **The build enforces the same rule as the runtime.** An undeclared mapping fails
   `EndpointAuthorizationArchitectureTest`, and `publicEndpointsAreExactlyTheExpectedOnes` pins the
   exemption list by name. The runtime guard alone would be a rule you discover by breaking
   production; the test alone would be a rule anyone can delete.

What it does not claim: the guard checks that a decision was *made*, not that it was the *right* one.
`@RequiresDocumentAccess(READ)` on something that writes is declared, consistent, and wrong.

On the WebSocket side, `DocumentAccessInterceptor` checks every SEND and SUBSCRIBE frame. It asks not
"do you have access to the document" but "does your role permit *this* operation", because comments
and edits travel the same channel and the distinction has to descend to the operation type. An
unresolvable destination is refused, which it was not at first: Spring's simple broker matches
subscription destinations as Ant patterns, so `SUBSCRIBE /topic/**` resolved to no document, passed
unchecked as if it were a private queue, and then matched every document's broadcast.

---

## Tests

| Layer | Tests |
|---|---:|
| `crdt-core` — property, structural invariants, conformance vectors, snapshot bytes, marks, GC safety | 87 |
| `app` — integration on a real Postgres and Redis: cluster chaos, permissions, presence across instances, the architecture test, the frame interceptor | 227 |
| `frontend` — agreement with the Java implementation, the CodeMirror bridge, snapshot decoding, undo, gap detection, the offline store | 134 |
| end-to-end — two browser contexts, every spec failing on an uncaught console error | 21 |
| **total** | **469** |

The deterministic cluster simulation is counted separately: 1207 is a count of *seeds*, not distinct
assertions, and folding it into the total would turn an honest number into a marketing one. Its
value is that a failure arrives with the seed that reproduces it.

Priority order is property tests of the algorithm, then integration tests against a real Postgres
and Redis through Testcontainers instead of mocks, then chaos and seeded simulation, and only then
ordinary unit tests. Load harness, JMH and the fan-out measurement run nightly, because they take
minutes and measure the machine as much as the system.

The tests get checked too. I mutate production code and check the suite goes red, which has found
three tests that verified nothing:

- Removing the Lamport clock bump left all fifteen property tests green, while four comments claimed
  they existed to catch exactly that. [Full story above.](#what-the-lamport-clock-is-actually-for)
- Removing the treap's aggregate update after a tombstone sailed through convergence, because every
  replica was wrong in the same way. The text agreed; only the positions were wrong.
- `garbageCollectionSparesMarkAnchors` passed with its mechanism switched off. I had anchored the
  mark mid-text, where an older rule already protected the character, so the test was checking the
  old rule and crediting the new one.

A test written to check mechanism X may be passing thanks to mechanism Y, and breaking X is the only
way to find out.

---

## Performance

All numbers from one machine: MacBook, Apple Silicon, 10 cores, JDK 25-ea, 4 GB heap, with Postgres
and Redis in containers alongside. Full tables, methodology, and the claims that turned out wrong
once measured are in [`docs/performance.md`](docs/performance.md).

Index structure, JMH, at 100 000 characters:

| Operation | `ArrayBlockList` | `TreapBlockList` |
|---|---:|---:|
| Position → identifier | 60.2 µs | **0.286 µs** |
| Identifier → position | 40.3 µs | **0.210 µs** |
| Reading the whole text | 310 µs | 429 µs *(treap slower)* |

The shape matters more than the values: for a tenfold larger document the naive scan grows about
13×, the treap about 2×. Reading the whole text is 40% slower in the treap because walking a tree
has worse locality than iterating an array. That runs once per document open while lookup runs on
every keystroke, so it is an easy trade — but a trade, not a free win.

Propagation between clients, from the load harness. All twelve scenarios converge:

| Document | Clients | p50 | p99 |
|---:|---:|---:|---:|
| empty | 50 | 202 ms | 374 ms |
| 10 000 chars | 50 | 135 ms | 230 ms |
| 100 000 chars | 50 | 124 ms | 1299 ms |

Document size barely affects median latency. The p99 tail at 100 000 characters is the rate limiter,
not the algorithm; `docs/performance.md` has the measurement that disproved my first explanation of
it, along with the time I blamed the system for an 8 KiB buffer in my own test client.

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
| **Access revoked mid-session** | Takes effect on every instance, with the 5 s cache TTL as the floor. Subscriptions are re-checked per frame, so the session survives and the permission does not. The client half was wrong until a review found it: the banner appeared above a still-writable editor, so keystrokes were sent, silently refused, and counted as unsaved — the indicator read "Saving…" while the work was discarded. Losing access drops the role to viewer now. | `revokedAccessTerminatesLiveSubscription`, `PermissionClusterInvalidationIT` |

---

## Limitations

Deliberate simplifications, and the ones that turned out worse than I assumed.

- **No paragraph identity, so no move operation.** Two people moving the same paragraph get two of
  it. Structure as marks over a flat sequence is what makes it merge cleanly with everything else,
  and also what makes this unfixable without rewriting the layer everything else stands on. The real
  fix is a tree CRDT for structure with a sequence CRDT inside each node.
- **A document open on three instances lives in three copies,** the declared cost of having no
  document owner. Per-replica memory has never been measured, so `max-loaded-documents: 200` is an
  order-of-magnitude guess rather than a capacity plan. At real scale this wants document-affinity
  routing or a measured eviction policy, and I would want the measurement first.
- **Identity is federated; the account model around it is not.** No account page, no linking a
  Google account to a GitHub one (sign in with the other and you are a different person), no session
  revocation beyond the token's twelve-hour life, no way to delete an account. Switching GitHub
  accounts has to happen at GitHub, since only Google understands `prompt=select_account`. The
  development login still exists, off by default, because a contributor without an OAuth client has
  to be able to run the project.
- **Invitations are multi-use** until they expire or are revoked, which is what "anyone with this
  link" means. Now that a provider gives every user a verified email, an invitation bound to an
  address — single-use, addressed to a person instead of to whoever holds the URL — is the obvious
  next step, and was impossible before.
- **`link` visibility grants edit to anyone holding the address.** That is the mode working as
  intended: possession of an unguessable URL *is* the credential. The bug worth recording is what it
  used to mean. The document list published those URLs to every signed-in user, so "anyone with the
  link" was "everyone", and 132 of 133 documents on a development instance were world-editable.
  Listing and access are separate questions now.
- **The authorization guard checks that a decision was made, not that it was right.** A read
  capability declared on a write endpoint passes.
- **The performance numbers come from a single process,** clients, server and database on one
  machine, so they exclude real network latency and real disk contention.
- **GC reclaims less than I assumed.** Causal stability needs every replica to have seen the
  deletion, so one client that closed its laptop holds the window open for everyone in that document
  until it times out.
- **A cluster message for a document whose replica is still loading is dropped,** and anti-entropy
  cannot detect it afterwards, because the fresh replica is labelled at log head so
  `head > appliedServerSeq` is never true. A client on that instance can stay permanently one
  operation behind. Known, reproduced under load, not fixed: it needs a design decision about the
  cluster load path, not a patch.

---

## Observability

One keystroke produces a single trace across two services: `edit.submit` in the browser, then
`op.receive`, `crdt.merge` and `op.broadcast.local` on the server. The context travels in a STOMP
frame header, because the WebSocket handshake happens once per session and an HTTP header would
describe the session rather than the edit. The write to the log is attached by **links** instead of
nesting, since one write covers many operations from different traces.

Metrics: active sessions, propagation latency percentiles, operations merged, conflicts resolved,
characters reclaimed by GC, cluster messages dropped, frames refused by reason. The
[dashboard](ops/grafana/dashboards/collabedit.json) is a file in the repository, and each panel says
what a bad value looks like — including that a permanently zero count of gap repairs is not good
news but a sign the repair path has never run.

Setup is in [`docs/RUNNING.md`](docs/RUNNING.md).

---

MIT — see [`LICENSE`](LICENSE).
