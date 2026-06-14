# ADR-0001 — Synchronisation algorithm: RGA (CRDT) instead of OT

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP2 (implementation), CP8 (performance verification)

---

## Context

The core problem: two or more users edit the same text document at the same time, without
central locking. Two edits may reach different replicas in different orders, and yet every
replica must end up with **identical** text — text that preserves the intent of both authors,
not merely some deterministic result.

The naive approaches, and why they collapse immediately:

- **Last-write-wins on the whole document** — user A types in paragraph 1, B in paragraph 5, and
  B's save overwrites the whole document. A's work disappears without trace. This is not an edge
  case; it happens the first time two people use it in parallel.
- **LWW per character / per position** — a position (offset) is not a stable identifier. When A
  inserts a character at position 3, every operation from B referring to positions > 3 is off by
  one. Offsets require transformation — which is to say, OT.
- **Locking the document or a section** — it works, but it stops being a collaborative editor.

The real choice is between two families of algorithms: **Operational Transformation (OT)** and
**Conflict-free Replicated Data Types (CRDT)**.

Constraints of this project that bear on the decision:

1. The algorithm is to be **written from scratch**, not imported (`CLAUDE.md`, "What to avoid").
   That rules out using Yjs/Automerge/ShareDB as a black box.
2. Correctness is to be **proven with property-based tests**, not demonstrated with a handful of
   manual cases.
3. It must work offline with a later merge — a client may be disconnected for minutes and come
   back with a local buffer of operations.
4. It must be distributable across many backend instances (ADR-0004).

---

## Options considered

### Option A — Operational Transformation (OT)

Every operation is expressed in offsets (`insert(pos, char)`, `delete(pos)`). When a concurrent
operation arrives, we transform it against the operations its sender had not yet seen, adjusting
the offsets. Historically used in Google Wave and Google Docs.

**For:**
- Operations are small and cheap on the wire — `insert(42, 'a')` is a dozen or so bytes.
- Zero memory overhead in the structure: the document is a plain string. No tombstones.
- Maps naturally onto the editor's model (offsets), so UI integration is trivial.

**Against:**
- Correctness requires satisfying properties **TP1 and TP2**. TP2 (transforming three concurrent
  operations gives the same result regardless of order) is notoriously hard — the literature
  documents TP2 bugs in published, peer-reviewed OT algorithms, found years after publication.
  This is not a problem of "programming carefully".
- OT systems in practice sidestep TP2 by introducing a **central server that imposes a total
  order** (this is how Google Docs works). That works, but:
  - the server becomes a serialisation point — at odds with ADR-0004 (many peer instances),
  - being offline for longer than a trivial period requires a long chain of transformations on
    return,
  - and above all: "the algorithm is correct because the server enforces an order" is a much
    weaker property than "the algorithm is correct mathematically".
- Property-based testing is less natural: you cannot randomly permute delivery order when
  correctness depends on the server fixing that order.

### Option B — CRDT, the Logoot / LSEQ family (dense identifiers)

Every character gets a positional identifier from a dense, ordered set (a sequence of numbers
compared lexicographically). Between any two identifiers a new one can always be generated. No
tombstones on insert.

**For:**
- Deleting a character is an actual deletion — no tombstone overhead on delete.
- Conceptually simple: compare identifiers, sort.

**Against:**
- **Identifier growth.** When typing sequentially into the same place (that is: ordinary
  left-to-right typing) identifiers grow in length. In pathological cases a single character's
  identifier exceeds the size of the character itself by orders of magnitude. LSEQ mitigates this
  with a level-dependent allocation strategy, but does not eliminate it.
- **A strong interleaving anomaly.** Two users typing different words concurrently in the same
  place can get a result with letters interleaved character by character (`hweolrllod`). That is
  the worst possible failure mode — it loses no data, but it produces nonsense.

### Option C — CRDT, the RGA / YATA family (relative identifiers, tombstones)

Every character has an identifier `(replicaId, counter)` and a pointer to **its left neighbour at
the moment of insertion** (`originLeft`). The order among siblings inserted after the same origin
is settled by a deterministic rule (here: descending by `(counter, replicaId)`). A deletion is a
tombstone, because the node may be someone's origin.

RGA underpins, among others, Yjs (YATA is a variant with an additional `originRight`) and
Automerge.

**For:**
- **Convergence is a property of the structure, not of the protocol.** Operations are commutative
  and idempotent; any delivery order gives the same result. That is exactly the property a
  property-based test permuting delivery order can prove — and it is the most important test in
  this project.
- No need for a central arbiter → this closes ADR-0004 (every backend instance is simply another
  replica, and unreliable broadcast over Redis is safe by definition).
- Offline comes free: buffer the operations and replay them on return, with no transformation.
- Identifiers are fixed size (`int` + `long`) and do not grow with document length.
- Sequential typing compresses into blocks (run-length), because consecutive characters have
  consecutive counters and a shared origin.

**Against:**
- **Tombstones grow without bound** absent garbage collection. A document in which 1 MB of text
  was written and deleted holds 1 MB of invisible nodes in memory. That needs its own
  non-trivial decision — ADR-0005.
- **Offset ↔ identifier mapping.** The editor says "insert at position 4271"; the CRDT needs a
  node identifier. Naively that is an O(n) scan on every keystroke. It requires an indexed
  structure (ADR in CP2 / plan §2).
- **RGA is not maximally non-interleaving** — see "Known limitation" below.
- Operations are larger than in OT (they carry an identifier + origin rather than a bare offset).

### Option D — Fugue (maximally non-interleaving)

A 2023 CRDT variant (Weidner & Kleppmann) designed to eliminate the interleaving anomaly in both
directions while preserving RGA's properties.

**For:** it solves RGA's only serious quality defect.
**Against:** substantially more work for no difference in what the project demonstrates
(understanding of convergence, tombstones, causality). Cost estimated at +2–3 days out of a
~14-day budget.

---

## Decision

**We implement RGA (option C)** — our own implementation in Java (`crdt-core`, a module with no
Spring dependencies whatsoever) plus a twin implementation in TypeScript on the client.

The key design choices:

| Element | Choice | Why |
|---|---|---|
| Identifier | `(replicaId: int, counter: long)` | Fixed size, no growth as in Logoot |
| Anchor point | `originLeft` | The classic RGA variant; `originRight` (YATA) adds complexity with no benefit at our scope |
| Sibling ordering | descending by `(counter, replicaId)` | Deterministic and total with no arbiter; `replicaId` breaks counter ties |
| Deletion | tombstone | The node may be the origin of an operation we have not seen yet |
| Representation | run-length blocks, split on insertion into the middle | Without it a 100k-character document is 100k objects on the heap |
| Dedup / causality | version vector + buffer of orphaned operations | A retransmission after reconnect must not duplicate text |

**Fugue (option D) rejected** on budget — deliberately, and recorded in the README's "what I
would do differently" section.

**OT (option A) rejected** primarily because its correctness in practice rests on a central
ordering server, which contradicts the model of many peer instances in ADR-0004 — and
secondarily because TP2 is a trap that peer-reviewed publications have fallen into.

---

## Known limitation: the interleaving anomaly

RGA does **not** guarantee that concurrently typed character sequences stay contiguous in every
case. The classic problematic scenario is concurrent right-to-left insertion (for example, two
users repeatedly pressing a key in the same place while moving the cursor back).

The approach in this project — **we do not hide it**:

1. The property test `concurrentTyping_staysContiguous` checks the non-interleaving property for
   sequential typing (RGA passes it).
2. A separate test `knownLimitation_backwardInterleaving` constructs the case where RGA does
   interleave, and **is marked as an expected limitation** with a link to this ADR. It stays in
   the repository as executable documentation of the algorithm's boundary.
3. The README states that the solution is Fugue, and why it did not fit in scope.

A test that documents a defect is worth more than its absence. Removing it would be pretending
the algorithm has a property it does not have.

---

## Consequences

### Positive
- Convergence can be **proven** with a property-based test over random delivery permutations —
  not merely demonstrated on a few cases. This is the project's main technical artifact.
- The backend scales horizontally without sticky sessions and without a distributed lock
  (ADR-0004).
- Offline support needs no separate mechanism — it follows from the algorithm.

### Negative
- **Tombstones require GC**, and GC in a CRDT requires causal stability. That is an entire
  separate decision (ADR-0005) and a real failure mode for clients that stay offline a long time.
- **The algorithm exists in two implementations** (Java + TypeScript). That is a debt: two
  opportunities for a bug, one specification. Mitigation — a corpus of conformance vectors
  generated by the Java tests and replayed by the TS suite in CI. Divergence cannot pass
  silently, but it still *doubles the cost of every change to the algorithm*.
- **Operations are larger than in OT.** The effect on throughput is to be measured in CP8, not
  guessed.
- **The naive offset↔id mapping is O(n)**, so the first version will be slow on large documents.
  That is deliberate: we want to measure before we optimise.
- **The interleaving anomaly** in selected scenarios (see above).

### To be verified in CP8
- The real degradation point: at what document size and how many concurrent users the p95
  propagation latency stops being acceptable.
- The actual memory overhead of tombstones before and after GC, in MB, on a document with a
  known history.
- Whether run-length blocking gives the expected reduction in object count under a realistic
  editing pattern (and not only under synthetic continuous typing).

---

## Sources

- Roh, Jeon, Kim, Lee — *Replicated abstract data types: Building blocks for collaborative
  applications* (RGA, 2011)
- Nicolaescu, Jahns, Derntl, Klamma — *Near Real-Time Peer-to-Peer Shared Editing on
  Extensible Data Types* (YATA, 2016)
- Weidner, Kleppmann — *The Art of the Fugue: Minimizing Interleaving in Collaborative
  Text Editing* (2023)
- Sun, Ellis — *Operational Transformation in Real-Time Group Editors: Issues, Algorithms,
  and Achievements* (1998) — the source of TP1/TP2
- Kleppmann, Beresford — *A Conflict-Free Replicated JSON Datatype* (2017)
