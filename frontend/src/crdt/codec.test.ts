import { describe, expect, it } from 'vitest'
import { decodeOp, encodeOp, MAX_LAMPORT, type WireOp } from './codec'
import { id, type Op } from './types'

/**
 * The refusals `OpCodec.java` makes, made here too.
 *
 * The conformance corpus proves the two implementations agree on payloads that are VALID. Nothing
 * covered what each does with a payload that is not, and the two answers had drifted apart:
 * `OpCodec` treats an explicit JSON null for an origin as no origin at all, refuses a malformed
 * anchor, and bounds the Lamport timestamp; this side fabricated an origin of 0@0 for the first,
 * cast its way past the second, and did not know about the third. Each is a divergence the corpus
 * is structurally unable to see, which is the argument for testing the boundary separately rather
 * than adding more vectors.
 */
describe('operation decoding refuses what the Java codec refuses', () => {
  const decode = (wire: unknown) => () => decodeOp(wire as WireOp)

  it('refuses an implausible Lamport timestamp', () => {
    // The failure this prevents is not local. A replica advances its clock past every timestamp it
    // RECEIVES, before deciding whether the operation is applicable — so one frame near the top of
    // the range saturates the clock of every replica it reaches, and the next local edit overflows
    // it. Past 2^53 in JavaScript, `lamport + 1` stops being a distinct value and two different
    // characters begin comparing equal.
    expect(decode({ t: 'i', r: 5, l: Number.MAX_SAFE_INTEGER, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'i', r: 5, l: MAX_LAMPORT + 1, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'i', r: 5, l: -1, s: 'x' })).toThrow(/lamport/)
    expect(decode({ t: 'd', r: 1, l: 9, g: [[2, MAX_LAMPORT + 1, 1]] })).toThrow(/lamport/)
    expect(decode({ t: 'm', r: 1, l: 9, k: 'bold', a: [1, MAX_LAMPORT + 1, 0], b: [1, 3, 1] }))
      .toThrow(/lamport/)

    // Generous rather than tight: a document edited at a thousand operations a second would need
    // about thirty-five thousand years to reach the bound, so nothing real is refused by it.
    expect(decodeOp({ t: 'i', r: 5, l: 2 ** 40, s: 'x' } as WireOp).id.lamport).toBe(2 ** 40)
  })

  it('reads an explicit null origin the way Java does, instead of inventing one', () => {
    // `wire.or !== undefined` was TRUE for an explicit null, so `{"or":null,"ol":null}` decoded to
    // an origin of 0@0 — an identifier `locateId` can never match, so the insert waited in the
    // buffer for ever. `OpCodec.fromJson` asks `hasNonNull`, which treats a null field exactly
    // like a missing one: this is the start of the document. Its javadoc records that the two
    // implementations disagreed on a payload it sanctioned; the Java half of that fix landed and
    // this half did not.
    expect(decodeOp({ t: 'i', r: 1, l: 2, or: null, ol: null, s: 'x' } as unknown as WireOp))
      .toMatchObject({ originLeft: null })

    // A missing origin is the same case and stays legitimate.
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
    // An explicit null VALUE is not an error on either side — `hasNonNull` reads it as "this mark
    // carries no value", and so does this decoder. What matters is that both give the same answer.
    expect(
      decodeOp({ t: 'm', r: 1, l: 9, k: 'bold', v: null, a: [1, 0, 0], b: [1, 1, 1] } as unknown as WireOp),
    ).toMatchObject({ value: null })
  })

  /**
   * The payloads where the two implementations answered differently — found by feeding both the
   * same inputs rather than by reading either.
   *
   * The first is the one that mattered: it is not accept-versus-reject but a difference in
   * MEANING. `OpCodec` refuses an origin replica with no lamport; this side read it as "no origin
   * at all" and integrated the text at the head of the document.
   */
  it('agrees with the Java codec on the payloads the two used to read differently', () => {
    // `or` without `ol` — Java's `required(node, "ol")` throws.
    expect(decode({ t: 'i', r: 1, l: 2, or: 2, s: 'x' })).toThrow(/'ol'/)
    expect(decode({ t: 'i', r: 1, l: 2, or: 2, ol: null, s: 'x' })).toThrow(/'ol'/)

    // An empty target list — `Op.Delete`'s compact constructor refuses it.
    expect(decode({ t: 'd', r: 1, l: 9, g: [] })).toThrow(/'g'/)

    // A non-positive or non-numeric range length — `Op.Range` refuses all three, and a zero-length
    // range makes the presence walk step nowhere.
    for (const bad of [0, -1, 'x', 1.5]) {
      expect(decode({ t: 'd', r: 1, l: 9, g: [[2, 3, bad]] })).toThrow(/positive length/)
    }

    // An anchor side that is not 0 or 1. `asInt()` coerced `"0"` to BEFORE on the Java side while
    // `=== 0` answered 'after' here — the same anchor resolving to opposite sides of a character.
    for (const bad of ['0', 2, null, undefined]) {
      expect(decode({ t: 'm', r: 1, l: 9, k: 'bold', a: [1, 0, bad], b: [1, 3, 1] }))
        .toThrow(/anchor side/)
    }
  })
})

describe('the wire field order matches the Java encoder', () => {
  /**
   * The conformance vectors compare PARSED objects, so key order is invisible to them — and the
   * two encoders had drifted to `r,l,t,s,or,ol` here against `r,l,t,or,ol,s` in Java. Harmless
   * while both sides parse JSON, and it stops being harmless the moment anything hashes or signs a
   * frame. This is the assertion the corpus structurally cannot make.
   */
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
