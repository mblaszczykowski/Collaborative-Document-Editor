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
 * The field names are single letters deliberately (ADR-0002): for an operation carrying one
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

function decodeAnchor(wire: WireAnchor): Anchor {
  return { charId: id(wire[0], wire[1]), side: wire[2] === 0 ? 'before' : 'after' }
}

export function encodeOp(op: Op): WireOp {
  switch (op.kind) {
    case 'insert': {
      const wire: WireOp = { r: op.id.replicaId, l: op.id.lamport, t: 'i', s: op.text }
      if (op.originLeft !== null) {
        wire.or = op.originLeft.replicaId
        wire.ol = op.originLeft.lamport
      }
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
      const wire: WireOp = {
        r: op.id.replicaId,
        l: op.id.lamport,
        t: 'm',
        k: op.type,
        a: encodeAnchor(op.start),
        b: encodeAnchor(op.end),
      }
      // The value field is OMITTED when unused — it has to match the Java version, which omits
      // it too. Sending `"v":null` would give different bytes on the wire for the same operation,
      // and the conformance vectors compare strings.
      if (op.value !== null) {
        wire.v = op.value
      }
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

export function decodeOp(wire: WireOp): Op {
  const opId: Id = id(wire.r, wire.l)
  switch (wire.t) {
    case 'i': {
      const originLeft = wire.or !== undefined && wire.ol !== undefined ? id(wire.or, wire.ol) : null
      const insert: InsertOp = { kind: 'insert', id: opId, originLeft, text: wire.s ?? '' }
      return insert
    }
    case 'd': {
      const targets: Range[] = (wire.g ?? []).map(([replicaId, lamport, length]) => ({
        start: id(replicaId, lamport),
        length,
      }))
      const remove: DeleteOp = { kind: 'delete', id: opId, targets }
      return remove
    }
    case 'm': {
      const add: AddMarkOp = {
        kind: 'addMark',
        id: opId,
        type: wire.k ?? '',
        value: wire.v ?? null,
        start: decodeAnchor(wire.a as WireAnchor),
        end: decodeAnchor(wire.b as WireAnchor),
      }
      return add
    }
    case 'x': {
      const drop: RemoveMarkOp = {
        kind: 'removeMark',
        id: opId,
        type: wire.k ?? '',
        start: decodeAnchor(wire.a as WireAnchor),
        end: decodeAnchor(wire.b as WireAnchor),
      }
      return drop
    }
  }
}
