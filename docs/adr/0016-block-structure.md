# ADR-0016 — Block structure as marks, and no move operation

Status: **Accepted** · CP30

## Context

The document is a flat sequence of characters. There is no paragraph object in it: a paragraph is
the text between two newlines, and "make this a heading" was not expressible at all — the mark
layer carried bold, links, comments and suggestions, and nothing that describes a *line*.

Adding structure is the last item in plan v4 and the only one that could change the core. It also
carries the hardest problem in this project's neighbourhood, and it is worth naming before the
decision rather than after: **concurrent movement**. If paragraphs become objects that can be
reordered, two people moving the same paragraph at the same moment is a case a naive design gets
wrong — by duplicating it, or by losing it — and getting it right is what Kleppmann's *Moving
Elements in List CRDTs* (2020) is about.

## Options considered

**1. A tree of blocks, with a move operation.** Paragraphs become nodes with identity; moving one
is an operation rather than a delete plus an insert. This is what a document editor eventually
needs, and it is the version of this checkpoint that would have changed the core.

Rejected for v4, on cost and on risk: it is a multi-day rewrite of the layer every other feature
in this repository already rests on, and it arrives at the end of a plan whose earlier items are
finished and tested. Half a tree is worse than no tree.

**2. Block structure as marks over a line.** A heading is a mark whose range is the line; so are a
quotation and a list item. Nothing changes in the character layer — the fourth time that sentence
has been true, after formatting (CP13), comments (CP14) and suggestions (CP23).

**3. Nothing.** Defensible, and rejected for a narrow reason: the *absence* of a move operation is
much more interesting when the structure exists than when it does not. A limit you can demonstrate
is worth more than a limit you describe.

## Decision

**Option 2, with the missing move written down and pinned by a test.**

- Three types: `heading` (value = the level), `quote`, `list` (value = the kind).
- All three **expand at the end**, unlike links and suggestions, and for a different reason from
  bold: a block mark describes a *line*, so typing at the end of a line must stay inside it. The
  end anchor sits before the newline, so the growth stops there — a heading cannot swallow the
  paragraph below it. Both implementations get this from the shared type table
  (`conformance/schema/mark-types.json`), which is where a divergence would otherwise be invisible
  until a user saw it.
- **There is no move operation**, and `BlockStructureTest.concurrentMovesOfTheSameParagraphDuplicateIt`
  shows exactly what that costs: two people moving the same paragraph at the same moment end up
  with two copies of it. The replicas still agree — the algorithm's guarantee is intact — and the
  *result* is wrong for the user. That distinction is the whole point of the test.

## Consequences

**Positive**

- Structure exists, in a day rather than a week, and the character layer did not change by a
  field.
- The most-cited hard problem in this area is now demonstrated in this repository rather than
  mentioned: a reader can run one test and see the duplication. Anybody implementing a real move
  operation starts with the failing case already written.
- The expansion rule for block marks is a genuinely different case from the inline ones, and
  having both in one table makes the distinction visible instead of folkloric.

**Negative**

- **An empty line cannot be marked.** A mark needs characters to anchor to, and an empty line has
  none. Pressing "heading" on an empty line does nothing — honest, and still a surprise.
- **No nesting.** A list inside a list is a tree, and this is not one.
- **A paragraph has no identity**, so nothing in the model can express "the same paragraph,
  moved". Everything downstream inherits that: history shows a move as a deletion and an
  insertion, and so does the identity diff from CP22 — correctly, because that is what happened.
- Ordered lists are numbered by the renderer rather than by the document, so two people cannot
  disagree about the numbering — which is convenient here and would not survive nesting.
- The bullet is drawn in CSS rather than inserted as a character. A character would be one that
  somebody could delete without removing the mark, leaving the two disagreeing.

**When to revisit:** when moving paragraphs becomes a gesture people actually use. Until then the
cost is a duplicated paragraph in a rare race, and the fix is a rewrite of the layer everything
else stands on.
