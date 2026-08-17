import { describe, expect, it } from 'vitest'
import { decodeCursorAnchor, encodeCursorAnchor, type CursorAnchor } from './cursorAnchor'

describe('the presence cursor encoding', () => {
  it('survives a round trip for both sides, including lamport 0', () => {
    const places: CursorAnchor[] = [
      { charId: { replicaId: 3, lamport: 0 }, side: 'before' },
      { charId: { replicaId: 3, lamport: 0 }, side: 'after' },
      { charId: { replicaId: 1, lamport: 7 }, side: 'before' },
      { charId: { replicaId: 1, lamport: 7 }, side: 'after' },
    ]

    for (const place of places) {
      const wire = encodeCursorAnchor(place)
      expect(wire).not.toBeNull()
      expect(decodeCursorAnchor(wire!.replicaId, wire!.lamport)).toEqual(place)
    }
  })

  it('gives every distinct place a distinct pair of numbers', () => {
    const seen = new Set<string>()
    for (let lamport = 0; lamport < 64; lamport++) {
      for (const side of ['before', 'after'] as const) {
        const wire = encodeCursorAnchor({ charId: { replicaId: 2, lamport }, side })!
        seen.add(`${wire.replicaId}@${wire.lamport}`)
      }
    }
    expect(seen.size).toBe(128)
  })

  it("reads a frame from a client that predates the side as 'after'", () => {
    expect(decodeCursorAnchor(9, 41)).toEqual({
      charId: { replicaId: 9, lamport: 41 },
      side: 'after',
    })
  })

  it('carries "nothing to anchor to" through unchanged', () => {
    expect(encodeCursorAnchor(null)).toBeNull()
    expect(decodeCursorAnchor(null, null)).toBeNull()
    expect(decodeCursorAnchor(4, null)).toBeNull()
    expect(decodeCursorAnchor(null, 4)).toBeNull()
  })
})
