# ADR-0011 — Comments: the anchor in the CRDT, the content outside it

- **Status:** Accepted
- **Date:** 2026-08-15
- **Applies to checkpoints:** CP14

---

## Context

A comment in a document editor consists of two things that are easy to conflate:

1. **The anchoring** — which fragment of text the conversation is about.
2. **The conversation** — the messages, their authors, their order.

The question is **which of them needs a CRDT**. The answer "both, since we already have a CRDT" is
tempting and expensive.

---

## Options considered

### The anchoring

**Option A — offsets in the database** (`from`, `to` in the thread table).

**Against:** offsets drift with every edit by somebody else. It is the same mistake
[ADR-0006](0006-editor-layer.md) rejected for cursors and [ADR-0010](0010-rich-text.md) rejected
for formatting — **the same mistake a third time, in a third place**.

**Option B — a CRDT mark of type `comment`** with the thread identifier as its value.

**For:** this is exactly the machinery CP13 introduced. Anchors move with the text, survive edits to
their surroundings, and merge like any other operation — without a single new line in the CRDT
layer.

### The conversation

**Option A — messages as a CRDT.**

**For:** uniformity. Everything is an operation, everything travels the same channel.

**Against:** a CRDT solves **one specific problem** — concurrent editing of the same sequence. A
message has a single author, is created whole, and is not edited character by character by two
people at once. Using a CRDT therefore means an operation log, merging, tombstones, GC and a double
implementation, for text that needs none of it.

**Option B — ordinary relational tables.**

---

## Decision

**The anchor in the CRDT (B), the conversation outside it (B).**

A thread and its anchor are therefore created by **two different routes**: the content over REST,
the anchor over WebSocket as a CRDT operation. This looks like an inconsistency and is not — they
are two things with different requirements:

| | anchor | conversation |
|---|---|---|
| must merge without an arbiter | yes | no |
| must survive somebody else's edit | yes | not applicable |
| requires low latency | yes (the highlight) | no |
| has a single author | no | yes |

You can see it in the behaviour: the highlight of an anchored fragment appears for the other person
immediately, over the operation channel; the conversation content appears when the panel refreshes.

### A detached thread rather than a deleted one

When anchored text is deleted, the anchors remain as tombstones (protected from GC —
[ADR-0005](0005-tombstone-gc.md)) and the range stops covering anything. The thread is then
**detached**: shown with the fragment quoted as of when it was created, and clearly marked.

The copy of the quoted text in the database is **deliberately redundant** with respect to the
anchors. Without it a detached conversation would have no way of showing what it was about — and a
conversation about an unknown fragment is worth little.

### Resolving is not deleting

The same principle as with soft-deleting documents in CP11: the context of a decision is usually the
only thing anyone comes back to comments for, months later.

---

## Consequences

### Positive
- CP14 added **not a single line** to the CRDT layer. The entire anchor is a mark from CP13, which
  is the best proof that the layer was extracted correctly.
- The `commenter` role from [ADR-0009](0009-permission-model.md) gained substance and confirmed its
  design: authorization had to descend to the **operation type**, because comments travel the same
  channel as editing. Anticipated in CP12, verified in CP14.
- Conversations can be read with a plain `SELECT`, without rebuilding state from the operation log.

### Negative

- **Two routes instead of one.** A thread is created over REST and its anchor over WebSocket, and
  between them there is a window in which the thread exists without an anchor. If the client dies
  exactly then, an unanchored thread remains — shown as detached from the moment of creation. It is
  not data loss, but it is a state indistinguishable from "the text was deleted".
- **Comment content does not work offline.** The offline buffer covers CRDT operations; a comment
  requires a connection. This is a consequence of excluding the conversation from the CRDT, and a
  deliberate price.
- **No notifications and no mentions.** A comment nobody notices is worth as much as no comment. Out
  of scope, listed in the README.
- **The panel refreshes by polling** rather than by an event. A new message in a thread appears for
  others only when the panel is reopened or the document is changed. The right solution is a comment
  event channel — not done.
- **Deleting a document cascades to its threads** (`ON DELETE CASCADE`). With the soft delete from
  CP11 this does not matter, because a document is never hard-deleted — but it is a dependency that
  will stop being safe if hard deletion is ever introduced.
