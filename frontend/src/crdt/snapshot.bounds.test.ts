import { gzipSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { decodeSnapshot, gunzip, SNAPSHOT_LIMITS } from './snapshot'

class Writer {
  private parts: number[] = []
  int(value: number) { const bytes = Buffer.alloc(4); bytes.writeInt32BE(value); this.parts.push(...bytes); return this }
  long(value: bigint) { const bytes = Buffer.alloc(8); bytes.writeBigInt64BE(value); this.parts.push(...bytes); return this }
  bool(value: number) { this.parts.push(value); return this }
  bytes(value: Uint8Array) { this.parts.push(...value); return this }
  gzip() { return new Uint8Array(gzipSync(new Uint8Array(this.parts))) }
}

function header(clock = 0n, version = 0) { return new Writer().int(5).long(clock).int(version) }
function contentless(length: number, deleted = 1) {
  return header().int(1).int(1).long(0n).bool(0).bool(deleted)
    .bytes(deleted ? new Uint8Array([0]) : new Uint8Array())
    .bool(0).int(length).int(0).int(0)
}
function block(writer: Writer, start: bigint, text: Uint8Array) {
  return writer.int(1).long(start).bool(0).bool(0).bool(1).bool(1).int(text.length).bytes(text)
}

describe('snapshot corruption and allocation budgets', () => {
  it.each([-1, SNAPSHOT_LIMITS.blocks + 1])('rejects invalid block counts %s', async (count) => {
    expect(await decodeSnapshot(header().int(count).int(0).int(0).gzip())).toBeNull()
  })
  it.each([-1n, 2n ** 53n + 1n])('rejects negative or unsafe counters %s', async (clock) => {
    expect(await decodeSnapshot(header(clock).int(0).int(0).int(0).gzip())).toBeNull()
  })
  it('rejects oversized contentless blocks before allocating placeholders', async () => {
    expect(await decodeSnapshot(contentless(SNAPSHOT_LIMITS.characterUnits + 1).gzip())).toBeNull()
  })
  it.each([100_000, SNAPSHOT_LIMITS.characterUnits])('accepts bounded contentless blocks of %s units', async (units) => {
    const decoded = await decodeSnapshot(contentless(units).gzip())
    expect(decoded?.blocks[0].text.length).toBe(units)
    expect(decoded?.blocks[0].deleted).toBe(true)
  })
  it('rejects contentless visible blocks and zero lengths', async () => {
    expect(await decodeSnapshot(contentless(2, 0).gzip())).toBeNull()
    expect(await decodeSnapshot(contentless(0).gzip())).toBeNull()
  })
  it('rejects truncated text, invalid UTF-8, and non-boolean flag bytes', async () => {
    expect(await decodeSnapshot(header().int(1).int(1).long(0n).bool(0).bool(0).bool(1).bool(1).int(20).gzip()))
      .toBeNull()
    expect(await decodeSnapshot(block(header(1n).int(1), 0n, new Uint8Array([0xff])).int(0).int(0).gzip()))
      .toBeNull()
    expect(await decodeSnapshot(header().int(1).int(1).long(0n).bool(2).int(0).int(0).gzip())).toBeNull()
  })
  it('preserves a leading UTF-8 BOM as actual document text', async () => {
    const decoded = await decodeSnapshot(block(header(2n).int(1), 0n,
      new TextEncoder().encode('\ufeffx')).int(0).int(0).gzip())
    expect(decoded?.blocks[0].text).toBe('\ufeffx')
  })
  it('rejects duplicate vectors, overlapping identities, and trailing payload bytes', async () => {
    expect(await decodeSnapshot(header(1n, 2).int(1).long(0n).int(1).long(0n)
      .int(0).int(0).int(0).gzip())).toBeNull()
    const overlap = block(block(header(1n).int(2), 0n, new Uint8Array([120])), 0n, new Uint8Array([121]))
    expect(await decodeSnapshot(overlap.int(0).int(0).gzip())).toBeNull()
    expect(await decodeSnapshot(header().int(0).int(0).int(0).bool(0).gzip())).toBeNull()
  })
  it('rejects non-mark operations inside the mark section', async () => {
    const json = new TextEncoder().encode(JSON.stringify({ t: 'i', r: 1, l: 0, s: 'x' }))
    expect(await decodeSnapshot(header(1n).int(0).int(1).bool(1).int(json.length)
      .bytes(json).int(0).gzip())).toBeNull()
  })
  it('rejects aggregate placeholder expansion beyond the shared character budget', async () => {
    const writer = header().int(2)
    for (let replica = 1; replica <= 2; replica++) {
      writer.int(replica).long(0n).bool(0).bool(1).bool(0).bool(0)
        .int(SNAPSHOT_LIMITS.characterUnits / 2 + 1)
    }
    expect(await decodeSnapshot(writer.int(0).int(0).gzip())).toBeNull()
  })
  it('stops streaming gzip expansion at the decompressed budget', async () => {
    const bomb = new Uint8Array(gzipSync(Buffer.alloc(SNAPSHOT_LIMITS.decompressedBytes + 1)))
    await expect(gunzip(bomb)).rejects.toThrow('exceeds budget')
  }, 20_000)
  it('rejects oversized compressed input before decompression', async () => {
    await expect(gunzip(new Uint8Array(SNAPSHOT_LIMITS.compressedBytes + 1)))
      .rejects.toThrow('compressed snapshot exceeds budget')
  })
})
