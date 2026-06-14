# CRDT structure design — decisions at the implementation level

A companion to [ADR-0001](adr/0001-crdt-vs-ot.md). The ADR answers "why RGA". This document answers
"how exactly", and records three findings that emerged only while writing the code and that are
easy to get wrong.

---

## 1. The Lamport clock — what actually follows from it, and what does not

RGA's insertion rule reads: *find the origin, then walk right for as long as the elements
encountered have a higher timestamp than the one being inserted, and insert at the first place
where the element encountered has a lower one*.

The classic argument for the correctness of this rule rests on the timestamp being a **Lamport
clock**, that is, on *if E was created causally after S, then `ts(E) > ts(S)`*. That is what makes
skipping a sibling S entail skipping its whole subtree, so the scan never leaves the origin's
subtree. The implementation does this: on receiving a remote operation the clock is bumped to
`max(clock, operation_lamport + 1)`.

### What could NOT be confirmed

The first version of this document claimed that without bumping the clock the implementation
**diverges**, and gave a counterexample. That claim was too strong and has been withdrawn.

Verification: I removed the clock bump from production code (the mutation `clock = max(clock, 0)`)
and ran a brute-force divergence search — **200 000 random scenarios**, 3–5 replicas, 30 edits
each, with aggressive interleaving of generation and delivery, a full exchange in random order and
double delivery of every operation.

**Result: zero divergences, zero operations stuck in the buffer.**

The counterexample given earlier does not reproduce under manual tracing either: the replicas
arrive at the same result, because the integration function is deterministic with respect to the
*set* of present elements rather than the order of their delivery — and that is enough for
convergence regardless of whether the timestamps respect causality.

### What this means for the code

The clock bump **stays**, but for an honestly named reason:

- It is a requirement of the published RGA correctness proof and standard in every implementation
  of this family I know of.
- Convergence without it is, in this implementation, **observed rather than proven**. 200 000 random
  scenarios are strong evidence, not a proof — and giving up a condition a proof rests on in
  exchange for an empirical result is a bad trade.
- It guarantees the property that arguments about preserving author intent rely on (subtree
  contiguity) — even if convergence itself does not need it.

### What this taught me about tests

The original property-test suite **did not detect that mutation at all** — and would not have,
whether or not it was harmful. The reason was structural: every scenario had the shape "first all
the edits locally, then the whole exchange", in which a replica never generates an operation *after*
receiving somebody else's. The clock-bumping rule was therefore never executed once.

Hence the property `convergenceWithInterleavedDelivery` and the method `ReplicaSet.deliverSome` — a
scenario in which replicas exchange operations while typing. The blind spot was found by
deliberately introducing a mutation into production code and checking whether the tests would catch
it. They did not. A test suite that passes on broken code is not a test suite.

**The same thing has happened twice more**, and differently each time.

*The second time* — the treap aggregates. Removing their update after marking a node as a tombstone
sailed through the convergence tests, because every replica was wrong **in the same way**. The text
agreed; only the positions from the tree were false — a bug that looks like a healthy document with
cursors standing in the wrong places. The answer is a pair of properties comparing the indexed
implementation against the naive one **on query answers**, not on the resulting text.

*The third time* — protecting formatting anchors from GC (CP13). I wrote the test
`garbageCollectionSparesMarkAnchors`, ran it, and it passed. Then I switched off the mechanism it
was supposed to check — and **it still passed**. The reason: I had anchored the mark on a character
in the middle of the text, and such a character is the next block's origin, so a rule that had
existed since CP2 was protecting it. The test was checking the old rule and crediting the new one.
The fix was to anchor on the **last** character of the document, with a non-expanding mark — there
nobody else protects it. Only then did the mutation break it.

The common denominator is uncomfortable: **a test written to check mechanism X may be passing thanks
to mechanism Y** — and there is no way to notice other than by breaking X and checking whether the
test reports it. I now do this for every mechanism that is the only line of defence against silent
data loss.

---

## 2. Why dedup is NOT based on the version vector

The natural reflex: keep a version vector and reject operations already seen. That is incorrect with
a Lamport clock, because **per-replica counters are not contiguous** — the clock jumps when
operations from other replicas arrive. Replica A may generate operations with lamports 1, 2, 47, 48,
103. The record "I have seen everything from A up to 48" cannot tell a hole left by a lost operation
from a hole left by a clock jump.

Instead:

| Operation type | How we detect a duplicate |
|---|---|
| `InsertOp` | We check whether a character with that identifier **is already in the structure**. Exact, and free in memory terms — the structure indexes by identifier anyway. |
| `DeleteOp` | We do not detect it at all. Setting a tombstone a second time is a no-op — delete is idempotent by nature. |

The version vector still exists, but serves **other** purposes: causal stability for GC
([ADR-0005](adr/0005-tombstone-gc.md)) and the "what do I have" exchange on reconnect (CP6). It is
not a correctness mechanism of the core.

This separation is deliberate and worth naming: **detecting gaps in delivery is the transport's job
(`server_seq`, [ADR-0004](adr/0004-skalowanie-poziome.md)), not the algorithm's.** The CRDT core has
to be correct under any order and any repetition — and it is.

---

## 3. The block as a unit, or run-length in practice

The document is not a list of characters but a list of **blocks**:

```java
Block { replicaId, startLamport, String text, Id originLeft, boolean deleted }
```

The character at index `i` inside a block has the identifier `(replicaId, startLamport + i)`, and its
origin is the character `i-1`. That makes typing 20 characters in a row **one** object and one
operation instead of 20.

Two invariants keep this working:

1. **A block is entirely alive or entirely deleted.** Deleting part of a block splits it first. The
   alternative (an array of per-character flags) complicates every indexing operation and saves
   little.
2. **The scan on insertion is coarse — by blocks, not by characters.** That is correct because the
   characters in a block have increasing lamports and are causally linked: if the first character of
   a block has a higher timestamp than the one being inserted, so do all the rest. There is never a
   need to stop in the middle of a block — unless the inserted character's origin *is* in the middle
   of a block, in which case the block gets split first anyway.

**Merging back:** when inserting at the end of a block from the same replica, with a contiguous
lamport and a matching origin, we append to the existing block instead of creating a new one. This
is the fast path for the most common pattern — ordinary typing.

> **Since CP18** a deleted block may additionally drop its `text` while keeping its identity and its
> length. Both invariants above survive unchanged: a content-dropped block is still entirely
> deleted, and the scan still works on lamports rather than on characters. Details in
> [ADR-0005](adr/0005-tombstone-gc.md), the CP18 section.

---

## 4. Two index implementations, one algorithm

`RgaDocument` does not know how the block list is stored — it receives it through the `BlockList`
interface. There are two implementations:

| Implementation | Insert a block | Find by offset | Find by identifier |
|---|---|---|---|
| `ArrayBlockList` | O(n) | O(n) | O(n) |
| `TreapBlockList` | O(log n) | O(log n) | O(log n) |

Both pass **the same** property test suite, including the test `naiveEqualsIndexed`, which feeds
both an identical random sequence of operations and compares the results step by step. That is proof
that the optimisation changed nothing in the behaviour — rather than a hope that it did not.

The order of the work is deliberate: first the naive version and a full test suite, then measurement
(JMH), and only then the optimised version. The commit history shows that sequence, because it is
more honest than dropping in a clever structure straight away.
