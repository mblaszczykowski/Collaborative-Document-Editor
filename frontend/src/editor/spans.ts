import type { Span } from '../crdt/marks'

/**
 * Two pure questions about formatting runs, kept out of the React hook that used to hold them.
 *
 * `coveredBy` lived at the bottom of `useCollaborativeDocument.ts` — exported from a module whose
 * whole subject is a hook, and imported by nobody. Its natural home is `crdt/marks.ts`, next to the
 * `Span` type it is about; it is here instead because that file belongs to the CRDT twin and moving
 * it there would mean the same predicate having to exist in Java too, which it does not.
 *
 * Both functions are one off-by-one from being silently wrong in a way nothing else would catch —
 * see each one — and both are now covered by `spans.test.ts`. Neither was tested before.
 */

/**
 * Whether the WHOLE range [from, to) already carries a mark of the given type.
 *
 * The condition is deliberately strong: for a selection covering both bold and non-bold text the
 * gesture should ADD bold to all of it, rather than remove it from part. That is how the editors
 * people are used to behave, and it is easier to undo than the opposite.
 *
 * This is the predicate that decides whether Ctrl+B applies formatting or removes it, so getting
 * it wrong does not produce an error — it silently inverts every formatting gesture in the
 * product. The spans arrive from `MarkSet.spans`: disjoint, ascending, with unmarked gaps simply
 * absent, which is why a gap can be detected by `span.from > covered` and the loop can stop there.
 */
export function coveredBy(spans: Span[], type: string, from: number, to: number): boolean {
  let covered = from
  for (const span of spans) {
    if (!span.marks.has(type)) continue
    if (span.from > covered) break
    covered = Math.max(covered, span.to)
    if (covered >= to) return true
  }
  return covered >= to
}

/**
 * Whether two lists of spans describe the same formatting.
 *
 * ## Why this is worth a comparison rather than a re-render
 *
 * `RgaDocument.formatting()` builds a FRESH array every time, including a fresh `[]` for a
 * document with no formatting at all. `refreshStats` calls it on every keystroke and put the
 * result straight into React state, so the state changed identity on every character typed. The
 * `Editor` keys an effect on `props.formatting` identity, so every keystroke dispatched a
 * CodeMirror transaction that rebuilt every decoration over the whole document — for an unformatted
 * document, rebuilding nothing, expensively.
 *
 * A transaction per character, regardless of document size, is the justification for the
 * comparison; it is not a guess about which is faster. The comparison itself is bounded by the
 * number of formatting RUNS, which is tiny next to the number of characters, and the overwhelmingly
 * common cases — two empty lists, or two lists of different length — settle in the first two lines.
 */
export function sameSpans(a: Span[], b: Span[]): boolean {
  if (a === b) return true
  if (a.length !== b.length) return false
  for (let index = 0; index < a.length; index++) {
    const left = a[index]
    const right = b[index]
    if (left.from !== right.from || left.to !== right.to) return false
    if (left.marks.size !== right.marks.size) return false
    for (const [type, value] of left.marks) {
      // `has` as well as `get`, even though equal sizes plus a value comparison would nearly do
      // it. A valueless mark — bold, italic, a quotation — is stored as `null`, and `get` answers
      // `undefined` for an absent key, so the two are already distinguishable here; the explicit
      // `has` is what keeps that true if the map ever carries an explicit `undefined`. This is the
      // same `null` versus `undefined` distinction the undo layer had to learn in CP22, where
      // conflating them made a restore silently keep formatting it had been asked to remove.
      if (!right.marks.has(type) || right.marks.get(type) !== value) return false
    }
  }
  return true
}
