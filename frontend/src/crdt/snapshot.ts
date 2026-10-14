import { Block } from './block'
import { decodeOp, MAX_LAMPORT, type WireOp } from './codec'
import type { Id, Op } from './types'

export interface DecodedSnapshot {
  formatVersion: number
  clock: number
  maxSeen: Map<number, number>
  blocks: Block[]
  marks: Op[]
  deleteIds?: Id[]
  gcWatermark: Map<number, number>
}

export const SNAPSHOT_LIMITS = {
  compressedBytes: 16 * 1024 * 1024,
  decompressedBytes: 64 * 1024 * 1024,
  textBytes: 16 * 1024 * 1024,
  characterUnits: 16 * 1024 * 1024,
  blocks: 200_000,
  marks: 100_000,
  vectorEntries: 50_000,
} as const

const SUPPORTED_FORMAT_VERSION = 5
const DROPPED_CONTENT_PLACEHOLDER = '\u0000'
const UTF8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })

class Reader {
  private readonly bytes: Uint8Array
  private readonly view: DataView
  private offset = 0
  private remainingUnits: number = SNAPSHOT_LIMITS.characterUnits

  constructor(bytes: Uint8Array) {
    this.bytes = bytes
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  }

  private ensure(size: number): void {
    if (!Number.isSafeInteger(size) || size < 0 || size > this.bytes.length - this.offset) {
      throw new Error('truncated snapshot')
    }
  }

  int(): number {
    this.ensure(4)
    const value = this.view.getInt32(this.offset)
    this.offset += 4
    return value
  }

  count(maximum: number, wireBytes = 0): number {
    const value = this.int()
    if (value < 0 || value > maximum) throw new Error('snapshot count exceeds budget')
    this.ensure(value * wireBytes)
    return value
  }

  long(maximum = MAX_LAMPORT): number {
    this.ensure(8)
    const value = this.view.getBigInt64(this.offset)
    this.offset += 8
    if (value < 0n || value > BigInt(maximum)) throw new Error('invalid snapshot counter')
    const number = Number(value)
    if (!Number.isSafeInteger(number)) throw new Error('unsafe snapshot counter')
    return number
  }

  bool(): boolean {
    this.ensure(1)
    const value = this.view.getUint8(this.offset++)
    if (value !== 0 && value !== 1) throw new Error('invalid snapshot boolean')
    return value === 1
  }

  consumeUnits(count: number): void {
    if (count > this.remainingUnits) throw new Error('snapshot character budget exceeded')
    this.remainingUnits -= count
  }

  text(): string {
    let text: string
    if (this.bool()) {
      const length = this.count(SNAPSHOT_LIMITS.textBytes, 1)
      text = UTF8.decode(this.bytes.subarray(this.offset, this.offset + length))
      this.offset += length
    } else {
      const units = this.count(SNAPSHOT_LIMITS.textBytes / 2, 2)
      const pieces: string[] = []
      for (let from = 0; from < units; from += 8192) {
        const values: number[] = []
        for (let i = from; i < Math.min(units, from + 8192); i++) {
          values.push(this.view.getUint16(this.offset))
          this.offset += 2
        }
        pieces.push(String.fromCharCode(...values))
      }
      text = pieces.join('')
    }
    this.consumeUnits(text.length)
    return text
  }

  get done(): boolean { return this.offset === this.bytes.length }
}

export async function gunzip(data: Uint8Array): Promise<Uint8Array> {
  if (data.byteLength > SNAPSHOT_LIMITS.compressedBytes) {
    throw new Error('compressed snapshot exceeds budget')
  }
  const reader = new Blob([data as BlobPart]).stream()
    .pipeThrough(new DecompressionStream('gzip')).getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    for (;;) {
      const next = await reader.read()
      if (next.done) break
      size += next.value.byteLength
      if (size > SNAPSHOT_LIMITS.decompressedBytes) {
        await reader.cancel('snapshot decompression budget exceeded')
        throw new Error('decompressed snapshot exceeds budget')
      }
      chunks.push(next.value)
    }
  } finally {
    reader.releaseLock()
  }
  const result = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.length
  }
  return result
}

export async function decodeSnapshot(raw: Uint8Array): Promise<DecodedSnapshot | null> {
  try {
    const reader = new Reader(await gunzip(raw))
    const snapshot = readSnapshot(reader)
    return reader.done ? snapshot : null
  } catch {
    return null
  }
}

function readVector(reader: Reader): Map<number, number> {
  const result = new Map<number, number>()
  const count = reader.count(SNAPSHOT_LIMITS.vectorEntries, 12)
  for (let i = 0; i < count; i++) {
    const replica = reader.int()
    if (result.has(replica)) throw new Error('duplicate snapshot vector identity')
    result.set(replica, reader.long())
  }
  return result
}

function readSnapshot(reader: Reader): DecodedSnapshot | null {
  const formatVersion = reader.int()
  if (formatVersion !== SUPPORTED_FORMAT_VERSION) return null
  const clock = reader.long(MAX_LAMPORT + 1)
  const maxSeen = readVector(reader)
  const blocks: Block[] = []
  const deleteIds: Id[] = []
  const intervals = new Map<number, { from: number; to: number }[]>()
  const blockCount = reader.count(SNAPSHOT_LIMITS.blocks, 20)
  for (let i = 0; i < blockCount; i++) {
    const start: Id = { replicaId: reader.int(), lamport: reader.long() }
    const originLeft: Id | null = reader.bool()
      ? { replicaId: reader.int(), lamport: reader.long() } : null
    const deleted = reader.bool()
    if (deleted && reader.bool()) {
      deleteIds.push({ replicaId: reader.int(), lamport: reader.long() })
    }
    const hasContent = reader.bool()
    if (!hasContent && !deleted) throw new Error('visible block has no content')
    let text: string
    if (hasContent) {
      text = reader.text()
    } else {
      const length = reader.count(SNAPSHOT_LIMITS.characterUnits)
      reader.consumeUnits(length)
      text = DROPPED_CONTENT_PLACEHOLDER.repeat(length)
    }
    if (text.length === 0 || start.lamport + text.length - 1 > MAX_LAMPORT) {
      throw new Error('invalid snapshot block range')
    }
    const ranges = intervals.get(start.replicaId) ?? []
    ranges.push({ from: start.lamport, to: start.lamport + text.length })
    intervals.set(start.replicaId, ranges)
    blocks.push(new Block(start, text, originLeft, deleted))
  }
  for (const ranges of intervals.values()) {
    ranges.sort((a, b) => a.from - b.from)
    for (let i = 1; i < ranges.length; i++) {
      if (ranges[i].from < ranges[i - 1].to) throw new Error('overlapping snapshot identities')
    }
  }
  const marks: Op[] = []
  const markIds = new Set<string>()
  const markCount = reader.count(SNAPSHOT_LIMITS.marks, 6)
  for (let i = 0; i < markCount; i++) {
    const mark = decodeOp(JSON.parse(reader.text()) as WireOp)
    if (mark.kind !== 'addMark' && mark.kind !== 'removeMark') {
      throw new Error('snapshot contains a non-mark operation')
    }
    const key = `${mark.id.replicaId}@${mark.id.lamport}`
    if (markIds.has(key)) throw new Error('duplicate snapshot mark identity')
    markIds.add(key)
    marks.push(mark)
  }
  const gcWatermark = readVector(reader)
  return { formatVersion, clock, maxSeen, blocks, marks, gcWatermark, deleteIds }
}
