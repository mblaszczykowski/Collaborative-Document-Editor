import { compareIds, idToString, type Anchor, type Id, type Op } from './types'

export interface Span {
  readonly from: number
  readonly to: number
  readonly marks: ReadonlyMap<string, string | null>
}

export type AnchorPositions = (anchor: Anchor) => number | undefined

interface Resolved {
  readonly op: Op
  readonly type: string
  readonly value: string | null
  readonly adding: boolean
  readonly from: number
  readonly to: number
}

export class MarkSet {
  private readonly operations = new Map<string, Op>()

  apply(op: Op): boolean {
    if (op.kind !== 'addMark' && op.kind !== 'removeMark') {
      throw new Error(`not a mark operation: ${op.kind}`)
    }
    const key = idToString(op.id)
    if (this.operations.has(key)) return false
    this.operations.set(key, op)
    return true
  }

  has(opId: Id): boolean {
    return this.operations.has(idToString(opId))
  }

  all(): Op[] { return [...this.operations.values()] }

  get size(): number {
    return this.operations.size
  }

  clear(): void {
    this.operations.clear()
  }

  spans(positions: AnchorPositions): Span[] {
    const resolved = this.resolveAll(positions)
    if (resolved.length === 0) return []

    const boundaries = new Set<number>()
    for (const entry of resolved) {
      boundaries.add(entry.from)
      boundaries.add(entry.to)
    }
    const points = [...boundaries].sort((a, b) => a - b)

    const startingAt = new Map<number, Resolved[]>()
    const endingAt = new Map<number, Resolved[]>()
    for (const entry of resolved) {
      const starts = startingAt.get(entry.from)
      if (starts === undefined) startingAt.set(entry.from, [entry])
      else starts.push(entry)
      const ends = endingAt.get(entry.to)
      if (ends === undefined) endingAt.set(entry.to, [entry])
      else ends.push(entry)
    }

    const activeByType = new Map<string, Resolved[]>()

    const spans: Span[] = []
    for (let i = 0; i + 1 < points.length; i++) {
      const from = points[i]
      const to = points[i + 1]

      for (const ending of endingAt.get(from) ?? []) {
        const active = activeByType.get(ending.type)
        if (active === undefined) continue
        const at = active.indexOf(ending)
        if (at >= 0) active.splice(at, 1)
        if (active.length === 0) activeByType.delete(ending.type)
      }
      for (const starting of startingAt.get(from) ?? []) {
        const active = activeByType.get(starting.type)
        if (active === undefined) {
          activeByType.set(starting.type, [starting])
        } else {
          insertByPriority(active, starting)
        }
      }

      const marks = new Map<string, string | null>()
      for (const [type, active] of activeByType) {
        const winner = active[0]
        if (winner.adding) marks.set(type, winner.value)
      }
      if (marks.size > 0) spans.push({ from, to, marks })
    }
    return mergeAdjacent(spans)
  }

  private resolveAll(positions: AnchorPositions): Resolved[] {
    const resolved: Resolved[] = []
    for (const op of this.operations.values()) {
      if (op.kind !== 'addMark' && op.kind !== 'removeMark') continue

      const from = positions(op.start)
      const to = positions(op.end)
      if (from === undefined || to === undefined || from >= to) {
        continue
      }
      resolved.push({
        op,
        type: op.type,
        value: op.kind === 'addMark' ? op.value : null,
        adding: op.kind === 'addMark',
        from,
        to,
      })
    }
    return resolved
  }
}

function insertByPriority(active: Resolved[], entry: Resolved): void {
  let low = 0
  let high = active.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (compareIds(active[mid].op.id, entry.op.id) > 0) low = mid + 1
    else high = mid
  }
  active.splice(low, 0, entry)
}

function mergeAdjacent(spans: Span[]): Span[] {
  if (spans.length < 2) return spans
  const merged: Span[] = []
  let current = spans[0]
  for (let i = 1; i < spans.length; i++) {
    const next = spans[i]
    if (next.from === current.to && sameMarks(current.marks, next.marks)) {
      current = { from: current.from, to: next.to, marks: current.marks }
    } else {
      merged.push(current)
      current = next
    }
  }
  merged.push(current)
  return merged
}

function sameMarks(
  a: ReadonlyMap<string, string | null>,
  b: ReadonlyMap<string, string | null>,
): boolean {
  if (a.size !== b.size) return false
  for (const [key, value] of a) {
    if (!b.has(key) || b.get(key) !== value) return false
  }
  return true
}
