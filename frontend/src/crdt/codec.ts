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
 * Format przewodowy operacji — musi być IDENTYCZNY z `OpCodec` w Javie.
 *
 * Nazwy pól są jednoliterowe celowo (ADR-0002): przy operacji niosącej jeden znak
 * `"originLeft"` kosztuje w ramce więcej niż sama treść.
 *
 *   wstawienie: {"t":"i","r":1,"l":7,"or":2,"ol":3,"s":"abc"}
 *   usunięcie:  {"t":"d","r":1,"l":9,"g":[[2,3,5]]}
 *   znacznik+:  {"t":"m","r":1,"l":11,"k":"bold","v":"…","a":[2,3,0],"b":[2,7,1]}
 *   znacznik−:  {"t":"x","r":1,"l":13,"k":"bold","a":[2,3,0],"b":[2,7,1]}
 *
 * Kotwica jest zapisana jako `[replika, lamport, strona]`, gdzie strona 0 = przed, 1 = za.
 * Trójelementowa tablica zamiast obiektu z nazwami pól z tego samego powodu, dla którego
 * pola są jednoliterowe: operacji znacznikowych jest dużo, a nazwy pól powtarzałyby się
 * w każdej z nich.
 *
 * Ten plik i `OpCodec.java` są parą, którą trzeba zmieniać razem. Wektory konformancyjne
 * są zapisane w tym formacie, więc rozbieżność wychodzi natychmiast w testach — to jest
 * jedyny powód, dla którego dwie ręcznie utrzymywane implementacje formatu są do obrony.
 */

/** Kotwica na drucie: [replika, lamport, strona] — strona 0 = przed, 1 = za. */
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
      // Pole wartości POMIJANE, gdy nieużywane — musi zgadzać się z wersją w Javie,
      // która też je pomija. Wysłanie `"v":null` dałoby inny bajt na drucie przy tej samej
      // operacji, a wektory konformancyjne porównują ciąg znaków.
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
