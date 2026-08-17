import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { decodeOp, encodeOp, type WireOp } from './codec'
import { RgaDocument } from './document'

interface ExpectedSpan {
  from: number
  to: number
  marks: Record<string, string | null>
}

interface Vector {
  name: string
  description: string
  operations: WireOp[]
  expectedText: string
  expectedFormatting: ExpectedSpan[]
}

function describeFormatting(document: RgaDocument): ExpectedSpan[] {
  return document.formatting().map((span) => ({
    from: span.from,
    to: span.to,
    marks: Object.fromEntries(span.marks),
  }))
}

const VECTOR_DIR = join(__dirname, '..', '..', '..', 'conformance')

function loadVectors(): Vector[] {
  const files = readdirSync(VECTOR_DIR).filter((name) => name.endsWith('.json')).sort()
  return files.map((file) => JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf8')) as Vector)
}

describe('conformance with the Java implementation', () => {
  const vectors = loadVectors()

  it('the vector corpus is non-empty', () => {
    expect(vectors.length).toBeGreaterThan(15)
  })

  for (const vector of vectors) {
    it(`${vector.name} - ${vector.description}`, () => {
      const document = new RgaDocument(999)
      document.applyAll(vector.operations.map(decodeOp))

      expect(document.text).toBe(vector.expectedText)
      expect(document.pendingCount, 'operations were left in the buffer').toBe(0)

      expect(describeFormatting(document), 'the formatting diverged from the Java implementation')
        .toEqual(vector.expectedFormatting)
    })
  }

  it('a round trip through the codec does not change an operation', () => {
    for (const vector of vectors) {
      for (const wire of vector.operations) {
        expect(encodeOp(decodeOp(wire))).toEqual(wire)
      }
    }
  })

  it('delivery order does not change the result', () => {
    for (const vector of vectors) {
      const reversed = new RgaDocument(999)
      reversed.applyAll([...vector.operations].reverse().map(decodeOp))
      expect(reversed.text, `vector ${vector.name} depends on order`).toBe(vector.expectedText)
      expect(
        describeFormatting(reversed),
        `the formatting of vector ${vector.name} depends on delivery order`,
      ).toEqual(vector.expectedFormatting)
    }
  })
})
