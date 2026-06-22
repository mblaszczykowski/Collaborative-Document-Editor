import {
  id,
  type AddMarkOp,
  type Anchor,
  type DeleteOp,
  type Id,
  type InsertOp,
  type Op,
  type Range,
  type RemoveMarkOp,
} from './types'

/**
 * The wire format for operations — it must be IDENTICAL to `OpCodec` in Java.
 *
 * The field names are single letters deliberately: for an operation carrying one
 * character, `"originLeft"` costs more in the frame than the content itself.
 *
 *   insert:  {"t":"i","r":1,"l":7,"or":2,"ol":3,"s":"abc"}
 *   delete:  {"t":"d","r":1,"l":9,"g":[[2,3,5]]}
 *   mark+:   {"t":"m","r":1,"l":11,"k":"bold","v":"…","a":[2,3,0],"b":[2,7,1]}
 *   mark−:   {"t":"x","r":1,"l":13,"k":"bold","a":[2,3,0],"b":[2,7,1]}
 *
 * An anchor is written as `[replica, lamport, side]`, where side 0 = before, 1 = after. A
 * three-element array rather than an object with field names, for the same reason the fields are
 * single letters: mark operations are numerous, and the field names would repeat in every one of
 * them.
 *
 * This file and `OpCodec.java` are a pair that has to change together. The conformance vectors are
 * written in this format, so a discrepancy shows up immediately in the tests — which is the only
 * reason two hand-maintained implementations of a format are defensible.
 *
 * **What the vectors do NOT check is key ORDER.** They are compared with a deep-equality
 * assertion, so the two encoders could — and did — emit `r,l,t,s,or,ol` here against
 * `r,l,t,or,ol,s` in Java and agree on every vector. Harmless while both sides parse JSON, and it
 * would stop being harmless the moment anything hashed or signed a frame. The literals below are
 * now written in the Java order, and the `v`-omission rule beneath them is justified by matching
 * the other implementation rather than by a string comparison that does not happen.
 */

/** An anchor on the wire: [replica, lamport, side] — side 0 = before, 1 = after. */
type WireAnchor = [number, number, number]

export interface WireOp {
  t: 'i' | 'd' | 'm' | 'x'
  r: number
  l: number
  or?: number
  ol?: number
  s?: string
  g?: [number, number, number][]
  k?: string
  v?: string
  a?: WireAnchor
  b?: WireAnchor
}

function encodeAnchor(anchor: Anchor): WireAnchor {
  return [anchor.charId.replicaId, anchor.charId.lamport, anchor.side === 'before' ? 0 : 1]
}

export function encodeOp(op: Op): WireOp {
  switch (op.kind) {
    case 'insert': {
      // Field order matches OpCodec.java: r, l, t, or, ol, s.
      const wire: WireOp = { r: op.id.replicaId, l: op.id.lamport, t: 'i' }
      if (op.originLeft !== null) {
        wire.or = op.originLeft.replicaId
        wire.ol = op.originLeft.lamport
      }
      wire.s = op.text
      return wire
    }
    case 'delete':
      return {
        r: op.id.replicaId,
        l: op.id.lamport,
        t: 'd',
        g: op.targets.map((range) => [range.start.replicaId, range.start.lamport, range.length]),
      }
    case 'addMark': {
      // Field order matches OpCodec.java: r, l, t, k, v, a, b.
      const wire: WireOp = { r: op.id.replicaId, l: op.id.lamport, t: 'm', k: op.type }
      // The value field is OMITTED when unused, because the Java encoder omits it and `decodeOp`
      // below refuses an explicit null. An earlier comment justified this by saying "the
      // conformance vectors compare strings" — they do not, they compare parsed objects, so the
      // real reason is the one that was always true: the two implementations have to agree.
      if (op.value !== null) {
        wire.v = op.value
      }
      wire.a = encodeAnchor(op.start)
      wire.b = encodeAnchor(op.end)
      return wire
    }
    case 'removeMark':
      return {
        r: op.id.replicaId,
        l: op.id.lamport,
        t: 'x',
        k: op.type,
        a: encodeAnchor(op.start),
        b: encodeAnchor(op.end),
      }
  }
}

/**
 * The largest Lamport timestamp this decoder accepts — the twin of `OpCodec.MAX_LAMPORT`.
 *
 * A replica advances its clock past every timestamp it RECEIVES, before it decides whether the
 * operation is a duplicate or even applicable. So one frame carrying a value near the top of the
 * range saturates the clock of every replica it reaches, and the next local edit overflows it.
 * On the server that ends in a refusal (`Id` rejects a negative lamport); here it ends in
 * arithmetic on numbers past 2^53, where `lamport + 1` stops being a distinct value and two
 * different characters start comparing equal — which is the one failure the identifier scheme
 * exists to prevent.
 *
 * 2^50 is the same bound the Java side uses, chosen to be unreachable by any real document and to
 * leave the clock room to keep counting.
 */
export const MAX_LAMPORT = 2 ** 50

function requireLamport(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > MAX_LAMPORT) {
    throw new Error(`lamport outside the accepted range [0, ${MAX_LAMPORT}]: ${String(value)}`)
  }
  return value
}

function requireReplicaId(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error(`replica identifier is not an integer: ${String(value)}`)
  }
  return value
}

function requireField<T>(value: T | undefined | null, field: string): T {
  if (value === undefined || value === null) {
    throw new Error(`operation is missing the field '${field}'`)
  }
  return value
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw new Error(`operation is missing the field '${field}': ${JSON.stringify(value)}`)
  }
  return value
}

/**
 * An anchor, refusing anything that is not `[replica, lamport, side]`.
 *
 * The cast this replaces — `decodeAnchor(wire.a as WireAnchor)` — was unsound: `a` is optional on
 * `WireOp`, so a truncated mark frame reached `decodeAnchor` as `undefined` and threw a
 * `TypeError` from inside it. Neither `parseOps` nor `onSyncResponse` wraps the loop, so one bad
 * frame took the remaining operations in that batch down with it. `OpCodec.readAnchor` has
 * validated this since it was written; this side had the same javadoc and not the same check.
 */
function decodeAnchor(wire: unknown): Anchor {
  if (!Array.isArray(wire) || wire.length !== 3) {
    throw new Error(`an anchor must be [replicaId, lamport, side]: ${JSON.stringify(wire)}`)
  }
  // The side is validated rather than compared with `=== 0`. A non-numeric third element made
  // this answer 'after' while `OpCodec.readAnchor`'s `asInt` coerced it to 0 and answered
  // BEFORE — the two implementations resolving the same anchor to opposite sides of a character,
  // which is the one thing the mark layer's anchors exist to pin down.
  const side = wire[2]
  if (side !== 0 && side !== 1) {
    throw new Error(`an anchor side must be 0 (before) or 1 (after): ${JSON.stringify(wire)}`)
  }
  return {
    charId: id(requireReplicaId(wire[0]), requireLamport(wire[1])),
    side: side === 0 ? 'before' : 'after',
  }
}

export function decodeOp(wire: WireOp): Op {
  const opId: Id = id(requireReplicaId(wire.r), requireLamport(wire.l))
  switch (wire.t) {
    case 'i': {
      // `!= null` rather than `!== undefined`, and that difference is the whole point: an explicit
      // JSON null used to decode to `id(null, null)`, an origin `locateId` can never match, so the
      // insert waited in the buffer for ever. `OpCodec.fromJson` uses `hasNonNull` for exactly
      // this, and its javadoc records that "the two implementations disagreed on a payload this
      // class sanctioned" — the Java half of that fix landed and this half did not.
      // Both halves or neither. `wire.or != null && wire.ol != null` quietly turned a frame with
      // an origin replica and NO lamport into "start of the document" — Java's `required(node,
      // "ol")` throws for it, so the two implementations did not merely differ on accept versus
      // reject, they differed on the MEANING: one refuses the frame, the other integrates the text
      // at the head of the document.
      const originLeft =
        wire.or == null
          ? null
: id(requireReplicaId(wire.or), requireLamport(requireField(wire.ol, 'ol')))
      const insert: InsertOp = {
        kind: 'insert',
        id: opId,
        originLeft,
        text: requireString(wire.s, 's'),
      }
      return insert
    }
    case 'd': {
      if (!Array.isArray(wire.g) || wire.g.length === 0) {
        // Empty as well as missing: `Op.Delete`'s compact constructor refuses a deletion that
        // targets nothing, so accepting one here would put an operation into the client's document
        // that the server would never have produced and cannot receive back.
        throw new Error(`operation is missing the field 'g': ${JSON.stringify(wire)}`)
      }
      const targets: Range[] = wire.g.map((entry) => {
        if (!Array.isArray(entry) || entry.length !== 3) {
          throw new Error(
            `a deletion range must be [replicaId, lamport, length]: ${JSON.stringify(entry)}`,
          )
        }
        // The LENGTH was never checked here, so `[2,3,0]`, `[2,3,-1]` and `[2,3,"x"]` were all
        // accepted verbatim while `Op.Range` refuses every one of them. A non-positive length makes
        // `isRangeFullyPresent` walk zero characters per step, which does not terminate.
        const length = entry[2]
        if (typeof length !== 'number' || !Number.isSafeInteger(length) || length <= 0) {
          throw new Error(
            `a deletion range must have a positive length: ${JSON.stringify(entry)}`,
          )
        }
        return { start: id(requireReplicaId(entry[0]), requireLamport(entry[1])), length }
      })
      const remove: DeleteOp = { kind: 'delete', id: opId, targets }
      return remove
    }
    case 'm': {
      const add: AddMarkOp = {
        kind: 'addMark',
        id: opId,
        type: requireString(wire.k, 'k'),
        // `== null`, matching `OpCodec.fromJson`'s `hasNonNull`: a MISSING value and an explicit
        // `"v":null` both mean "this mark carries no value". The bug this replaces was the
        // opposite reading — `wire.v ?? null` was already right here, while the ORIGIN two cases
        // up used `!== undefined` and so turned an explicit null into a fabricated 0@0 origin.
        // Both fields now ask the same question, which is the only way the two implementations
        // can be checked against each other at all.
        value: wire.v == null ? null : requireString(wire.v, 'v'),
        start: decodeAnchor(wire.a),
        end: decodeAnchor(wire.b),
      }
      return add
    }
    case 'x': {
      const drop: RemoveMarkOp = {
        kind: 'removeMark',
        id: opId,
        type: requireString(wire.k, 'k'),
        start: decodeAnchor(wire.a),
        end: decodeAnchor(wire.b),
      }
      return drop
    }
    default:
      throw new Error(`unknown operation type: ${JSON.stringify(wire)}`)
  }
}
