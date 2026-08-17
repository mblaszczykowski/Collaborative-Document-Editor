import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { RgaDocument } from './document'
import { decodeSnapshot } from './snapshot'

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

    expect(() => document.adoptReplicaId(42)).not.toThrow()
    expect(document.replicaId).toBe(42)
  })

  it('refuses a snapshot in a format it does not know, instead of half-reading it', async () => {
    const bytes = fixture()
    const plain = await new Response(
      new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip')),
    ).arrayBuffer()
    const tampered = new Uint8Array(plain)
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

  it('reads a block whose text is not well-formed UTF-16', async () => {
    const parts: number[] = []
    const int = (value: number) => {
      parts.push((value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff)
    }
    const long = (value: number) => {
      int(0)
      int(value)
    }
    int(5)
    long(3)
    int(0)
    int(1)
    int(9)
    long(0)
    parts.push(0)
    parts.push(0)
    parts.push(1)
    parts.push(0)
    int(2)
    parts.push(0xd8, 0x3d)
    parts.push(0x00, 0x78)
    int(0)
    int(0)

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
    expect(Array.from({ length: text.length }, (_, i) => text.charCodeAt(i)))
      .toEqual([0xd83d, 0x78])
  })

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
