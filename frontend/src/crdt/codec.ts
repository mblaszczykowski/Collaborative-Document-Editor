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
      const wire: WireOp = { r: op.id.replicaId, l: op.id.lamport, t: 'm', k: op.type }
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

function decodeAnchor(wire: unknown): Anchor {
  if (!Array.isArray(wire) || wire.length !== 3) {
    throw new Error(`an anchor must be [replicaId, lamport, side]: ${JSON.stringify(wire)}`)
  }
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
        throw new Error(`operation is missing the field 'g': ${JSON.stringify(wire)}`)
      }
      const targets: Range[] = wire.g.map((entry) => {
        if (!Array.isArray(entry) || entry.length !== 3) {
          throw new Error(
            `a deletion range must be [replicaId, lamport, length]: ${JSON.stringify(entry)}`,
          )
        }
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
