import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { RgaDocument } from './document'
import { decodeSnapshot } from './snapshot'

/**
 * The twin of `SnapshotFixtureTest.java` — both sides read THE SAME BYTES (CP24).
 *
 * The vector corpus cannot catch a divergence here. Vectors check that both implementations
 * *merge* the same way; the snapshot format is an agreement about *bytes*, and only one side
 * writes it. A decoder that misreads a field does not throw — it produces a document subtly
 * unlike the one on the server, and the first keystroke merges that difference into everybody's
 * copy.
 */

const DIRECTORY = join(__dirname, '..', '..', '..', 'conformance', 'snapshots')

function fixture(): Uint8Array {
  return new Uint8Array(readFileSync(join(DIRECTORY, 'document-v3.bin')))
}

interface Expected {
  text: string
  marks: { from: number; to: number; marks: Record<string, string | null> }[]
}

const expected = JSON.parse(
  readFileSync(join(DIRECTORY, 'document-v3.expected.json'), 'utf8'),
) as Expected

describe('snapshot decoding', () => {
  it('reads the fixture the Java encoder produced', async () => {
    const decoded = await decodeSnapshot(fixture())
    expect(decoded).not.toBeNull()

    const document = new RgaDocument(0)
    document.restoreFrom(decoded!)

    // The text is the whole point: a document opened from a snapshot has to be the document
    // the server has, character for character. The fixture contains text outside ASCII on
    // purpose — the length prefix counts BYTES and this side counts characters, which is the
    // classic way a binary decoder goes wrong on the second language it meets.
    expect(document.text).toBe(expected.text)
  })

  it('reads the formatting, not only the characters', async () => {
    const decoded = await decodeSnapshot(fixture())
    const document = new RgaDocument(0)
    document.restoreFrom(decoded!)

    const spans = document.formatting().map((span) => ({
      from: span.from,
      to: span.to,
      marks: Object.fromEntries([...span.marks.entries()]),
    }))

    expect(spans).toEqual(
      expected.marks.map((span) => ({ from: span.from, to: span.to, marks: span.marks })),
    )
  })

  it('adopts the replica identifier the server assigns AFTER loading a snapshot', async () => {
    const decoded = await decodeSnapshot(fixture())
    const document = new RgaDocument(0)
    document.restoreFrom(decoded!)

    // The guard on adoptReplicaId used to key on the Lamport clock, which a snapshot sets
    // without this replica having produced anything. A document opened from a snapshot then
    // refused the identifier the server had just assigned it — and stayed on id 0, the one
    // identifier guaranteed to collide with another replica.
    expect(() => document.adoptReplicaId(42)).not.toThrow()
    expect(document.replicaId).toBe(42)
  })

  it('refuses a snapshot in a format it does not know, instead of half-reading it', async () => {
    const bytes = fixture()
    const plain = await new Response(
      new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip')),
    ).arrayBuffer()
    const tampered = new Uint8Array(plain)
    // The first four bytes are the format version.
    new DataView(tampered.buffer).setInt32(0, 99)
    const recompressed = new Uint8Array(
      await new Response(
        new Blob([tampered as BlobPart]).stream().pipeThrough(new CompressionStream('gzip')),
      ).arrayBuffer(),
    )

    expect(await decodeSnapshot(recompressed)).toBeNull()
  })

  it('treats unreadable bytes as a cache miss rather than an error', async () => {
    expect(await decodeSnapshot(new Uint8Array([1, 2, 3, 4]))).toBeNull()
  })
})
