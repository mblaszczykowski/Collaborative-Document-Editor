import { describe, expect, it } from 'vitest'
import type { Span } from '../crdt/marks'
import { coveredBy, sameSpans } from './spans'

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
    expect(coveredBy([span(0, 5, 'bold')], 'bold', 0, 6)).toBe(false)
  })

  it('says no when the range starts before the span', () => {
    expect(coveredBy([span(2, 8, 'bold')], 'bold', 0, 8)).toBe(false)
  })

  it('says yes across adjacent spans that both carry the mark', () => {
    expect(coveredBy([span(0, 4, 'bold'), span(4, 10, 'bold', 'italic')], 'bold', 0, 10)).toBe(true)
  })

  it('says no when a gap between two marked spans falls inside the range', () => {
    expect(coveredBy([span(0, 4, 'bold'), span(6, 10, 'bold')], 'bold', 0, 10)).toBe(false)
  })

  it('ignores spans carrying only other marks', () => {
    expect(coveredBy([span(0, 10, 'italic')], 'bold', 0, 10)).toBe(false)
    expect(coveredBy([span(0, 4, 'italic'), span(4, 10, 'bold')], 'bold', 4, 10)).toBe(true)
  })

  it('says yes for an empty range', () => {
    expect(coveredBy([], 'bold', 3, 3)).toBe(true)
  })
})

describe('sameSpans', () => {
  it('treats two separately built empty lists as equal', () => {
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
