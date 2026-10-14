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
  reportProgress: () => void
  handleBrowserOffline: () => void
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
  remote: (ops: Op[]) => void = () => {},
  persistApplied?: () => Promise<void>,
): Harness {
  let pending = -1
  let state = 'never reported'
  const link = new DocumentConnection('doc-1', 'token', 0, {
    onSession: () => {},
    hasOperation: holds,
    hasApplied: applied,
    onRemoteOps: remote,
    persistApplied,
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

describe('replay and acknowledgement retry', () => {
  it('reapplies durable unsent operations to the local document before connecting', async () => {
    restorable = [HELLO]
    const apply = vi.fn()
    const harness = open(() => false, () => false, apply)
    await harness.link.restoreOfflineBuffer()
    expect(apply).toHaveBeenCalledWith([HELLO])
    expect(harness.link.pendingCount).toBe(1)
  })

  it('retries an unacknowledged dropped operation without reconnecting', async () => {
    const harness = open()
    const publish = vi.fn()
    harness.inside.client = { connected: true, publish }
    harness.link.simulator.update({ packetLoss: 1 })
    harness.link.send(HELLO)
    await Promise.resolve()
    await Promise.resolve()
    expect(publish).not.toHaveBeenCalled()
    harness.link.simulator.update({ packetLoss: 0 })
    const now = Date.now()
    const clock = vi.spyOn(Date, 'now').mockReturnValue(now + 20_000)
    harness.inside.reportProgress()
    clock.mockRestore()
    expect(publish.mock.calls.some(([frame]) => (frame as { destination: string }).destination.endsWith('/op'))).toBe(true)
  })

  it('does not transmit a held partition operation after its buffer was abandoned', async () => {
    const harness = open()
    const publish = vi.fn()
    harness.inside.client = { connected: true, publish }
    harness.link.simulator.update({ partitioned: true })
    harness.link.send(HELLO)
    await Promise.resolve()
    await Promise.resolve()
    await harness.link.abandonBuffer()
    harness.link.simulator.update({ partitioned: false })
    expect(publish).not.toHaveBeenCalled()
  })
})

it('completes an anchor receipt only after its operation is durably acknowledged', async () => {
  const harness = open()
  harness.link.send(HELLO)
  const complete = vi.fn()
  const receipt = harness.link.waitForDurable(HELLO).then(complete)
  await Promise.resolve()
  expect(complete).not.toHaveBeenCalled()
  harness.inside.onAcks(acks([{ r: HELLO.id.replicaId, l: HELLO.id.lamport, serverSeq: 1 }]))
  await receipt
  expect(complete).toHaveBeenCalledTimes(1)
})

it('rejects an anchor receipt on disconnect so its comment stays queued for retry', async () => {
  const harness = open()
  harness.link.send(HELLO)
  const receipt = harness.link.waitForDurable(HELLO)
  harness.link.disconnect()
  await expect(receipt).rejects.toThrow('before saving')
})

it('retains acknowledged changes until their canonical cache transaction completes', async () => {
  let cached!: () => void
  const cache = new Promise<void>(resolve => { cached = resolve })
  const harness = open(() => false, () => false, () => {}, () => cache)
  harness.link.send(HELLO)
  const complete = vi.fn()
  const receipt = harness.link.waitForDurable(HELLO).then(complete)
  harness.inside.onAcks(acks([{ r: HELLO.id.replicaId, l: HELLO.id.lamport, serverSeq: 1 }]))
  expect(harness.link.pendingCount).toBe(1)
  expect(forgotten).toEqual([])
  expect(complete).not.toHaveBeenCalled()
  cached()
  await receipt
  expect(harness.link.pendingCount).toBe(0)
  expect(forgotten).toEqual([HELLO])
})

it('keeps sync-echoed operations recoverable while the canonical cache is being written', async () => {
  let cached!: () => void
  const cache = new Promise<void>(resolve => { cached = resolve })
  const harness = open(() => false, () => false, () => {}, () => cache)
  harness.link.send(HELLO)
  harness.inside.onSyncResponse(syncResponse([HELLO], 1))
  expect(harness.link.pendingCount).toBe(1)
  expect(forgotten).toEqual([])
  cached()
  await Promise.resolve()
  await Promise.resolve()
  expect(harness.link.pendingCount).toBe(0)
  expect(forgotten).toEqual([HELLO])
})


it('a delayed cache commit cannot mark the document online after the browser goes offline', async () => {
  let cached!: () => void
  const cache = new Promise<void>(resolve => { cached = resolve })
  const harness = open(() => false, () => false, () => {}, () => cache)
  harness.inside.onSyncResponse(syncResponse([], 1))
  harness.inside.handleBrowserOffline()
  cached()
  await Promise.resolve()
  await Promise.resolve()
  expect(harness.state()).toBe('offline')
})
