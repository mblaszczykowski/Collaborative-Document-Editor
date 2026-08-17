import { beforeEach, describe, expect, it, vi } from 'vitest'
import { encodeOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

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

interface Internals {
  onSyncResponse: (message: { body: string }) => void
  parseOps: (message: { body: string }) => Op[]
  onAcks: (message: { body: string }) => void
  resynchronize: () => void
  client: { connected: boolean; publish: (frame: unknown) => void } | null
  gapDetected: boolean
}

interface Harness {
  link: InstanceType<typeof DocumentConnection>
  inside: Internals
  pending: () => number
  state: () => string
}

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

function syncResponse(ops: Op[], headServerSeq: number, last = true) {
  return { body: JSON.stringify({ ops: ops.map(encodeOp), headServerSeq, last }) }
}

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

    inside.onSyncResponse(syncResponse([HELLO], 12))

    expect(pending()).toBe(0)
    expect(forgotten).toEqual([HELLO])
  })

  it('releases an operation the server had already, before connecting', async () => {
    restorable = [HELLO]
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
  it('stays resyncing until the last chunk of the answer arrives', () => {
    const { inside, state } = open()
    inside.client = { connected: true, publish: () => {} }

    inside.resynchronize()
    expect(state()).toBe('resyncing')

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
    inside.onSyncResponse({ body: JSON.stringify({ ops: [], headServerSeq: 9 }) })

    expect(state()).toBe('online')
  })
})

describe('gap detection', () => {
  const REMOTE_DELETE: Op = {
    kind: 'delete',
    id: { replicaId: 9, lamport: 12 },
    targets: [{ start: { replicaId: 9, lamport: 3 }, length: 1 }],
  }

  it('does not flag a gap for a delete the client has received', () => {
    const { inside } = open()

    inside.parseOps(broadcast([REMOTE_DELETE]))
    inside.onAcks(acks([{ r: 9, l: 12, serverSeq: 41 }]))

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

    inside.onAcks(acks([{ r: 9, l: 12, serverSeq: 41 }]))

    expect(inside.gapDetected).toBe(true)
  })

  it('still flags a gap for an insert whose characters are absent', () => {
    const { inside } = open((replicaId) => replicaId === 1)

    inside.onAcks(acks([{ r: 4, l: 8, serverSeq: 42 }]))

    expect(inside.gapDetected).toBe(true)
  })
})
