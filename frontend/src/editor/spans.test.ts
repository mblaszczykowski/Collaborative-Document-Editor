import { describe, expect, it } from 'vitest'
import type { Span } from '../crdt/marks'
import { coveredBy, sameSpans } from './spans'

/**
 * `coveredBy` decides whether Ctrl+B **applies** formatting or **removes** it. It is the one
 * predicate in the editor whose failure mode is not an error but an inversion: get the boundary
 * wrong and every formatting gesture in the product does the opposite of what it says, silently,
 * on every document. It went from CP13 to here untested.
 *
 * The spans it reads come from `MarkSet.spans`, which guarantees they are disjoint, ascending, and
 * carry no entry for unmarked text. Every case below is built to that contract — a test that fed
 * it overlapping spans would be testing a situation the model cannot produce.
 */

function span(from: number, to: number, ...types: string[]): Span {
  return { from, to, marks: new Map(types.map((type) => [type, null])) }
}

describe('coveredBy', () => {
  it('says no when nothing is marked', () => {
    expect(coveredBy([], 'bold', 0, 5)).toBe(false)
  })

  it('says yes for a range exactly covered by one span', () => {
    expect(coveredBy([span(0, 5, 'bold')], 'bold', 0, 5)).toBe(true)
  })

  it('says yes for a range strictly inside a span', () => {
    expect(coveredBy([span(0, 10, 'bold')], 'bold', 3, 7)).toBe(true)
  })

  it('says no when the span stops one character short', () => {
    // The off-by-one that inverts the gesture. Selecting one character past the end of a bold run
    // and pressing Ctrl+B must EXTEND the bold, not clear it — the strong reading of "covered" is
    // the whole point of the function, and this is the case that distinguishes it from a weak one.
    expect(coveredBy([span(0, 5, 'bold')], 'bold', 0, 6)).toBe(false)
  })

  it('says no when the range starts before the span', () => {
    expect(coveredBy([span(2, 8, 'bold')], 'bold', 0, 8)).toBe(false)
  })

  it('says yes across adjacent spans that both carry the mark', () => {
    // Adjacent runs happen whenever two marks have different extents — bold over [0,10) and italic
    // over [4,10) makes `spans` report [0,4) bold and [4,10) bold+italic. Asking about bold over
    // the whole thing must answer yes, or bolding an already-bold sentence with an italic word in
    // it would un-bold nothing and add bold a second time.
    expect(coveredBy([span(0, 4, 'bold'), span(4, 10, 'bold', 'italic')], 'bold', 0, 10)).toBe(true)
  })

  it('says no when a gap between two marked spans falls inside the range', () => {
    // An unmarked gap is simply absent from `spans`, so the only evidence of it is that the next
    // span starts past where the coverage had reached.
    expect(coveredBy([span(0, 4, 'bold'), span(6, 10, 'bold')], 'bold', 0, 10)).toBe(false)
  })

  it('ignores spans carrying only other marks', () => {
    expect(coveredBy([span(0, 10, 'italic')], 'bold', 0, 10)).toBe(false)
    expect(coveredBy([span(0, 4, 'italic'), span(4, 10, 'bold')], 'bold', 4, 10)).toBe(true)
  })

  it('says yes for an empty range', () => {
    // `from >= to` never reaches this function from the toolbar (`toggleMark` refuses it), but the
    // answer should still be the coherent one: an empty range is trivially covered, and the
    // alternative is a function whose value depends on which caller happens to reach it.
    expect(coveredBy([], 'bold', 3, 3)).toBe(true)
  })
})

describe('sameSpans', () => {
  it('treats two separately built empty lists as equal', () => {
    // The case that matters most, because it is the ordinary one: an unformatted document produces
    // a fresh `[]` from `formatting()` on every keystroke, and comparing by identity dispatched a
    // CodeMirror transaction rebuilding every decoration for each of them.
    expect(sameSpans([], [])).toBe(true)
  })

  it('sees a changed boundary', () => {
    expect(sameSpans([span(0, 5, 'bold')], [span(0, 6, 'bold')])).toBe(false)
  })

  it('sees a changed mark set at the same boundaries', () => {
    expect(sameSpans([span(0, 5, 'bold')], [span(0, 5, 'italic')])).toBe(false)
    expect(sameSpans([span(0, 5, 'bold')], [span(0, 5, 'bold', 'italic')])).toBe(false)
  })

  it('sees a changed mark VALUE at the same boundaries', () => {
    // A link whose address changed and nothing else. Comparing only positions and type names would
    // report "no change" and leave the old URL drawn — the same failure CP22 found in the history
    // layer, in the other direction.
    const before: Span[] = [{ from: 0, to: 5, marks: new Map([['link', 'https://a']]) }]
    const after: Span[] = [{ from: 0, to: 5, marks: new Map([['link', 'https://b']]) }]
    expect(sameSpans(before, after)).toBe(false)
  })

  it('sees a different number of runs', () => {
    expect(sameSpans([span(0, 5, 'bold')], [span(0, 5, 'bold'), span(7, 9, 'bold')])).toBe(false)
  })

  it('says equal for structurally identical lists built independently', () => {
    expect(
      sameSpans(
        [span(0, 4, 'bold'), span(4, 10, 'bold', 'italic')],
        [span(0, 4, 'bold'), span(4, 10, 'italic', 'bold')],
      ),
    ).toBe(true)
  })
})
