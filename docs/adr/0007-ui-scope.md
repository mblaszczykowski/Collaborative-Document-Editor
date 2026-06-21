# ADR-0007 — UI scope: we adopt the mockup's visual language and reject its functional scope

- **Status:** **Superseded by [ADR-0008](0008-scope-extension.md)** as far as functional scope
  is concerned. The decisions about the visual language and about hiding diagnostics remain in
  force, and the document itself was **revised** (see the section at the end).

  This document is not deleted: rejecting the scope was right for v1 and stopped being right only
  once the core was finished — and that is information, not an embarrassment.
- **Date:** 2026-08-14
- **Applies to checkpoints:** CP4, CP5, CP6
- **Source material:** [`docs/design/mockup-inkline.html`](../design/mockup-inkline.html)

---

## Context

A visual mockup ("Inkline") joined the project — a complete application shell in the style of a
desktop editor: a menu bar, a formatting toolbar, a sidebar with a document list, a ruler, a page
with serif text, other users' cursors with name flags, selections, comment markers, a side panel
with Comments/History tabs, a status bar.

The design is good and worth adopting. The problem is that **the mockup shows three things
`CLAUDE.md` explicitly says to avoid** (the "What to avoid" section):

> Excessive scope (rich text formatting, comments, multi-document workspace) at the cost of depth
> in the core problem

The mockup contains all three: **B / I / U / S** buttons with colours and highlighting, a comments
panel with threads, a sidebar with a workspace and multiple documents.

This is a real conflict, not a matter of interpretation. It has to be settled explicitly.

---

## Options considered

### Option A — Implement the mockup in full

**For:** the demo looks richest, the mockup is reproduced 1:1.
**Against:**
- Rich text in a CRDT is a **different algorithm**, not an addition to the existing one. Formatting
  requires markers spanning a range of characters which must survive concurrent insertions into
  the middle of the range — a problem with its own research paper devoted to it (Peritext). In
  practice it doubles the scope of CP2 and CP4.
- Comments are another CRDT (text anchors surviving edits beneath them) plus a data model.
- Net effect: less time for property tests, observability and benchmarks — that is, for what this
  project is actually meant to demonstrate.

### Option B — Implement the shell but leave the formatting buttons decorative

**For:** it looks like the mockup, and it is cheap.
**Against:** **a button that does nothing is a lie in the interface.** In a portfolio project that
is a worse signal than its absence — a reviewer clicks, nothing happens, and from that moment they
do not trust the rest. Rejected.

### Option C — Adopt the visual language, replace the contents with what exists in this project

We keep: the palette, the typography (Source Serif 4 on the page, Inter in the chrome, IBM Plex
Mono for technical data), the three-column layout, the page with a shadow on a grey background,
cursors with name flags, selections in the author's colour, the status bar, the side panel with
tabs.

We replace the contents of the toolbar and side panel with features that **really exist** in this
project and are characteristic of it.

---

## Decision

**Option C.**

### What stays from the mockup unchanged

| Element | Justification |
|---|---|
| The whole palette and typography | The design is good; there is no reason to change it |
| Layout: sidebar / canvas / right panel | Maps well onto documents / editor / history |
| Page with a shadow on a grey background, serif text | Distinguishes "content" from "tooling" — legible |
| Cursors with a name flag, selections in the author's colour | This is the core of the demo (CP4) |
| Presence avatars in the top right | Presence is in scope (`idea.md`) |
| Status bar | An ideal place for connection state and an operation counter |
| Sidebar with a document list | Choosing a document is functionally necessary; this is not a "workspace", it is navigation |

### What goes

| Element | Reason |
|---|---|
| **B / I / U / S** buttons, text colours, highlighting | Rich text out of scope (`CLAUDE.md`) |
| The "Normal text" dropdown (headings) | As above |
| Bulleted/numbered lists, image insertion, links | As above |
| The Comments panel, comment markers in the text | Comments out of scope (`CLAUDE.md`) |
| The ruler with margins | It implies control over the page layout, which does not exist |
| The File / Edit / View / Insert / Format / Tools menu bar | Six menus, five of which would be empty |

### What replaces it — the toolbar

Instead of formatting, the toolbar gets controls that mean something in this project:

1. **Undo / redo** — working and CRDT-aware (it undoes the user's own operations, not other
   people's). It stays from the mockup, where it is also first.
2. **Network simulator** — sliders for latency, jitter and packet loss plus a "partition" button.
   This is a feature straight from `idea.md` ("conflict simulator"), and it is the strongest
   element of the demo: it lets you **show the algorithm working live** instead of asserting it.
3. **Connection state indicator** — online / offline / resynchronising, with the number of
   operations in the offline buffer.

### What replaces it — the right panel

The `Comments | History` tabs → **`History | Replicas`**:

- **History** — a timeline of versions with an audit trail (who, what, when), scrubbing to any
  point, restoring. This is CP5, fully in scope. The mockup already has a ready-made look for a
  history entry (`.history-entry`), so the design can be used directly.
- **Replicas** — a view of the CRDT's internal state for this document: each replica's version
  vector, the count of live characters versus tombstones, `stableVersion`, and which replica is
  blocking GC ([ADR-0005](0005-tombstone-gc.md)).

That second panel is a deliberate trade of a product feature for a **diagnostic** one. In an
ordinary application it would be unnecessary. Here it is the point of the whole exercise: it shows
that underneath there really is the data structure the ADRs talk about, and it lets you see
tombstones and causal stability live instead of reading about them in the README.

### Name

The mockup introduces the name **Inkline**. We adopt it as the product name in the UI. The
repository and modules stay `collabedit` / `dev.collabedit` — renaming identifiers in the code adds
nothing but cost.

---

## Consequences

### Positive
- The whole budget stays in the core problem; the design costs no scope.
- **Every visible element of the interface does something.** No decorative dummies.
- The network simulator in the toolbar turns the hardest aspect of the project to show
  (correctness under concurrency) into something visible during a 20-second GIF.
- The "Replicas" panel makes the CRDT's internal structure observable — which is itself an argument
  that it was written from scratch rather than imported.

### Negative
- **The mockup is not reproduced faithfully.** Anyone comparing the repo with the mockup will see a
  different toolbar and a different side panel. That is why the mockup stays in the repository
  alongside this ADR — the difference is to be documented, not hidden.
- **The absence of rich text limits credibility as a "Google Docs light"** — a document without
  bold and headings is visibly simpler than what a page with serif text and a ruler suggests. It
  goes into "what I would do differently in production", together with the fact that the right
  answer is Peritext rather than bolting markers onto RGA.
- **The sidebar with a document list does after all require a minimal multi-document model** — the
  `documents` table exists anyway ([ADR-0003](0003-change-history.md)), so the cost is close to
  zero, but formally it is a step towards the "workspace" we were meant to avoid. The boundary:
  listing and choosing a document yes, folders/permissions/sharing no.
- The "Replicas" panel is an element **no real editor has** — to somebody looking for a product it
  will seem odd. It is here deliberately, because the audience for this repository is an engineer,
  not an end user.

### To be verified in CP4
- Whether the mockup's cursors with name flags stay legible with 5+ concurrent users in one
  paragraph, or whether the flags start overlapping.
- Whether a fixed 620 px page copes with a window split in half (the demo scenario: two browsers
  side by side) — if not, a narrower variant is needed.

---

## Revision: diagnostics disappear from the user interface

**The decision to put the network simulator in the toolbar and the CRDT replica view in the side
panel has been reversed.**

### What was wrong

The original reasoning read: "every visible element should do something, and these two show
exactly what the project proves". The reasoning is right about the **value** of those tools and
wrong about the **audience** of the interface.

A user of an editor does not know what a tombstone or a version vector is, and is under no
obligation to know. A "packet loss" slider above the document does not make the product look
considered — it makes it look like a laboratory rig. I had conflated two things: what is worth
**showing a reviewer of the repository** with what a **user opening the editor** should see. The
first belongs in the README, the ADRs and the tests. The second should look like a finished
product.

### What replaces it

| Element | Was | Is |
|---|---|---|
| Toolbar | latency, jitter and loss sliders, "Cut the network" | undo / redo — the only two actions that actually exist |
| Right panel | "Replicas" and "History" tabs | change history, no tabs |
| Top bar | connection state in protocol language | document title, "Saved" / "Working offline", avatars, "Share" |
| Status bar | characters, blocks, tombstones, buffer | word and character count, how many people are online |

Diagnostics **have not disappeared** — they live behind `?debug=1`, out of reach of an accidental
user, and they still drive the chaos tests.

### Side effects that turned out to be fixes

The revision forced two changes that had to be made regardless:

1. **Undo had to start working.** Previously CodeMirror's `history()` was disabled (rightly — it
   would undo other people's changes), but nothing replaced it. Ctrl+Z did nothing, which in a text
   editor is a gap visible in the first minute of use. `UndoManager` was written, undoing **only
   the user's own operations**, merging continuous typing into a single step — without which Ctrl+Z
   would remove one letter at a time.

2. **The demo stopped relying on the simulator.** The offline scenario now cuts the network **for
   real** (`context.setOffline`) rather than pausing sending inside the application. That made the
   test stronger: it covers the WebSocket being torn down, reconnecting and resynchronising — the
   full path of a user whose wi-fi died. It also turned out that the interface showed "Saved" for a
   dozen or so seconds after connectivity was lost — until the heartbeat expired. Listening for the
   browser's `online`/`offline` events fixes that immediately.
