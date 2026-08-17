import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { markExpandsAtEnd } from './types'

interface MarkTypeTable {
  expandsAtEnd: string[]
  doesNotExpandAtEnd: string[]
}

const TABLE = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', '..', 'conformance', 'schema', 'mark-types.json'),
    'utf8',
  ),
) as MarkTypeTable

describe('the mark type table', () => {
  it('agrees with the shared source the server reads too', () => {
    expect(TABLE.expandsAtEnd.length).toBeGreaterThan(0)
    expect(TABLE.doesNotExpandAtEnd.length).toBeGreaterThan(0)

    for (const type of TABLE.expandsAtEnd) {
      expect(markExpandsAtEnd(type), `${type} does not expand in the client but does in Java`).toBe(true)
    }
    for (const type of TABLE.doesNotExpandAtEnd) {
      expect(markExpandsAtEnd(type), `${type} expands in the client but does not in Java`).toBe(false)
    }
  })

  it('an unknown type does not expand', () => {
    expect(markExpandsAtEnd('type-from-a-future-version')).toBe(false)
  })
})
