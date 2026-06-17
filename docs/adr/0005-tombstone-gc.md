# ADR-0005 — Tombstone garbage collection

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP6 (implementation), CP8 (overhead measurement)

---

## Context

A consequence of ADR-0001: deleting a character in RGA does not remove the node, it marks it as a
tombstone. The node has to stay, because an operation from another replica may point at it as
`originLeft` — removing it would mean an incoming character has nothing to attach to.

The effect: **the structure grows monotonically with the number of edits performed, not with the
size of the text.** A document in which 10 MB of text was written and deleted, and which ended up
empty, still occupies memory proportional to 10 MB. This is not a theoretical problem — it is the
main practical objection to CRDTs for text.

The question: **when is it safe to remove a tombstone?**

---

## Options considered

### Option A — Never remove them

**For:** trivially correct, zero code.
**Against:** a long-lived document becomes unusable. Memory use and snapshot size grow without
bound. Indefensible in a project meant to demonstrate an understanding of CRDTs — because this is
precisely where the difficulty lies.

### Option B — Remove after a time limit (e.g. a tombstone older than 24 h)

**For:** simple, predictable.
**Against:** **incorrect.** Time has nothing to do with whether some replica still needs the node.
A client offline for 25 h comes back with an operation pointing at a deleted tombstone → the
operation has nothing to attach to. Time is a proxy for causality, and a bad one.

### Option C — Remove once causal stability is reached

A tombstone may be removed once **every replica that could still send an operation has already
seen its deletion**. At that point nobody can send an operation anchored to that node.

Formally: for a document we maintain `stable = min` across all active replicas' version vectors.
Operations entirely below `stable` are causally stable — no future operation can be concurrent
with them.

**For:** correct by definition.
**Against:** it requires knowledge of every replica that "could still send an operation" — and in
an open system that cannot be known for certain. A replica offline for a month may come back. The
set of active replicas must therefore be **closed by an administrative decision**, and that is the
heart of this decision.

### Option D — Reconstruction: compact the whole document and force a full resync on everyone

Rewrite the structure without tombstones, assign new identifiers, make every client fetch the
state afresh.

**For:** maximum compression.
**Against:** this is not GC, it is a reset. Every client with unsynchronised changes loses them. It
makes sense as an emergency operation, not as a routine.

---

## Decision

**Option C — GC based on causal stability — with an explicit, documented boundary that closes the
set of replicas.**

### Mechanism

1. The server maintains, per document, a map `replicaId → version vector` for replicas considered
   **alive**: connected now, or disconnected for less than the **GC window**.
2. `stableVersion` = the component-wise minimum of those version vectors (plus the version vector
   of the state stored in Postgres — the database is a replica too).
3. A tombstone whose deletion operation is entirely below `stableVersion`, and whose node is not
   the `originLeft` of any live node, is **physically removed** at the next snapshot (ADR-0003).
   GC is part of snapshotting, not a separate process — a snapshot rewrites the whole structure
   anyway, so compaction is free there.
4. **GC window: 7 days.** Justification: it must be clearly longer than a realistic offline
   session (working while travelling, a weekend), and at the same time finite, because otherwise
   we are back to option A. A week covers "I went on holiday with the laptop open"; a month
   changes practically nothing for the user while extending the lifetime of garbage.

### What happens to a client that returns after the GC window — and why we do NOT fake a merge

A client offline for more than 7 days comes back with operations pointing at an `originLeft` that
no longer exists. **There is no honest way to merge them** — the information about where those
characters belong was irretrievably lost by GC.

Reactions considered:

- *Append at the end of the document* — the merge "succeeds", but the text lands in a random
  place. Silent content corruption. **Rejected.**
- *Ignore the operations* — silently losing the user's work. **Rejected.**
- *Reject and export* — **chosen.** The server rejects operations whose `originLeft` is outside
  `stableVersion`, responds `RESYNC_REQUIRED`, the client fetches the current state and **saves
  its local version as a separate document "Recovered draft (offline since …)"** with an explicit
  message to the user.

Losing work without notice is far worse than an explicit "I cannot merge this, here is your copy".
This decision is described in the README's Failure modes section, because it is a real limitation
of the system rather than an implementation detail.

### The actual reach of GC — verified in CP6, and the result is worse than assumed

The condition "the node is not the origin of any existing block" is necessary for correctness, but
turned out to be **considerably more limiting than I had assumed when writing this decision**.

In RGA every block remembers its left origin. Deleting a character in the middle of the text splits
the block into three parts, and the part after the tombstone is anchored to it. The tombstone is
therefore an origin and **must not be removed — regardless of how long ago it was created or how
causally stable it is**.

The practical consequence: collectable tombstones are only those nothing references, which in
practice means **the tail of the document** and fragments where the whole neighbourhood was
deleted. Text deleted in the middle of a paragraph stays in the structure forever.

**Why not "re-point" references at the tombstone's origin.** Tempting, and under causal stability
it looks safe. It is not: RGA's insertion rule compares the timestamps of elements encountered
after the origin, so removing a node from the scan path changes where a *future* insertion
anchored to the same origin will land. A replica after compaction and a replica before compaction
would place the same new character in different places — that is, they would diverge. Causal
stability says everyone eventually knows; it does not say they know simultaneously.

**What the right answer would be.** What Yjs does: do not remove the node, **drop its content**,
leaving only the length. The structure and all references stay intact, and the memory reclaimed is
proportional to the size of the deleted text — that is, to what actually hurts. The overhead of
the nodes themselves remains, but it is an order of magnitude smaller.

Not implemented — it requires changing the `Block` representation and invalidates the snapshot
format and the conformance vector corpus. It goes into "what I would do differently in production"
as a **known, named gap**, not as an oversight.

### Metrics (CP7)

- `doc_tombstone_ratio` — tombstones / all nodes. A high value sustained over time means GC is not
  working (for example, one hung client blocking `stableVersion` for the whole document — a real
  scenario).
- `crdt_gc_removed_nodes_total`
- `crdt_gc_blocked_by_replica` — which replica is holding `stableVersion` back the most. Without
  it, diagnosing "why is GC stuck" is guesswork.

---

## Consequences

### Positive
- GC is **correct by definition**, not heuristic — there is no scenario in which we remove a node
  somebody still needs (within the window).
- Zero additional execution cost: GC happens during snapshotting, which rewrites the structure
  anyway.
- The system's boundary is explicit and measurable rather than hidden.

### Negative
- **One slow or hung client blocks GC for the whole document.** `stableVersion` is a minimum — a
  replica that is connected but not confirming progress stops compaction for everyone. Mitigation:
  a client that has not confirmed progress for **60 s** despite an active connection is treated as
  unresponsive and excluded from the `stableVersion` computation (while its connection is left
  alone). The value is chosen as ~2× the heartbeat tolerance from ADR-0002, so that we do not
  exclude a client earlier than the transport considers it alive.
- **A client offline > 7 days loses the ability to merge** and gets a separate document. A
  deliberate, explicitly communicated limitation.
- **GC increases the complexity of snapshot serialisation** — the snapshot must carry
  `stableVersion`, because the instance loading it needs to know what is already gone.
- The 7-day window is a **guessed value** based on intuition about user behaviour, not on data. In
  production I would derive it from telemetry of offline session lengths. Written down explicitly
  so as not to pretend the number was computed.

### Correction: for five checkpoints the window did not exist in the code

Point 1 of the mechanism above defines the replicas that constrain GC as *"connected now, or
disconnected for less than the GC window"*. **Only the first clause was implemented.**
`PresenceService.leave` called `forgetReplica`, which deleted the replica's entry outright, so a
disconnected client constrained nothing at all. `collabedit.gc.window-days: 7` sat in
`application.yml` with a paragraph of justification, and in `CollabEditProperties` as a field, and
**no code read it**.

The gap between this document and the behaviour was five orders of magnitude. The README describes
`RESYNC_REQUIRED` as what happens to a client "offline longer than the GC window (7 days)"; what
actually happened is that closing a laptop removed that client from the calculation immediately, so
the tombstones its unsent work was anchored to became collectable as soon as the clients still
present moved past them and a snapshot ran. Minutes rather than days — on the one path in this
system that refuses a user's work by design.

Two things are worth recording about how it survived so long:

1. **Every test agreed with the code rather than with this document.** `GarbageCollectionIT`
   constructs its late replica as one that never reported progress at all, so it was never in the
   map and deleting-versus-keeping made no difference to it. The test asserts the refusal
   *happens*, which is the interesting half, and says nothing about *when* — so the window could
   be absent without a single assertion noticing.
2. **The configuration made it look implemented.** A named, justified, documented value that
   nothing reads is worse than an absent one: it answers the question "is this handled?" with a
   yes.

Now implemented as written here: a departed replica keeps its last known version vector for the
window, and only then is pruned. This is also the semantically correct rule rather than merely the
documented one — what may be collected is what *everyone who might come back has already seen*,
and forgetting a departed replica collects precisely the things it has not.

The two timeouts stay separate, because they answer different questions: silence from a client
that is **still connected** is evidence something is wrong with it (excluded after
`unresponsive-replica-timeout-ms`), while silence from one that has **disconnected** is expected
and is the case the window exists for. `GcWindowTest` pins both.

**What this costs, stated plainly:** GC now collects strictly less than it did, and for up to a
week after anyone disconnects. That is the trade this ADR chose; the previous behaviour was not a
better trade, it was this trade not being made.
- The mechanism does not protect against a document that is **continuously** actively edited by a
  client holding old version vectors — theoretically GC might never fire. **To be checked in CP8**,
  whether that is a realistic scenario under synthetic load.

### To be verified in CP8
- The actual memory overhead: MB for a document with 1 MB of text and a history of 10 rewrites,
  before and after GC.
- Whether GC actually fires under a typical usage pattern, or `stableVersion` stands still.
- The time cost of compaction when snapshotting a large document — whether it introduces a
  noticeable pause.

---

## Verified in CP13 — and it is bad news

Rich text adds a **third condition** to the list of "when a tombstone must not be removed":

1. it is not causally stable (the original condition),
2. it is the origin of an existing block (added during implementation),
3. **it is a formatting mark's anchor** (CP13).

The third condition follows directly from the construction in ADR-0010: a mark is a range between
anchors pinned to characters, and an anchor on a deleted character has to survive — otherwise
deleting neighbouring text would lose the formatting. The price is that **every formatting range
pins two tombstones**, and usually in the middle of the text rather than at the tail.

GC was already weak: the README has said plainly since CP6 that in practice only the tail of the
document is collectable. Now it is **weaker in proportion to the amount of formatting**. A heavily
formatted, heavily edited document reclaims less than the same document without formatting.

### What this does NOT change

The direction of the solution, described in the README since CP6, stays the same and only becomes
more urgent: **dropping the CONTENT of deleted nodes while keeping them in the structure** (what
Yjs does). A node kept as an empty anchor point costs a dozen or so bytes instead of the whole
text, and all three conditions above remain satisfied — because all three need the IDENTITY of a
character, not its content.

The CP13 plan pointed at raising the snapshot format version as the cheapest moment for this
change, and **that moment was missed**: the format was raised to version 2 without changing the
block representation. The reason was the absence of a measurement — I do not know how much memory
tombstones actually take in a document with formatting, so I do not know whether changing the
representation is worth its cost. The measurement belongs to CP15; if it comes out badly, the next
opportunity is the next format version bump.

### A safeguard that almost did not work

The test guarding the third condition, in its first version, **passed with anchor protection
switched off as well**. It anchored in the middle of the text, and a character in the middle is the
origin of the next one, so the second condition already protected it. Caught by mutation; the test
now anchors on the last character of the document, which is the origin of nothing.

This is the third time in this project that a mutation has revealed a test not checking what it
declares — and the second in which the cause was that the property under test was accidentally
guaranteed by another, earlier mechanism.

---

## Measured in CP15 — it is worse than the section above says

The measurement the previous section was waiting for is done
([`MarkCostMeasurement`](../../crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java)).
The result refutes the phrase "**every formatting range pins two tombstones**" — the true sentence
is: **every formatting range pins a whole run-length block**.

1000 characters, all text deleted, 100 marks, a fully stable version vector:

| How the text was written | Without marks | With marks |
|---|---:|---:|
| in one run (1 block) | 1000 | **0** |
| character by character (1000 blocks) | 1000 | 800 |

The reason is in the code and had been there from the start: `RgaDocument.gc` iterates over
**blocks**, and `isAnyCharReferenced` rejects a block in which even one character is an anchor. The
third condition is therefore not a condition on a character — it is a condition on the block that
character belongs to. With text written in one run, one anchor rescues the whole document.

This is not a bug in the anchor protection: the protection must be conservative, and splitting a
block during collection would require repairing the origin chain — the right-hand part of a block
anchors to the last character of the left, so dropping a prefix invalidates the origin of what
remains. The cheap workaround (trimming only the tail beyond the last anchor) gives nothing in the
case that hurts: anchors scattered across the whole length.

**The consequence for the decision:** the direction does not change, but it stops being optional.
Dropping the content while keeping the node in the structure solves this problem **entirely**,
because then a block does not have to be collected to give back memory — it only has to lose its
text. To be done at the next snapshot format version bump; this time with a number in hand rather
than a hunch.

**What the measurement does not say:** what this means in a real document. Blocks in a real session
are neither one run of a thousand characters nor a thousand one-character blocks — they arise from
several people's edits interleaving, and have a size I did not measure. Both numbers in the table
are extremes, and I present them as extremes rather than as a forecast.

---

## CP18 — the representation changed, and the number went from 0% to 100%

The section above ends with "the direction does not change, but it stops being optional". CP18 did
it. This section records what actually came out — including what turned out cheaper than assumed,
and what the plan guessed wrong.

### The change

A deleted block may now **drop its content and stay in the structure**. It keeps its identity, its
length, its origin and its position; it loses only the characters nobody can see anyway.

The justification was already written out above and only had to be taken literally: all three
reasons for keeping a tombstone — being the next block's origin, being a mark anchor, causal
stability — need the **identity** of a character, never its content. The mistake was never in the
conditions. It was in treating "I must keep the identity" and "I must keep everything" as the same
requirement.

### Measured, the same rig as CP15

`MarkCostMeasurement`, unchanged apart from the assertions: 1000 characters, everything deleted,
100 marks (= 200 anchors), a fully stable version vector.

| How the text was written | Before CP18 | After CP18 |
|---|---:|---:|
| in one run (**1** run-length block) | **0%** | **100%** |
| character by character (1000 blocks) | 80% | **100%** |

The first row is what this was done for. The second is the check that nothing was traded away in
the process: there GC could already remove whole blocks, so dropping content had to arrive
**beside** that mechanism rather than in place of it. A separate assertion guards it, because a
drop in that row would mean the cheaper operation had displaced the more complete one.

The snapshot cost, same measurement: **107 B → 108 B** for a 50 000-character document, with the
per-mark overhead unchanged at 8 B. One bit per block, rounded up to a byte.

### What was cheaper than the plan assumed

**Migration is free, and that is the second time ADR-0003 has paid for itself.** A snapshot in a
version this code does not recognise is *ignored*, not migrated — the state rebuilds from the
operation log, more slowly and correctly. Raising the format from 2 to 3 therefore required not a
single line of migration code.

**The dual-implementation debt did not materialise this time.** The README lists "two
implementations of one algorithm" as a cost paid on every algorithm change, and CP13 paid it in
full. CP18 did not: the TypeScript client has neither GC nor snapshot decoding — it replays the
operation log — so the entire change lives in a layer the client does not have. Worth recording,
because it is a counterexample to the project's own generalisation: the debt is proportional to
**how much of the algorithm the client actually implements**, not to the size of the algorithm
itself.

### What the plan guessed wrong

The plan called this "the only change in the core" and priced it at 3–5 days on the assumption that
the real cost would be the TypeScript twin and the treap aggregates. The treap required **nothing**:
it already read `block.length()` rather than the text, so turning that method from a computed value
into a field was invisible to it. The real cost sat in one place the plan did not mention — in
every construction of a `Block` from another `Block`, that is, on the snapshot and restore paths,
where reconstructing through the public constructor throws on a content-dropped block.

### The missed opportunity — settled

This ADR previously recorded that CP13 missed the cheapest moment for this change (the format bump
to version 2) for want of a measurement. With the change made, the cost can be stated rather than
estimated: **it was small.** The work touched `Block`, `RgaDocument.gc` and `SnapshotCodec`, and
the existence of the mark layer made none of it harder. Doing it in CP13 would have saved one
format version bump and nothing else.

Which puts the CP13 decision in a better light than it stood here before, and that is worth saying
plainly: without the CP15 measurement the change would have been made on a guess about where the
memory was going — and the guess recorded in the plan ("two tombstones per mark") was wrong by a
whole level of structure.

### Still true after CP18

- The GC window (7 days) is still a guessed value, not derived from telemetry.
- A tombstone in the middle of the text is still the next block's origin and still must not be
  removed. CP18 does not change that — it makes it cost a fixed handful of bytes instead of the
  text it used to hold.
- Nothing reclaims the block objects themselves. A document edited long enough still accumulates
  entries in the block list, and that is now the dominant remaining cost.
