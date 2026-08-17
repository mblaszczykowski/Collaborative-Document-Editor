import { Block } from './block'
import { decodeOp, type WireOp } from './codec'
import type { Id, Op } from './types'

export interface DecodedSnapshot {
  formatVersion: number
  clock: number
  maxSeen: Map<number, number>
  blocks: Block[]
  marks: Op[]
  gcWatermark: Map<number, number>
}

const SUPPORTED_FORMAT_VERSION = 5

const DROPPED_CONTENT_PLACEHOLDER = '\u0000'

class Reader {
  private readonly bytes: Uint8Array
  private readonly view: DataView
  private offset = 0

  constructor(bytes: Uint8Array) {
    this.bytes = bytes
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  }

  int(): number {
    const value = this.view.getInt32(this.offset)
    this.offset += 4
    return value
  }

  long(): number {
    const value = this.view.getBigInt64(this.offset)
    this.offset += 8
    return Number(value)
  }

  bool(): boolean {
    const value = this.view.getUint8(this.offset)
    this.offset += 1
    return value !== 0
  }

  text(): string {
    if (this.bool()) {
      const length = this.int()
      const slice = this.bytes.subarray(this.offset, this.offset + length)
      this.offset += length
      return new TextDecoder().decode(slice)
    }
    const units = this.int()
    let text = ''
    for (let i = 0; i < units; i++) {
      text += String.fromCharCode(this.view.getUint16(this.offset))
      this.offset += 2
    }
    return text
  }

  get done(): boolean {
    return this.offset >= this.bytes.byteLength
  }
}

export async function gunzip(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

export async function decodeSnapshot(raw: Uint8Array): Promise<DecodedSnapshot | null> {
  let plain: Uint8Array
  try {
    plain = await gunzip(raw)
  } catch {
    return null
  }

  try {
    return readSnapshot(new Reader(plain))
  } catch {
    return null
  }
}

function readSnapshot(reader: Reader): DecodedSnapshot | null {
  const formatVersion = reader.int()
  if (formatVersion !== SUPPORTED_FORMAT_VERSION) {
    return null
  }

  const clock = reader.long()

  const maxSeen = new Map<number, number>()
  const versionEntries = reader.int()
  for (let i = 0; i < versionEntries; i++) {
    const replicaId = reader.int()
    maxSeen.set(replicaId, reader.long())
  }

  const blocks: Block[] = []
  const blockCount = reader.int()
  for (let i = 0; i < blockCount; i++) {
    const start: Id = { replicaId: reader.int(), lamport: reader.long() }
    const hasOrigin = reader.bool()
    const originLeft: Id | null = hasOrigin
      ? { replicaId : reader.int(), lamport: reader.long() }
      : null
    const deleted = reader.bool()
    if (deleted && reader.bool()) {
      reader.int()
      reader.long()
    }
    const hasContent = reader.bool()
    const text = hasContent
      ? reader.text()
      : DROPPED_CONTENT_PLACEHOLDER.repeat(reader.int())
    blocks.push(new Block(start, text, originLeft, deleted))
  }

  const marks: Op[] = []
  const markCount = reader.int()
  for (let i = 0; i < markCount; i++) {
    marks.push(decodeOp(JSON.parse(reader.text()) as WireOp))
  }

  const gcWatermark = new Map<number, number>()
  const watermarkEntries = reader.int()
  for (let i = 0; i < watermarkEntries; i++) {
    const replicaId = reader.int()
    gcWatermark.set(replicaId, reader.long())
  }

  return { formatVersion, clock, maxSeen, blocks, marks, gcWatermark }
}
