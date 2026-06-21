# ADR-0010 — Text formatting as a separate CRDT layer

- **Status:** Accepted
- **Date:** 2026-08-15
- **Applies to checkpoints:** CP13 (implementation), CP14 (comments reuse the anchors),
  CP15 (measurement), CP20 (complexity)

---

## Context

[ADR-0008](0008-scope-extension.md) widened the scope to include rich text, on the grounds
that it is **an algorithmic problem, not a product one**. This document describes what that problem
is and why the solution that looks obvious is wrong.

There is one situation to resolve, and it serves as the whole specification:

> Ania bolds a range of text. Bartek — not yet seeing her change — types text into the middle of
> that range. What should happen to the formatting of the inserted text?

The answer a user expects: the inserted text is bold, because it ended up inside a bold fragment.
The answer a naive implementation gives: it is not, because at the moment the operation was created
the bolding had not reached Bartek.

---

## Options considered

### Option A — Formatting as a character attribute

Every character carries a set of marks. Bolding a range is an operation setting the attribute on
the listed characters.

**For:** trivial to implement, no new concepts, zero cost to compute the appearance.

**Against — and this is decisive:** **it does not solve the situation from the context.** Ania's
operation lists the characters that existed when it was created. Bartek's characters are not in
that list and have no way of getting into it, because they were created in parallel. The result: a
hole in the bolding that nobody made and that cannot be explained to the user.

This is not a problem to work around by "attaching the attribute on insert", because the inserting
replica does not know about the bolding — it will find out in a moment.

### Option B — A range stored as a pair of offsets

The mark holds the start and end positions.

**Against:** offsets drift with every edit by somebody else. It is the same mistake
[ADR-0006](0006-editor-layer.md) rejected for cursors, only more dangerous: a cursor in the
wrong place is visible immediately, whereas formatting in the wrong place looks like the author's
decision.

### Option C — A range between anchors pinned to characters (Peritext)

A mark is a separate operation: `(type, value, start anchor, end anchor)`, where an anchor points at
a **character** and a **side** of that character. The appearance of the text is computed at render
time rather than stored.

**For:** it solves the situation from the context with no special case — anchors move with the
text, so text inserted into the middle of a range **is** in the middle of the range, regardless of
delivery order.

**Against:** more concepts, computing the appearance on every render, and — what turned out to hurt
most — worse GC effectiveness.

---

## Decision

**Option C.** Together with two consequences that follow from it:

### The side of an anchor decides expansion

An anchor "after character X" and one "before character Y" point at the same place in the text `XY`
— **until somebody inserts something between them**. Then they diverge:

```
text:              a [b] c          ("b" is bold)

end = AFTER "b":     typing "x" → a [b] x c     — the bold does NOT grow
end = BEFORE "c":    typing "x" → a [b x] c     — the bold DOES grow
```

The entire difference between "bold grows when I type at its end" and "a link does not grow" is the
choice of one side of one anchor. The alternative would be a separate "should it expand" field,
which describes the same thing — only detached from where the anchor actually is, and therefore
worse.

**Expansion is a property of the mark TYPE, not of the operation.** The user does not make this
decision on every bolding; they have an expectation tied to the kind of formatting. If it were a
property of the operation, two bolds in one document could behave differently and the user would
have no way of predicting which.

### Conflicts are settled by an order we already have

For a given character and mark type, the winning operation is the one with the highest
`(lamport, replicaId)` among those covering that character. This is **the same total order** RGA's
insertion rule uses — with no new arbitration mechanism and no new assumption about clocks.

Removing formatting is a **range** operation, not a reference to a specific application. The reason:
removing is not undoing somebody else's operation. Two people may bold overlapping ranges and a
third unbold their intersection — at which point there is no single operation to point at.

---

## Consequences

### Positive
- The situation from the context works with no special case. It is checked directly by the
  conformance vector `201-mark-inherited-by-concurrent-insert`.
- The character sequence **did not change by a single field**. `Block` and `BlockList` in CP13 are
  identical to CP2 — which is the best proof that the layers really are separate.
- The anchor layer is ready for comments (CP14) with no additional work: a comment is an anchored
  range, which is exactly the same concept.
- Raising the snapshot format version turned out to be free thanks to the ADR-0003 decision (an
  incompatible snapshot is ignored, not migrated).

### Negative

- **Tombstone GC became weaker in proportion to the amount of formatting.** Every range pins two
  tombstones, usually in the middle of the text. Details and the direction of a solution in
  [ADR-0005](0005-tombstone-gc.md), the "Verified in CP13" section. **Removed in CP18** — see the
  CP18 section of that ADR.
- **Computing the appearance is O(m²)** with `m` mark operations. At hundreds of marks that is a
  fraction of a millisecond; at tens of thousands it is noticeable. The right solution is a sweep
  with a heap of active ranges, `O(m log m)`. **Not done**, because it requires a measurement I do
  not have. Deferred to CP15. **Done in CP20** — see the section at the end.
- **A range ending at the very end of the document cannot grow**, because there is no character to
  pin an expanding anchor to. Peritext uses an end-of-document sentinel there; there is none here,
  so a bold placed on the last word will not cover text appended after it.
- **Text typed in place of a deleted formatted fragment does not inherit the formatting.** The
  insertion lands before the tombstones (RGA's rule), that is, outside the range. Predictable and
  following directly from the algorithm, but different from the behaviour of offset-based editors.
  Pinned down by a test, so that changing it requires a conscious decision.
- **The dual-implementation debt grew.** The mark layer exists in Java and in TypeScript, because
  the client has to compute the appearance offline. The conformance corpus protects against
  divergence in operations **already created**, but not against divergence in the table of
  expandable types — that table is used when CREATING operations, so the vectors do not see it.

  **That sentence originally read "a separate unit test on both sides guards it" and was untrue** —
  no such test existed on either side. It came out during a code review. It exists now: both sides
  read the same file [`conformance/schema/mark-types.json`](../../conformance/schema/mark-types.json),
  so changing one of them fails that side's tests. The more general conclusion: **an ADR describing
  a safeguard is worth exactly as much as the safeguard itself, and no more** — and this particular
  description stood in for something that did not exist for two checkpoints.

### Verified in CP15

Measurement: [`MarkCostMeasurement`](../../crdt-core/src/test/java/dev/collabedit/crdt/MarkCostMeasurement.java).

**What computing the appearance costs.** A 50 000-character document, one `formatting()` call:

| Marks | Time |
|---:|---:|
| 100 | 0.6 ms |
| 400 | 0.8 ms |
| 1600 | **5.3 ms** |

The growth is **clearly superlinear** (×6.4 for a fourfold increase in marks between the last two
measurements) and matches what the code shows: on every computation `MarkSet` walks all the marks
and, for each segment, searches linearly for a winner. The first ratio (×1.5) means nothing — at
hundreds of microseconds, noise dominates.

The practical significance is greater than it looks, because `formatting()` is called **on every
content change**, that is, on every keystroke. 5 ms per keystroke at 1600 marks is already a
visible delay; at hundreds it is not. The usable limit of the current implementation is therefore
**on the order of hundreds of marks per document**, and that is a number I did not have before the
measurement.

The right solution: an interval tree indexed by position, recomputed incrementally. Not done — the
current document sizes do not require it, and writing the number down is more honest than
optimising blind.

**How much memory the anchors hold.** The answer is in [ADR-0005, the CP15
section](0005-tombstone-gc.md): worse than assumed. A mark does not pin two tombstones but **a whole
run-length block**, the one its anchor sits in. In a document written in one run, GC reclaims
**nothing** once formatting is added. Redesigning the block representation therefore stops being
optional — the question "is it worth it" has the answer "yes".

In the snapshot itself a mark costs **8 B**, and that is good news: it does not copy the text it
covers, that is, it does not do the thing Peritext exists to prevent.

**How formatting changes the traffic profile.** The table in the README ("Traffic profile"). Marks
are every tenth edit in the harness, and they did not turn out to be the expensive part — the
expensive part was **presence**, which the previous measurements were not sending at all.

### Verified in CP20 — the O(m²) is gone, and the limit moved somewhere else

The sweep with an incrementally maintained set of active ranges was implemented. Measured on the
same rig, before and after (median of 11 samples, the CP15 measurement having timed a single call
and been too noisy to compare with):

| Marks | `O(m²)` — before | sweep — after | Speed-up |
|---:|---:|---:|---:|
| 100 | 0.39 ms | 0.27 ms | 1.5× |
| 400 | 0.58 ms | 0.49 ms | 1.2× |
| 1600 | 4.18 ms | 1.49 ms | 2.8× |
| 6400 | **62.1 ms** | **4.72 ms** | **13×** |

Growth per fourfold increase in marks fell from ×7.2 → ×14.9 to ×3.1 → ×3.2. The usable limit moved
from **hundreds** of marks to **thousands**.

**Two findings more interesting than the speed-up.** First: below roughly a thousand marks the
change buys nothing, because the dominant cost is not picking a winner at all — it is **resolving
anchors** through the block index, two lookups per mark. The complexity fix starts paying exactly
where CP15 said the limit was, and not one bit earlier. Second: the CP15 measurement above timed
one call and gave 0.8 ms and 4.7 ms on identical code at 400 marks. The numbers 0.6 / 0.8 / 5.3 ms
quoted here are therefore single shots, and are kept only as a record of what was believed at the
time.
