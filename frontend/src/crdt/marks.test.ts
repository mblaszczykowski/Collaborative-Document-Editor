import { describe, expect, test } from 'vitest'
import { decodeOp, encodeOp } from './codec'
import { RgaDocument } from './document'

/**
 * Client-side formatting (CP13) — the twin of `MarkTest.java`.
 *
 * The same scenarios as in Java, deliberately: the two implementations have to behave identically,
 * and the conformance corpus enforces that for operations that already exist. What it does not
 * enforce is the decisions made when an operation is CREATED — the choice of anchor sides — because
 * those happen before anything comparable exists. Hence these tests.
 */

/** The formatting of the character at a given position — easier to assert on than a list of runs. */
function marksAt(document: RgaDocument, index: number): ReadonlyMap<string, string | null> {
  for (const span of document.formatting()) {
    if (index >= span.from && index < span.to) return span.marks
  }
  return new Map()
}

describe('formatting', () => {
  test('text typed inside a bold range inherits it, even though its author had not seen it', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)

    const base = ania.insert(0, 'abcdef')
    bartek.apply(base)

    const bold = ania.addMark(1, 5, 'bold', null)
    // Bartek had NOT seen the bold mark yet.
    const inserted = bartek.insert(3, 'XY')

    ania.apply(inserted)
    bartek.apply(bold)

    expect(ania.text).toBe('abcXYdef')
    expect(bartek.text).toBe(ania.text)
    expect(marksAt(ania, 3).has('bold')).toBe(true)
    expect(bartek.formatting()).toEqual(ania.formatting())
  })

  test('bold grows when typing at its end', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(1, 3, 'bold', null)

    document.insert(3, 'X')

    expect(document.text).toBe('abcXdef')
    expect(marksAt(document, 3).has('bold')).toBe(true)
  })

  test('a link does NOT grow when typing at its end', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(1, 3, 'link', 'https://example.test')

    document.insert(3, 'X')

    expect(marksAt(document, 2).get('link')).toBe('https://example.test')
    expect(marksAt(document, 3).has('link')).toBe(false)
  })

  test('formatting does not grow backwards', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(2, 4, 'bold', null)

    document.insert(2, 'X')

    expect(document.text).toBe('abXcdef')
    expect(marksAt(document, 2).has('bold')).toBe(false)
    expect(marksAt(document, 3).has('bold')).toBe(true)
  })

  test('a concurrent add and remove converge regardless of order', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)
    const base = ania.insert(0, 'abcdef')
    bartek.apply(base)

    const bold = ania.addMark(1, 4, 'bold', null)
    const unbold = bartek.removeMark(1, 4, 'bold')

    ania.apply(unbold)
    bartek.apply(bold)

    expect(bartek.formatting()).toEqual(ania.formatting())

    const third = new RgaDocument(3)
    third.apply(base)
    third.apply(unbold)
    third.apply(bold)
    expect(third.formatting()).toEqual(ania.formatting())
  })

  test('a mark waits in the buffer for its anchors', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)

    const base = ania.insert(0, 'abcdef')
    const bold = ania.addMark(1, 4, 'bold', null)

    expect(bartek.apply(bold)).toBe('buffered')
    expect(bartek.formatting()).toEqual([])

    bartek.apply(base)

    expect(bartek.formatting()).toEqual(ania.formatting())
    expect(bartek.pendingCount).toBe(0)
  })

  test('formatting survives deletion of the neighbouring text', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(2, 4, 'bold', null)

    document.delete(0, 2)

    expect(document.text).toBe('cdef')
    expect(marksAt(document, 0).has('bold')).toBe(true)
    expect(marksAt(document, 2).has('bold')).toBe(false)
  })

  test('a repeated mark is recognised as a duplicate', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)
    bartek.apply(ania.insert(0, 'abcdef'))

    const bold = ania.addMark(1, 4, 'bold', null)
    expect(bartek.apply(bold)).toBe('applied')
    expect(bartek.apply(bold)).toBe('duplicate')
    expect(bartek.markCount).toBe(1)
  })

  test('adjacent runs with the same formatting are merged', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(0, 3, 'bold', null)
    document.addMark(3, 6, 'bold', null)

    const spans = document.formatting()
    expect(spans).toHaveLength(1)
    expect(spans[0].from).toBe(0)
    expect(spans[0].to).toBe(6)
  })

  test('a mark round-trips through the wire format', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')

    const added = document.addMark(1, 4, 'link', 'https://example.test/a?b=c')
    const removed = document.removeMark(2, 5, 'bold')

    expect(decodeOp(encodeOp(added))).toEqual(added)
    expect(decodeOp(encodeOp(removed))).toEqual(removed)
  })

  test('the value field is OMITTED when unused — it has to match Java', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    const bold = document.addMark(1, 4, 'bold', null)

    // Java omits the `v` field for a mark with no value. Sending `"v":null` would give different
    // bytes on the wire for the same operation, and the conformance vectors compare text.
    expect(Object.keys(encodeOp(bold))).not.toContain('v')
  })
})
