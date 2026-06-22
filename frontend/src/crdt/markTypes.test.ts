import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { markExpandsAtEnd } from './types'

/**
 * The twin of `MarkTypesConformanceTest.java` — both sides read THE SAME file.
 *
 * The vector corpus will not catch this: the vectors contain operations that already exist, with
 * specific anchor sides baked in, whereas the type table is used when an operation is CREATED.
 * Changing one side passes the whole corpus green and only manifests for the user as "bold grows
 * when I type and it does not when you do".
 *
 * The mark layer claimed such a test existed on both sides. A review found it existed on neither — the
 * documentation described a safeguard that was not there.
 */

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
    // An empty list would let every regression through — a loop over zero elements always passes.
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
    // The conservative choice, the same on both sides: a mark that expands unexpectedly changes the
    // appearance of text the user never formatted.
    expect(markExpandsAtEnd('type-from-a-future-version')).toBe(false)
  })
})
