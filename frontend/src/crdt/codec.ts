import { id, type DeleteOp, type Id, type InsertOp, type Op, type Range } from './types'

/**
 * Format przewodowy operacji — musi być IDENTYCZNY z `OpCodec` w Javie.
 *
 * Nazwy pól są jednoliterowe celowo (ADR-0002): przy operacji niosącej jeden znak
 * `"originLeft"` kosztuje w ramce więcej niż sama treść.
 *
 *   wstawienie: {"t":"i","r":1,"l":7,"or":2,"ol":3,"s":"abc"}
 *   usunięcie:  {"t":"d","r":1,"l":9,"g":[[2,3,5]]}
 *
 * Ten plik i `OpCodec.java` są parą, którą trzeba zmieniać razem. Wektory konformancyjne
 * są zapisane w tym formacie, więc rozbieżność wychodzi natychmiast w testach — to jest
 * jedyny powód, dla którego dwie ręcznie utrzymywane implementacje formatu są do obrony.
 */

export interface WireOp {
  t: 'i' | 'd'
  r: number
  l: number
  or?: number
  ol?: number
  s?: string
  g?: [number, number, number][]
}

export function encodeOp(op: Op): WireOp {
  if (op.kind === 'insert') {
    const wire: WireOp = { r: op.id.replicaId, l: op.id.lamport, t: 'i', s: op.text }
    if (op.originLeft !== null) {
      wire.or = op.originLeft.replicaId
      wire.ol = op.originLeft.lamport
    }
    return wire
  }
  return {
    r: op.id.replicaId,
    l: op.id.lamport,
    t: 'd',
    g: op.targets.map((range) => [range.start.replicaId, range.start.lamport, range.length]),
  }
}

export function decodeOp(wire: WireOp): Op {
  const opId: Id = id(wire.r, wire.l)
  if (wire.t === 'i') {
    const originLeft = wire.or !== undefined && wire.ol !== undefined ? id(wire.or, wire.ol) : null
    const insert: InsertOp = { kind: 'insert', id: opId, originLeft, text: wire.s ?? '' }
    return insert
  }
  const targets: Range[] = (wire.g ?? []).map(([replicaId, lamport, length]) => ({
    start: id(replicaId, lamport),
    length,
  }))
  const remove: DeleteOp = { kind: 'delete', id: opId, targets }
  return remove
}
