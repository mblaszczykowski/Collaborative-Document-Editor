import { describe, expect, test } from 'vitest'
import { decodeOp, encodeOp } from './codec'
import { RgaDocument } from './document'

/**
 * Formatowanie po stronie klienta (CP13) — bliźniak `MarkTest.java`.
 *
 * Te same scenariusze co w Javie i to jest celowe: obie implementacje muszą zachowywać się
 * identycznie, a korpus konformancyjny pilnuje tego dla operacji już utworzonych. Nie pilnuje
 * natomiast decyzji podejmowanych przy TWORZENIU operacji — wyboru stron kotwic — bo te
 * dzieją się przed powstaniem czegokolwiek, co dałoby się porównać. Stąd te testy.
 */

/** Formatowanie znaku na danej pozycji — wygodniejsze w asercjach niż lista odcinków. */
function marksAt(document: RgaDocument, index: number): ReadonlyMap<string, string | null> {
  for (const span of document.formatting()) {
    if (index >= span.from && index < span.to) return span.marks
  }
  return new Map()
}

describe('formatowanie', () => {
  test('tekst wpisany w środek pogrubienia dziedziczy je, mimo że autor go nie widział', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)

    const base = ania.insert(0, 'abcdef')
    bartek.apply(base)

    const bold = ania.addMark(1, 5, 'bold', null)
    // Bartek NIE widział jeszcze pogrubienia.
    const inserted = bartek.insert(3, 'XY')

    ania.apply(inserted)
    bartek.apply(bold)

    expect(ania.text).toBe('abcXYdef')
    expect(bartek.text).toBe(ania.text)
    expect(marksAt(ania, 3).has('bold')).toBe(true)
    expect(bartek.formatting()).toEqual(ania.formatting())
  })

  test('pogrubienie rośnie przy pisaniu na jego końcu', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(1, 3, 'bold', null)

    document.insert(3, 'X')

    expect(document.text).toBe('abcXdef')
    expect(marksAt(document, 3).has('bold')).toBe(true)
  })

  test('link NIE rośnie przy pisaniu na jego końcu', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(1, 3, 'link', 'https://example.test')

    document.insert(3, 'X')

    expect(marksAt(document, 2).get('link')).toBe('https://example.test')
    expect(marksAt(document, 3).has('link')).toBe(false)
  })

  test('formatowanie nie rośnie wstecz', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(2, 4, 'bold', null)

    document.insert(2, 'X')

    expect(document.text).toBe('abXcdef')
    expect(marksAt(document, 2).has('bold')).toBe(false)
    expect(marksAt(document, 3).has('bold')).toBe(true)
  })

  test('współbieżne nadanie i zdjęcie zbiegają się niezależnie od kolejności', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)
    const base = ania.insert(0, 'abcdef')
    bartek.apply(base)

    const bold = ania.addMark(1, 4, 'bold', null)
    const unbold = bartek.removeMark(1, 4, 'bold')

    ania.apply(unbold)
    bartek.apply(bold)

    expect(bartek.formatting()).toEqual(ania.formatting())

    const trzeci = new RgaDocument(3)
    trzeci.apply(base)
    trzeci.apply(unbold)
    trzeci.apply(bold)
    expect(trzeci.formatting()).toEqual(ania.formatting())
  })

  test('znacznik czeka w buforze na swoje kotwice', () => {
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

  test('formatowanie przeżywa skasowanie sąsiedniego tekstu', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(2, 4, 'bold', null)

    document.delete(0, 2)

    expect(document.text).toBe('cdef')
    expect(marksAt(document, 0).has('bold')).toBe(true)
    expect(marksAt(document, 2).has('bold')).toBe(false)
  })

  test('powtórzony znacznik jest rozpoznawany jako duplikat', () => {
    const ania = new RgaDocument(1)
    const bartek = new RgaDocument(2)
    bartek.apply(ania.insert(0, 'abcdef'))

    const bold = ania.addMark(1, 4, 'bold', null)
    expect(bartek.apply(bold)).toBe('applied')
    expect(bartek.apply(bold)).toBe('duplicate')
    expect(bartek.markCount).toBe(1)
  })

  test('sąsiadujące odcinki o tym samym formatowaniu są sklejane', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    document.addMark(0, 3, 'bold', null)
    document.addMark(3, 6, 'bold', null)

    const spans = document.formatting()
    expect(spans).toHaveLength(1)
    expect(spans[0].from).toBe(0)
    expect(spans[0].to).toBe(6)
  })

  test('format przewodowy znacznika przechodzi w obie strony', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')

    const added = document.addMark(1, 4, 'link', 'https://example.test/a?b=c')
    const removed = document.removeMark(2, 5, 'bold')

    expect(decodeOp(encodeOp(added))).toEqual(added)
    expect(decodeOp(encodeOp(removed))).toEqual(removed)
  })

  test('pole wartości jest POMIJANE, gdy nieużywane — musi zgadzać się z Javą', () => {
    const document = new RgaDocument(1)
    document.insert(0, 'abcdef')
    const bold = document.addMark(1, 4, 'bold', null)

    // Java pomija pole `v` dla znacznika bez wartości. Wysłanie `"v":null` dałoby inny
    // bajt na drucie przy tej samej operacji, a wektory konformancyjne porównują tekst.
    expect(Object.keys(encodeOp(bold))).not.toContain('v')
  })
})
