# ADR-0008 — Widening the scope from artifact to product

- **Status:** Accepted
- **Date:** 2026-08-15
- **Applies to checkpoints:** CP10–CP15
- **Supersedes:** [ADR-0007](0007-zakres-ui.md) as far as functional scope is concerned

---

## An honest note about ordering

[Plan v2](../PLAN-v2.md) assumed this document would be written **before** the first line of CP10
code. It was written after CP10, CP11 and CP12 were closed — that is, the rule from `CLAUDE.md`
("an ADR before domain code") was broken in this one case.

I record that instead of backdating it, because the entire value of this directory is that it
describes how things were. An ADR written after the fact is worth less than one written before —
and the reader is entitled to know, so as to weigh the "options considered" below accordingly.

---

## Context

Version 1 achieved its goal. The CRDT core is proven by property tests whose teeth were checked
with mutation; convergence is measured; the limits are named, including RGA's interleaving anomaly
and the limited effectiveness of GC. The last open question — no repeatable convergence above ten
clients — was settled in CP10 and turned out to be a flaw in the measurement rig, not in the
system.

[ADR-0007](0007-zakres-ui.md) rejected the mockup's product scope: rich text, comments, multiple
documents, permissions. The decision was right, and would still be right if the core were unready.
**It stopped being right at the moment the core was ready** — deepening the same place further has
diminishing returns, and the four rejected features are the only direction in which that core can
be *used* rather than merely shown.

The question is: **should we widen the scope, and if so, to include what?**

---

## Options considered

### Option A — Stay with v1 and deepen the core

Fugue instead of RGA (removing the interleaving anomaly), CRDT-aware undo, redesigning the block
representation for more effective GC.

**For:**
- The project keeps a one-sentence description: "our own CRDT implementation, investigated to the
  bottom". That is its greatest strength and the easiest thing to lose through dilution.
- Each of these is hard and measurable — that is, exactly what this project shows best.
- Zero risk of producing "another Google Docs clone, only worse".

**Against:**
- Diminishing returns. A second sequence-algorithm implementation proves the same thing as the
  first, only more precisely. A reader convinced by the first does not need the second.
- A core with no application remains an exercise. The question "is this good for anything" stays
  unanswered.

### Option B — Widen selectively: multiple documents and permissions only

Without rich text and comments.

**For:** cheapest, and it rounds the project off as a product — documents, access, sharing.
**Against:** this is work *around* the core rather than *in* it. It adds nothing to the project's
thesis; CRUD and authorization are a competence visible on every CV.

### Option C — Widen fully: rich text, comments, multiple documents, permissions

**For:**
- **Rich text is an algorithmic problem, not a product one.** Formatting marks must survive
  concurrent insertions into the middle of a range, which per-character attributes do not provide.
  It is the second hard thing in this project, of the same class as the first, and it deepens the
  thesis directly rather than diluting it.
- Comments reuse the anchor layer from rich text — the marginal cost is low and the feature is
  visible as a product.
- Permissions force authorization **per operation type**, because the "commenter" role cannot be
  expressed at the channel level. That is a non-obvious design problem, not CRUD.

**Against:**
- The largest scope and the greatest risk of dilution.
- The double cost of every algorithm change (Java + TypeScript) will now cover marks as well.
- Tombstone GC, already weak today, will get weaker still.

---

## Decision

**Option C.** With one hard condition that is part of this decision rather than a wish:

> **The README must still open with the core and the demo, not with a feature list.** Rich text is
> to be presented as a *consequence* of a good core — "the same algorithm carries formatting" —
> not as a separate item on a list of capabilities.

The checkpoint order follows from three hard dependencies described in the [plan](../PLAN-v2.md):
foundations before everything (because rich text multiplies traffic), multiple documents before
permissions (because without multiple documents permissions have nothing to protect), permissions
before rich text (because authorization must cover operation types), rich text before comments
(because a comment is an anchored range and reuses that layer).

---

## Consequences

### Positive
- The project answers the question "is this core good for anything" instead of leaving it open.
- Rich text adds a second hard algorithmic problem instead of work around the edges.
- Permissions forced per-operation-type authorization — a design problem that plain CRUD would not
  have raised.

### Negative

- **The risk of dilution is real and does not disappear by writing down a condition.** A reviewer
  with three minutes may come away with "another editor" instead of "somebody understands CRDTs".
  **Checked in CP15:** the README still opens with the core, but it has **grown by ~40%** and the
  first screen is now two GIFs instead of one. The risk did not materialise in the *content* — the
  opening talks about a CRDT written from scratch, and formatting and comments are presented in it
  as *applications* of the core rather than as a feature list — but it did materialise in
  **length**. A reviewer with three minutes reads a smaller fraction of the document today than
  before CP10. The safeguard is the "What is interesting here" section right at the top, with links
  to four specific things; if that stops being enough, the right move is to split the measurement
  part into a separate file, not to shorten the justifications.
- **The double implementation cost grows in proportion to scope.** Formatting marks have to be
  built in Java and in TypeScript, and the conformance corpus has to grow by their vectors.
- **Tombstone GC will become even less effective.** [ADR-0005](0005-tombstone-gc.md) forbids
  removing a tombstone that is an origin; after CP13 "that is a mark's anchor" is added, after CP14
  "a comment's anchor". This may force the block representation redesign that ADR-0005 deferred.
  **A decision to be taken at the start of CP13, not during it.**
- **All the performance numbers become stale** after CP13, because rich text changes the traffic
  profile. That is why CP15 (re-measurement) is mandatory in the plan rather than optional.
- **`CLAUDE.md` stops matching the project** in its "What to avoid" section and needs correcting
  alongside this ADR. An instruction advising against what the repository is currently doing is
  worse than no instruction.

### Verified after CP10–CP12

- **The checkpoint order turned out to be right, and non-trivially so.** CP11 (multiple documents)
  opened an IDOR vulnerability that CP12 closed — had the order been reversed, authorization would
  have been built for the "one document" model and would have had to be rewritten.
- **Permissions did indeed force per-operation-type authorization**, exactly as the plan assumed.
  The "commenter" role cannot be expressed at the level of a STOMP destination, because comments
  travel the same channel as editing.
- **No dilution is visible yet**, but CP13 has not started — and it is CP13 that brings the biggest
  change to how the project is described. The judgement belongs to CP15.
