# ADR-0015 — Suggestion mode as a third use of the mark layer

Status: **Accepted** · CP23

## Context

`commenter` has been a role since CP12: may read, may comment, may not change the text. ADR-0009
justified it as the reason authorization has to descend to the operation type, and CP14 gave it
something to do — conversations. What it never had was a way to contribute **to** the document.
A commenter could write about a sentence and could not propose a better one.

That is also the gap in the project's own argument. The role model is the most carefully built
part of the application outside the CRDT, and until CP21 none of it was reachable from the product
(ADR-0014); after CP21 it was reachable but `commenter` still meant "an observer who can talk".

The second thing making this worth doing: it is a feature **nobody had in mind** when the mark
layer was designed in CP13. Formatting and comments both landed on that layer, but comments were
predicted by ADR-0010 in advance. A third use, unanticipated, is the strongest available evidence
that the layer is the right shape rather than a lucky fit.

## Options considered

**1. A separate branch of the document.** The literal model of "track changes": suggestions live
in a shadow copy which is merged on accept. Rejected — it means a second CRDT state per document,
a merge with three-way semantics, and a whole class of questions ("what happens to a suggestion
when the base text changes underneath it") that the mark layer answers by construction, because
anchors move with the text.

**2. Suggestions as an application-level table with offsets.** Cheap, and wrong for a reason this
project has already paid for twice: an offset stops describing the same place the moment somebody
types before it. Cursors (ADR-0006) and marks (ADR-0010) both moved to anchors for that reason.

**3. Suggestions as marks over the real text.** A proposed insertion is real text in the real
sequence wearing a `suggest-insert` mark; a proposed deletion is a `suggest-delete` mark over
text that is still there. Accepting an insertion removes the mark, rejecting it deletes the
characters, and the two are exactly reversed for a deletion.

**Then a second choice: who creates the operations.** A proposed insertion needs an `Insert`, and
a commenter may not send one — that is the role. Either the commenter sends it and the frame
interceptor verifies it is covered by a suggestion mark, or the client asks over REST and the
**server replica** generates both operations, as history restore already does.

## Decision

**Option 3, with the operations generated on the server.**

- Two mark types, `suggest-insert` and `suggest-delete`, neither of which expands at the end —
  typing after somebody's proposal is ordinary writing, and a mark that swallowed it would put a
  stranger's text inside a proposal they never made. The shared type table
  (`conformance/schema/mark-types.json`) carries both, so the two implementations cannot drift on
  it.
- The proposal's identifier is the mark's **value**, exactly as a thread identifier is for a
  comment anchor. Author, time, resolution and the quoted text live in a table, because none of
  them is concurrently edited (ADR-0011, the same split for the same reason).
- Proposing requires `Capability.COMMENT`; deciding requires `Capability.WRITE_TEXT`. That single
  difference is the feature.
- Positions travel as **character identifiers**, and the server resolves them against its own
  replica.

**The character sequence layer did not change by a single field.** Third time.

## Consequences

**Positive**

- The `commenter` role acquires a purpose beyond talking, three checkpoints after being defined.
- No new authorization mechanism, and — worth stating — **no second instance of the debt the
  README names**: nothing reads an operation's payload to decide whether the operation is
  allowed. The alternative design would have added exactly that, in a place where it would be
  harder to remove than the existing one.
- Idempotence comes free rather than being engineered: two editors accepting at the same instant
  produce one resolution, because the second call finds no mark and generates nothing, and the
  row update is conditioned on `resolved_at IS NULL`.
- A proposal whose anchored text somebody deleted is still listed, marked as unanchored, with the
  fragment it was about — the same behaviour a detached comment thread already has, and for the
  same reason.

**Negative**

- **A proposed insertion is real text in the document from the moment it is proposed.** Everybody
  sees it immediately, styled as a proposal; anything that reads the document as a string — the
  search preview, the timeline character counts, an export — counts it as content. That is the
  price of not having a second CRDT state, and it is a real one.
- A commenter therefore causes text to be inserted, which reads as a contradiction of "may not
  change the text" until you notice that they cannot *keep* it there: rejecting removes it, and
  they cannot reject.
- The proposal is not optimistic: the client waits for the round trip and the broadcast, because
  the operations are generated on the server. Every other edit in this system appears
  instantly. Justified — proposing is a deliberate gesture measured in seconds, not a keystroke —
  but it is an inconsistency in the interface.
- Rejecting an insertion **deletes text**, so it needs write permission even when the author is
  rejecting their own proposal. Predictable once stated, surprising until then.
- Nothing merges two overlapping proposals. Two people may propose deleting overlapping ranges,
  and accepting both applies both deletions — which is correct in the CRDT sense and may not be
  what either of them meant.

**To be verified later:** whether suggestions want their own list in the document catalogue ("3
open proposals") the way comments do. Left out on purpose until somebody uses this and finds the
panel insufficient — the plan's own rule is that a feature has to bring a problem, not a wish.
