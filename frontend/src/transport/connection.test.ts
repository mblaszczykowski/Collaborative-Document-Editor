import { beforeEach, describe, expect, it, vi } from 'vitest'
import { encodeOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

/**
 * Two rules of the transport that the rest of the suite cannot reach, both with a permanent-state
 * failure behind them:
 *
 * 1. an operation leaves the unacknowledged buffer when the server says it is IN THE LOG — and an
 *    acknowledgement is not the only way it can say so;
 * 2. "have I got this operation" is a question about the OPERATION, not about the text it left
 *    behind, and the two answers differ for every delete and every mark.
 *
 * `connection.ts` needs a browser — `config.ts` reads `window.location` at module load and
 * `@stomp/stompjs` opens a socket — so those two are stubbed here rather than making a DOM
 * implementation a dependency of the unit suite for one file. What is NOT stubbed is the logic
 * under test.
 */

const forgotten: Op[] = []
let restorable: Op[] = []

vi.mock('./offlineStore', () => ({
  offlineStore: {
    pending: () => Promise.resolve(restorable),
    forget: (_documentId: string, op: Op) => {
      forgotten.push(op)
      return Promise.resolve()
    },
    remember: () => Promise.resolve(),
    clear: () => Promise.resolve(),
  },
}))

vi.stubGlobal('window', {
  location: {
    origin: 'http://localhost:5173',
    protocol: 'http:',
    hostname: 'localhost',
    port: '5173',
  },
  addEventListener: () => {},
  removeEventListener: () => {},
  setTimeout: () => 0,
  clearTimeout: () => {},
})

const { DocumentConnection } = await import('./connection')

/**
 * The STOMP subscription callbacks are the class's only entry point, and standing up a broker to
 * reach them would test the broker. The tests drive those callbacks directly, through this cast.
 */
interface Internals {
  onSyncResponse: (message: { body: string }) => void
  parseOps: (message: { body: string }) => Op[]
  onAcks: (message: { body: string }) => void
  resynchronize: () => void
  /** Stubbed by the resynchronisation test — the real one is a live STOMP client. */
  client: { connected: boolean; publish: (frame: unknown) => void } | null
  gapDetected: boolean
}

interface Harness {
  link: InstanceType<typeof DocumentConnection>
  inside: Internals
  pending: () => number
  state: () => string
}

/**
 * A connection with inert handlers.
 *
 * `holds` is what the CRDT would answer for a character identifier, and `applied` what it would
 * answer for a whole operation — the two are deliberately separate, because the gap between them
 * is what two of the tests below are about.
 */
function open(
  holds: (replicaId: number, lamport: number) => boolean = () => false,
  applied: (op: Op) => boolean = () => false,
): Harness {
  let pending = -1
  let state = 'never reported'
  const link = new DocumentConnection('doc-1', 'token', 0, {
    onSession: () => {},
    hasOperation: holds,
    hasApplied: applied,
    onRemoteOps: () => {},
    versionVector: () => new Map(),
    onPresence: () => {},
    onStateChange: (next, count) => {
      pending = count
      state = next
    },
    onError: () => {},
    onAccessChanged: () => {},
  })
  return {
    link,
    inside: link as unknown as Internals,
    pending: () => pending,
    state: () => state,
  }
}

/** A sync response as the server writes it — see `Messages.SyncResponse`. */
function syncResponse(ops: Op[], headServerSeq: number, last = true) {
  return { body: JSON.stringify({ ops: ops.map(encodeOp), headServerSeq, last }) }
}

/** A broadcast frame as the server writes it — see `DocumentStompController.broadcast`. */
function broadcast(ops: Op[]) {
  return { body: JSON.stringify(ops.map((op) => ({ op: encodeOp(op) }))) }
}

function acks(entries: { r: number; l: number; serverSeq: number }[]) {
  return { body: JSON.stringify(entries) }
}

const HELLO: Op = { kind: 'insert', id: { replicaId: 7, lamport: 3 }, originLeft: null, text: 'hi' }

beforeEach(() => {
  forgotten.length = 0
  restorable = []
})

describe('the unacknowledged buffer', () => {
  it('releases an operation that comes back in a sync response', async () => {
    restorable = [HELLO]
    const { link, inside, pending } = open(() => true)

    await link.restoreOfflineBuffer()
    expect(pending()).toBe(1)

    // The acknowledgement for this operation was broadcast while the tab was disconnected, so it
    // was never heard. The retransmission after reconnect is correctly refused as a duplicate —
    // `OperationStore.append` filters duplicates BEFORE assigning sequence numbers, so no second
    // acknowledgement is ever produced for it. Without this rule the operation stayed in the
    // buffer for the rest of the session: counted as pending for ever, resent on every reconnect,
    // and kept in the offline store so that every future page load restored it again.
    inside.onSyncResponse(syncResponse([HELLO], 12))

    expect(pending()).toBe(0)
    expect(forgotten).toEqual([HELLO])
  })

  it('releases an operation the server had already, before connecting', async () => {
    restorable = [HELLO]
    // The replica was rebuilt from a snapshot that already contains this operation — so it is in
    // the log, and the acknowledgement is simply one the tab was not around to hear. The resync
    // cannot help: it asks for operations after the snapshot's cursor, which is already past it.
    const { link, pending } = open(() => true, () => true)

    await link.restoreOfflineBuffer()

    expect(pending()).toBe(0)
    expect(forgotten).toEqual([HELLO])
  })

  it("leaves somebody else's operations alone", async () => {
    const theirs: Op = {
      kind: 'insert',
      id: { replicaId: 9, lamport: 4 },
      originLeft: null,
      text: 'theirs',
    }
    restorable = [HELLO]
    const { link, inside, pending } = open(() => true)

    await link.restoreOfflineBuffer()
    inside.onSyncResponse(syncResponse([theirs], 12))

    expect(pending()).toBe(1)
    expect(forgotten).toEqual([])
  })
})

describe('the resynchronisation state', () => {
  /**
   * What the user is told while a backlog is still arriving.
   *
   * `resynchronize()` used to set 'resyncing' and then 'online' two statements later, in the same
   * synchronous handler. React batches inside a handler, so only 'online' was ever rendered — and
   * `onSyncResponse`'s own transition, the one written with a comment about waiting for the final
   * chunk, was dead code on that path. `SaveIndicator` therefore showed **"Saved"** while chunks
   * were still on their way: after a reconnect with a backlog, or on first opening a busy
   * document. The indicator is the only thing in this interface that tells somebody whether their
   * work is safe, so a false "Saved" is the one lie it must never tell.
   */
  it('stays resyncing until the last chunk of the answer arrives', () => {
    const { inside, state } = open()
    // The publish target only has to accept frames; what is under test is the state machine, and
    // standing up a broker to reach it would be testing the broker.
    inside.client = { connected: true, publish: () => {} }

    inside.resynchronize()
    expect(state()).toBe('resyncing')

    // CP28 splits a large answer across several frames, every one carrying the same head.
    inside.onSyncResponse(syncResponse([], 120, false))
    expect(state()).toBe('resyncing')

    inside.onSyncResponse(syncResponse([], 120, false))
    expect(state()).toBe('resyncing')

    inside.onSyncResponse(syncResponse([], 120, true))
    expect(state()).toBe('online')
  })

  it('treats a missing `last` flag as the whole answer', () => {
    const { inside, state } = open()
    inside.client = { connected: true, publish: () => {} }

    inside.resynchronize()
    // An older server does not send the field at all, and the safe reading of a missing flag is
    // the behaviour that was correct when an answer was always a single frame. Without this, a
    // client talking to a server from before CP28 would sit on "Connecting…" for ever.
    inside.onSyncResponse({ body: JSON.stringify({ ops: [], headServerSeq: 9 }) })

    expect(state()).toBe('online')
  })
})

describe('gap detection', () => {
  /**
   * A delete carries its own identity, drawn from the same Lamport clock as the characters but
   * naming none of them — so `RgaDocument.contains` is false for it however perfectly the client
   * has applied it. That is the whole bug: `holds` below returns false, exactly as the real
   * document does.
   */
  const REMOTE_DELETE: Op = {
    kind: 'delete',
    id: { replicaId: 9, lamport: 12 },
    targets: [{ start: { replicaId: 9, lamport: 3 }, length: 1 }],
  }

  it('does not flag a gap for a delete the client has received', () => {
    const { inside } = open()

    inside.parseOps(broadcast([REMOTE_DELETE]))
    inside.onAcks(acks([{ r: 9, l: 12, serverSeq: 41 }]))

    // Acknowledgements go to a shared topic, so this fired for a backspace by ANYBODY. `gapDetected`
    // makes `reportProgress` ask for a full resynchronisation FROM ZERO at the next 15 s tick —
    // the entire operation log, to every client, every fifteen seconds, for as long as anyone is
    // editing. The repair mechanism was its own permanent load.
    expect(inside.gapDetected).toBe(false)
  })

  it('does not flag a gap for the client\'s own delete', () => {
    const { link, inside } = open()

    link.send(REMOTE_DELETE)
    inside.onAcks(acks([{ r: 9, l: 12, serverSeq: 41 }]))

    expect(inside.gapDetected).toBe(false)
  })

  it('still flags a gap for an operation that never arrived', () => {
    const { inside } = open()

    // Nothing received, and the structural check says no — which for a lost broadcast is the
    // truth. This is the case the mechanism exists for (found by the CP8 load harness: at 25+
    // clients some clients were permanently behind with the server holding the complete set).
    inside.onAcks(acks([{ r: 9, l: 12, serverSeq: 41 }]))

    expect(inside.gapDetected).toBe(true)
  })

  it('still flags a gap for an insert whose characters are absent', () => {
    const { inside } = open((replicaId) => replicaId === 1)

    inside.onAcks(acks([{ r: 4, l: 8, serverSeq: 42 }]))

    expect(inside.gapDetected).toBe(true)
  })
})
