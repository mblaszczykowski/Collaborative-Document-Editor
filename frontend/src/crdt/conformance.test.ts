import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { decodeOp, encodeOp, type WireOp } from './codec'
import { RgaDocument } from './document'

/**
 * Test, który spina dwie implementacje jednego algorytmu.
 *
 * ADR-0001 przyjmuje świadomy dług: RGA istnieje w Javie (serwer) i w TypeScripcie (klient
 * edytujący offline). Dwie implementacje jednej specyfikacji rozjadą się prędzej czy później,
 * a objawi się to najgorszym możliwym błędem — „u mnie wygląda inaczej niż u ciebie".
 *
 * Wektory w `conformance/` są GENEROWANE przez testy w Javie i wersjonowane w repo. Ten
 * test odtwarza je krok w krok. CI uruchamia obie strony i osobno pilnuje, żeby korpus
 * w repo był aktualny wobec implementacji w Javie — dzięki czemu rozjazd nie może przejść
 * po cichu w żadną stronę.
 */

interface Vector {
  name: string
  description: string
  operations: WireOp[]
  expectedText: string
}

const VECTOR_DIR = join(__dirname, '..', '..', '..', 'conformance')

function loadVectors(): Vector[] {
  const files = readdirSync(VECTOR_DIR).filter((name) => name.endsWith('.json')).sort()
  return files.map((file) => JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf8')) as Vector)
}

describe('zgodność z implementacją w Javie', () => {
  const vectors = loadVectors()

  it('korpus wektorów jest niepusty', () => {
    // Bez tej asercji pusty albo źle wskazany katalog dawałby zielony przebieg
    // i cała ochrona przed rozjazdem przestawałaby działać w ciszy.
    expect(vectors.length).toBeGreaterThan(15)
  })

  for (const vector of vectors) {
    it(`${vector.name} — ${vector.description}`, () => {
      const document = new RgaDocument(999)
      document.applyAll(vector.operations.map(decodeOp))

      expect(document.text).toBe(vector.expectedText)
      expect(document.pendingCount, 'operacje zostały w buforze').toBe(0)
    })
  }

  it('kodowanie w obie strony nie zmienia operacji', () => {
    for (const vector of vectors) {
      for (const wire of vector.operations) {
        expect(encodeOp(decodeOp(wire))).toEqual(wire)
      }
    }
  })

  it('kolejność dostarczenia nie zmienia wyniku', () => {
    // Wektory są zapisane w konkretnej kolejności, ale zbieżność ma nie zależeć od żadnej.
    // Odwrócenie jest tanim, deterministycznym sprawdzianem tej własności po stronie TS —
    // pełne losowe permutacje robi property test w Javie.
    for (const vector of vectors) {
      const reversed = new RgaDocument(999)
      reversed.applyAll([...vector.operations].reverse().map(decodeOp))
      expect(reversed.text, `wektor ${vector.name} zależy od kolejności`).toBe(vector.expectedText)
    }
  })
})
