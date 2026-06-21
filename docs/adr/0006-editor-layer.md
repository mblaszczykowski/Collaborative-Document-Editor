# ADR-0006 — The editor layer: CodeMirror 6 as the view, our own document model

- **Status:** Accepted
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP4

---

## Context

The frontend has to: render text, accept keyboard editing, draw **other users' cursors and
selections** inside the text, and turn local edits into CRDT operations (and back).

`CLAUDE.md` forbids using off-the-shelf **CRDT libraries** as a black box. `idea.md` at the same
time explicitly permits a library as a **UI layer** ("e.g. based on ContentEditable, or a library
such as Slate.js/ProseMirror as the UI layer, with our own CRDT logic underneath").

The boundary is therefore clear and worth stating plainly: **the document model and the merge
algorithm are ours; rendering text and handling the browser's input events do not have to be.**
The difficulty of the project should lie in distributed consistency, not in IME handling.

---

## Options considered

### Option A — `<textarea>`

**For:** trivial, zero dependencies, zero surprises.
**Against:** **nothing can be drawn inside the text.** Remote cursors, selections and author
colours are impossible. That removes the most striking and most frequently mentioned feature from
`idea.md` — and precisely the one that sells the demo. Rejected.

### Option B — A custom editor on `contentEditable`

**For:** full control, no dependencies, "written from scratch" sounds strong.
**Against:** `contentEditable` is one of the most inconsistent parts of the DOM across browsers.
The real list of things to solve yourself:
- **IME / composition events** — typing in Chinese, Japanese, Korean, but also autocorrect and
  suggestions on mobile keyboards. `compositionstart`/`compositionupdate`/`compositionend` versus
  `input` events is the source of bugs most naive editors fall over.
- **The browser's native undo/redo** — it works on the DOM, not on our model; it has to be
  intercepted and replaced, including `Ctrl+Z` from the context menu.
- Pasting (HTML → text), dragging text with the mouse, iOS autocorrect, differences in text node
  normalisation, cursor behaviour at node boundaries.

Cost: realistically a week of work, of which **none demonstrates an understanding of CRDTs** — it
is a fight with the DOM, not with the algorithm. Rejected as a bad allocation of budget in a
project with finite time whose goal is depth in the core problem.

### Option C — ProseMirror / Slate

**For:** mature, they solve `contentEditable` well.
**Against:** both are built around a **tree document model** (rich text, schema, nested nodes). Our
document is a flat sequence of characters (a deliberate scope limitation from `idea.md`). We would
get all the complexity of a tree model in order to use one node type, and we would have to map our
flat CRDT onto their positions in a tree — an extra translation layer with no benefit.

### Option D — CodeMirror 6 as a pure view layer

CM6 is built around a **flat text document** — exactly our model. The key properties:
- **Transactions with `ChangeSet`** — every user edit arrives as an explicit, described set of
  changes (`fromA/toA/fromB/toB` + inserted text), rather than as a DOM diff to be guessed at.
  Mapping onto CRDT operations is direct.
- **IME, undo, pasting and mobile solved** — battle-tested in production for years.
- **`Decoration` and widgets** — a native mechanism for drawing other users' cursors and
  selections without touching the DOM by hand.
- **`StateField` / `StateEffect`** — a place to keep presence state outside the document content.
- Tree-shaking, modular build — we take only the modules we need.

**Against:**
- An external dependency on the UI critical path.
- CM6 has **its own** document model (`Text`) and its own history — two models side by side
  require discipline to avoid disagreeing about which is the source of truth.
- The learning curve of CM6's architecture (state/view, facets, transactions) is not flat.

---

## Decision

**Option D — CodeMirror 6 exclusively as the view layer.**

### The responsibility boundary — written down, because otherwise it blurs

| Responsibility | Owner |
|---|---|
| Document structure, character identifiers, tombstones | **our code** (`crdt-ts`) |
| Merging operations, convergence, version vectors | **our code** |
| Offline buffer, resynchronisation | **our code** |
| Remote cursor positions (as CRDT identifiers) | **our code** |
| Rendering text, keyboard events, IME, pasting | CodeMirror |
| Drawing decorations where our code says | CodeMirror |

**The source of truth is the CRDT, not CodeMirror's `EditorState`.** CM6 is a projection: every
local change goes `CM6 → CRDT operation → merge → new state → back to CM6`, never "change CM6 and
the CRDT separately". CM6's history (`history()`) is **disabled** — undo has to work on CRDT
operations (undoing the user's own operations), not on the text, because otherwise an undo would
remove other people's changes.

### Remote cursors are kept as CRDT identifiers, not offsets

This is not an implementation detail but the resolution of a classic bug. If Bartek's cursor
position were an offset (`column 42`), then Ania inserting a character before that position would
shift the text but not the cursor — and Bartek's cursor would point at somebody else's character.
Keeping the position as a **CRDT node identifier** makes the cursor move with the text
automatically, because it is attached to a character rather than to a place.

The same problem applies to selections (a pair of identifiers) and to the user's own local cursor
during an incoming remote edit.

---

## Consequences

### Positive
- The entire time budget goes to the core problem (CRDT, consistency, observability) rather than to
  fighting `contentEditable`.
- CM6's `ChangeSet` maps onto CRDT operations almost one to one — less room for error than
  guessing changes from DOM mutations.
- Remote cursors and selections get first-class support (`Decoration`), so the best-looking part of
  the demo is also the cheapest to build.
- Editing on a mobile device and with an IME works, even though it is not the subject of this
  project.

### Negative
- **Two document models side by side** (the CRDT and `EditorState`). Any divergence between them is
  a hard-to-trace bug of the "the text on screen does not match the state" kind. Mitigation: an
  invariant checked in development mode after every transaction
  (`crdtDoc.text() === cmState.doc.toString()`), with a loud error on mismatch. This is meant to be
  an assertion, not a hope.
- **An external dependency** — if CM6 changes its transaction API, the bridging layer needs work.
  Confined to one module (`frontend/src/editor/`) so the change does not radiate.
- **Disabling CM6's `history()`** means we have to build undo/redo ourselves on CRDT operations.
  That is non-trivial (undo in a CRDT is a new inverse operation, not the removal of an old one)
  and is a **real scope risk in CP4** — if time runs short, undo is limited to undoing the user's
  own operations from the current session, which goes into "what I would do differently in
  production".
- The CM6 learning curve — a one-off cost, but not zero.

### To be verified in CP4
- Whether `ChangeSet` can actually be mapped without edge cases for compound operations (multiple
  selections, replacing selected text in one transaction).
- Whether the invariant "CM6 text == CRDT text" holds under simultaneous local editing and
  incoming remote edits (this is a test, not an assumption).
- Whether cursor decorations survive 20+ concurrent users without a noticeable loss of smoothness —
  if not, the mitigation is capping the number of cursors drawn.
