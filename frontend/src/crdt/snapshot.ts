import { Block } from './block'
import { decodeOp, type WireOp } from './codec'
import type { Id, Op } from './types'

/**
 * Reading a document snapshot produced by the server (CP24).
 *
 * Until CP24 opening a document meant replaying **the entire operation log** on the client: a
 * hundred thousand characters typed one at a time is a hundred thousand operations over the
 * socket, each one integrated by the merge algorithm, to arrive at a state the server already
 * had in one object. The server has used snapshots since CP3; the client did not,
 * and that was the largest remaining product debt in the previous plan's own account.
 *
 * ## This file is the two-implementation debt, priced
 *
 * The README concluded from CP18 — where a change to the core cost nothing on the client —
 * that the debt of two implementations is proportional to how much of the algorithm the client
 * actually implements, rather than to the size of the algorithm. This is that conclusion tested
 * from the other side: the client does not have GC, does not write snapshots, and still needs
 * to READ the format, so a decoder has to exist twice. It is about 120 lines here against about
 * 100 in `SnapshotCodec`, which is roughly what "proportional to what the client implements"
 * predicts.
 *
 * ## Format
 *
 * Byte-for-byte the format documented in `SnapshotCodec`: a GZIP stream wrapping big-endian
 * `DataOutputStream` output. Version 5, and a version this file does not know is not an error —
 * it returns `null` and the caller falls back to replaying the log, because a snapshot is a
 * cache and rebuilding from the log is always correct. That is the operation-log decision paying for itself on
 * the client too.
 *
 * Version 5 added the identifier of the operation that deleted a tombstone. The client has no
 * garbage collection, so it has nothing to do with the value — but it has to READ it, or the
 * reader is misaligned for every field after it. That is the same argument `gcWatermark` below
 * already makes, and it is the whole cost of the format change on this side.
 */

export interface DecodedSnapshot {
  formatVersion: number
  clock: number
  maxSeen: Map<number, number>
  blocks: Block[]
  marks: Op[]
  /**
   * The server's GC boundary.
   *
   * Read but not used: the client has no garbage collection, so it has nothing to compare against.
   * It is decoded anyway because the field is in the stream and skipping it would leave the reader
   * misaligned for anything appended after it — and because a client that later wants to tell "my
   * operation outran its cause" from "its cause is gone for good" already has the number.
   */
  gcWatermark: Map<number, number>
}

/**
 * The version this decoder understands. Anything else falls back to the log.
 *
 * Not exported. Nothing outside this file has ever imported it, and nothing should: the only
 * sensible thing to do with the number is compare it against the byte in a stream, which is what
 * `decodeSnapshot` does. Exporting it invites a second version check somewhere that cannot see the
 * stream — and two places deciding whether a snapshot is readable is how one of them ends up
 * saying yes to a format the other cannot parse.
 */
const SUPPORTED_FORMAT_VERSION = 5

/**
 * A stand-in for the text of a block whose content the server's GC dropped (CP18).
 *
 * The client has no garbage collection and therefore no representation for "a tombstone that
 * kept its identity and lost its text" — its `Block` requires non-empty text. It does not need
 * one either: such a block is deleted, so nothing ever reads its characters, and **the only
 * thing any index needs from it is its length**. That sentence is CP18's own conclusion, and it
 * is why one placeholder character repeated is a complete answer here rather than a hack.
 */
const DROPPED_CONTENT_PLACEHOLDER = '\u0000'

class Reader {
  // Declared as fields and assigned in the body rather than as a `private readonly` constructor
  // parameter property: the latter is TypeScript-only syntax that cannot be erased, and
  // `erasableSyntaxOnly` in tsconfig.app.json rejects it. `bytes` is kept because `text` slices
  // it directly — a DataView cannot hand a subarray to TextDecoder.
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
    // Lamport clocks are written as 64-bit and stay far inside the range a double represents
    // exactly (2^53). Reading them as bigint would make every comparison in the algorithm
    // convert, for a limit no document will reach: 9 quadrillion operations.
    const value = this.view.getBigInt64(this.offset)
    this.offset += 8
    return Number(value)
  }

  bool(): boolean {
    const value = this.view.getUint8(this.offset)
    this.offset += 1
    return value !== 0
  }

  /**
   * A string — `SnapshotCodec.writeText`.
   *
   * Two forms, distinguished by a leading flag. The common one is a length-prefixed UTF-8 string,
   * exactly as format version 3 wrote it. The other exists because identity in this CRDT is per
   * UTF-16 code unit, so a block boundary can fall between the two halves of an emoji and leave a
   * lone surrogate — which is not encodable as UTF-8, and which Java's encoder used to silently
   * turn into `?`. The length survived the substitution, so nothing broke visibly and the snapshot
   * simply disagreed with the operation log. See `SnapshotCodec`'s class javadoc.
   */
  text(): string {
    if (this.bool()) {
      const length = this.int()
      const slice = this.bytes.subarray(this.offset, this.offset + length)
      this.offset += length
      return new TextDecoder().decode(slice)
    }
    const units = this.int()
    // Built from code units directly: `TextDecoder` would replace a lone surrogate with U+FFFD,
    // which is the same loss in a different colour. Appended one at a time rather than spread into
    // `String.fromCharCode` — a run-length block runs to a hundred thousand characters, and that
    // many arguments overflows the call stack.
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

/** Decompresses a GZIP stream. Available in every browser this app supports, and in Node 18+. */
export async function gunzip(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

/**
 * Decodes a snapshot, or returns `null` when it is in a format this client does not know.
 *
 * @param raw the GZIP-compressed bytes exactly as the server sent them
 */
export async function decodeSnapshot(raw: Uint8Array): Promise<DecodedSnapshot | null> {
  let plain: Uint8Array
  try {
    plain = await gunzip(raw)
  } catch {
    // Not a snapshot we can read. Same answer as a version mismatch: fall back to the log.
    return null
  }

  // Everything below reads from a fixed-size buffer, so a stream that is valid gzip but SHORT —
  // a truncated response, a partial write — makes `DataView` throw `RangeError` rather than
  // returning a wrong number. That escaped as an exception from a function whose contract, three
  // paragraphs above, is that an unreadable snapshot returns `null` and the caller replays the
  // log. `SnapshotCodec.decode` catches `RuntimeException` for exactly this reason; the catch was
  // only around `gunzip` here, so the one failure the format cannot self-describe was the one
  // that got through.
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
    // Version 5: a tombstone records which operation deleted it, because the server's GC has to
    // ask whether THAT operation is causally stable rather than whether the insertion is. Read
    // and discarded — see the file header.
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
