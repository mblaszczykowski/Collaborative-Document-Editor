import { describe, expect, it } from 'vitest'
import { decodeOp, encodeOp, MAX_LAMPORT, type WireOp } from './codec'
import { id, type Op } from './types'

describe('operation decoding refuses what the Java codec refuses', () => {
  const decode = (wire: unknown) => () => decodeOp(wire as WireOp)

  it('refuses an implausible Lamport timestamp', () => {
    expect(decode({ t: 'i', r: 5, l: Number.MAX_SAFE_INTEGER, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'i', r: 5, l: MAX_LAMPORT + 1, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'i', r: 5, l: -1, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'd', r: 1, l: 9, g: [[2, MAX_LAMPORT + 1, 1]] })).toThrow(/lamport/)
    expect(decode({ t: 'm', r: 1, l: 9, k: 'bold', a: [1, MAX_LAMPORT + 1, 0], b: [1, 3, 1] }))
      .toThrow(/lamport/)

    expect(decodeOp({ t: 'i', r: 5, l: 2 ** 40, s: 'x' } as WireOp).id.lamport).toBe(2 ** 40)
  })

  it('reads an explicit null origin the way Java does, instead of inventing one', () => {
    expect(decodeOp({ t: 'i', r: 1, l: 2, or: null, ol: null, s: 'x' } as unknown as WireOp))
      .toMatchObject({ originLeft: null })

    expect(decodeOp({ t: 'i', r: 1, l: 2, s: 'x' } as WireOp)).toMatchObject({ originLeft: null })
  })

  it('refuses a malformed anchor rather than throwing a TypeError from inside it', () => {
    for (const wire of [
      { t: 'm', r: 1, l: 9, k: 'bold', b: [1, 3, 1] },
      { t: 'm', r: 1, l: 9, k: 'bold', a: [1, 3], b: [1, 4, 1] },
      { t: 'm', r: 1, l: 9, k: 'bold', a: 'nonsense', b: [1, 4, 1] },
      { t: 'x', r: 1, l: 9, k: 'bold', a: [1, 3, 0] },
    ]) {
      expect(decode(wire)).toThrow(/anchor/)
    }
  })

  it('refuses a deletion range that is not [replica, lamport, length]', () => {
    for (const g of [[[2, 3]], [7], [{}], [[2, 3, 5, 7]]]) {
      expect(decode({ t: 'd', r: 1, l: 9, g })).toThrow(/deletion range/)
    }
    expect(decodeOp({ t: 'd', r: 1, l: 9, g: [[2, 3, 5]] } as WireOp)).toMatchObject({
      kind: 'delete',
    })
  })

  it('refuses an unknown operation type and a missing required field', () => {
    expect(decode({ t: 'q', r: 1, l: 9 })).toThrow(/unknown operation type/)
    expect(decode({ t: 'i', r: 1, l: 9 })).toThrow(/'s'/)
    expect(decode({ t: 'm', r: 1, l: 9, a: [1, 0, 0], b: [1, 1, 1] })).toThrow(/'k'/)
    expect(
      decodeOp({ t: 'm', r: 1, l: 9, k: 'bold', v: null, a: [1, 0, 0], b: [1, 1, 1] } as unknown as WireOp),
    ).toMatchObject({ value: null })
  })

  it('agrees with the Java codec on the payloads the two used to read differently', () => {
    expect(decode({ t: 'i', r: 1, l: 2, or: 2, s: 'x' })).toThrow(/'ol'/)
    expect(decode({ t: 'i', r: 1, l: 2, or: 2, ol: null, s: 'x' })).toThrow(/'ol'/)

    expect(decode({ t: 'd', r: 1, l: 9, g: [] })).toThrow(/'g'/)

    for (const bad of [0, -1, 'x', 1.5]) {
      expect(decode({ t: 'd', r: 1, l: 9, g: [[2, 3, bad]] })).toThrow(/positive length/)
    }

    for (const bad of ['0', 2, null, undefined]) {
      expect(decode({ t: 'm', r: 1, l: 9, k: 'bold', a: [1, 0, bad], b: [1, 3, 1] }))
        .toThrow(/anchor side/)
    }
  })
})

describe('the wire field order matches the Java encoder', () => {
  it('emits insert fields as r, l, t, or, ol, s', () => {
    const op: Op = {
      kind: 'insert',
      id: id(1, 7),
      originLeft: id(2, 3),
      text: 'abc',
    }
    expect(Object.keys(encodeOp(op))).toEqual(['r', 'l', 't', 'or', 'ol', 's'])
  })

  it('emits mark fields as r, l, t, k, v, a, b', () => {
    const op: Op = {
      kind: 'addMark',
      id: id(1, 11),
      type: 'link',
      value: 'https://example.test',
      start: { charId: id(2, 3), side: 'before' },
      end: { charId: id(2, 7), side: 'after' },
    }
    expect(Object.keys(encodeOp(op))).toEqual(['r', 'l', 't', 'k', 'v', 'a', 'b'])
  })

  it('omits the value field entirely when a mark carries none', () => {
    const op: Op = {
      kind: 'addMark',
      id: id(1, 11),
      type: 'bold',
      value: null,
      start: { charId: id(2, 3), side: 'before' },
      end: { charId: id(2, 7), side: 'after' },
    }
    expect(Object.keys(encodeOp(op))).toEqual(['r', 'l', 't', 'k', 'a', 'b'])
  })
})
