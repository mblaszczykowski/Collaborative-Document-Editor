import { describe, expect, it } from 'vitest'
import { decodeCursorAnchor, encodeCursorAnchor, type CursorAnchor } from './cursorAnchor'

/**
 * The encoding packs a side into a numeric field, so the property that has to hold is that no two
 * distinct places share a representation. These tests are about the one value that broke the
 * obvious implementation and the one compatibility rule the encoding promises.
 */

describe('the presence cursor encoding', () => {
  it('survives a round trip for both sides, including lamport 0', () => {
    // Lamport 0 is not a corner case here: the clock starts at zero, so the FIRST character of
    // every document has it — and a caret before the first character is precisely the position
    // this encoding was added for. A plain sign flip loses it, because `-0 === 0`, which would
    // have made the fix wrong in exactly its own motivating case.
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
    // A collision here is two different caret positions drawn in one place — the same class of
    // failure as a replica id collision, only visible rather than destructive.
    expect(seen.size).toBe(128)
  })

  it("reads a frame from a client that predates the side as 'after'", () => {
    // Every version of this client before the fix sent `idAtVisible(position - 1)` with the
    // implicit rule "the caret is after this character". Those frames are non-negative lamports,
    // so the rule above reads them correctly. Two clients of different vintages in one document
    // must not disagree about where a cursor is.
    expect(decodeCursorAnchor(9, 41)).toEqual({
      charId: { replicaId: 9, lamport: 41 },
      side: 'after',
    })
  })

  it('carries "nothing to anchor to" through unchanged', () => {
    // An empty document has exactly one place a caret can be and no character to hold it.
    expect(encodeCursorAnchor(null)).toBeNull()
    expect(decodeCursorAnchor(null, null)).toBeNull()
    // A half-null frame cannot be interpreted and is not a place — the two fields always travel
    // together.
    expect(decodeCursorAnchor(4, null)).toBeNull()
    expect(decodeCursorAnchor(null, 4)).toBeNull()
  })
})
