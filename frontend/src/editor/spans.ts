import type { Span } from '../crdt/marks'

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

export function sameSpans(a: Span[], b: Span[]): boolean {
  if (a === b) return true
  if (a.length !== b.length) return false
  for (let index = 0; index < a.length; index++) {
    const left = a[index]
    const right = b[index]
    if (left.from !== right.from || left.to !== right.to) return false
    if (left.marks.size !== right.marks.size) return false
    for (const [type, value] of left.marks) {
      if (!right.marks.has(type) || right.marks.get(type) !== value) return false
    }
  }
  return true
}
