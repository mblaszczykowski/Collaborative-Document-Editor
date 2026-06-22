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
  return new Uint8Array(readFileSync(join(DIRECTORY, 'document-v5.bin')))
}

interface Expected {
  text: string
  marks: { from: number; to: number; marks: Record<string, string | null> }[]
}

const expected = JSON.parse(
  readFileSync(join(DIRECTORY, 'document-v5.expected.json'), 'utf8'),
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

  /**
   * The one branch of the format the shared fixture cannot carry.
   *
   * A block split between the two halves of an emoji holds a lone surrogate, so the document's
   * text is not a well-formed string — and writing it into `document-v5.expected.json` would
   * corrupt it in exactly the way this fixture exists to detect. Java covers the round trip in
   * `SnapshotCodecTest`; this covers the decoder, against bytes assembled to the spec in
   * `SnapshotCodec`'s class javadoc rather than to whatever the decoder happens to do.
   */
  it('reads a block whose text is not well-formed UTF-16', async () => {
    const parts: number[] = []
    const int = (value: number) => {
      parts.push((value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff)
    }
    const long = (value: number) => {
      int(0)
      int(value)
    }
    int(5) //                       formatVersion
    long(3) //                      clock
    int(0) //                       no version-vector entries
    int(1) //                       one block
    int(9)
    long(0) //                      start = 9@0
    parts.push(0) //                no origin
    parts.push(0) //                not deleted
    parts.push(1) //                has content
    parts.push(0) //                ... and it is NOT well-formed
    int(2) //                       two UTF-16 code units
    parts.push(0xd8, 0x3d) //       a high surrogate, alone
    parts.push(0x00, 0x78) //       'x'
    int(0) //                       no marks
    int(0) //                       no GC-watermark entries

    const gzipped = new Uint8Array(
      await new Response(
        new Blob([new Uint8Array(parts) as BlobPart])
          .stream()
          .pipeThrough(new CompressionStream('gzip')),
      ).arrayBuffer(),
    )

    const decoded = await decodeSnapshot(gzipped)
    expect(decoded).not.toBeNull()
    expect(decoded!.blocks).toHaveLength(1)
    const text = decoded!.blocks[0].text
    // Indexed by CODE UNIT, not by code point: spreading the string would iterate code points and
    // is exactly the distinction under test here.
    expect(Array.from({ length: text.length }, (_, i) => text.charCodeAt(i)))
      .toEqual([0xd83d, 0x78])
  })

  /**
   * A stream that is valid gzip but SHORT.
   *
   * The contract three paragraphs into `snapshot.ts` is that an unreadable snapshot returns `null`
   * and the caller replays the log. The `try` only wrapped `gunzip`, so a truncated body — a cut
   * response, a partial write — made `DataView.getInt32` throw `RangeError` straight out of a
   * function documented never to throw. `SnapshotCodec.decode` catches `RuntimeException` for
   * exactly this case; this side caught only the compression failure, which is the one the format
   * can already describe.
   */
  it('returns null for a truncated snapshot rather than throwing', async () => {
    const truncated = new Uint8Array(
      await new Response(
        new Blob([new Uint8Array([0, 0, 0, 5, 0, 0]) as BlobPart])
          .stream()
          .pipeThrough(new CompressionStream('gzip')),
      ).arrayBuffer(),
    )
    await expect(decodeSnapshot(truncated)).resolves.toBeNull()
  })
})
