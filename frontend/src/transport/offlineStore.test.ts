import { beforeEach, describe, expect, it } from 'vitest'
import type { Op } from '../crdt/types'

interface Recorder {
  opens: number
  transactions: number
  abortNext: boolean
}

const recorder: Recorder = { opens: 0, transactions: 0, abortNext: false }

let rows: Map<string, Map<string, Record<string, unknown>>>

interface StubRequest {
  result: unknown
  error: unknown
  onsuccess: (() => void) | null
  onerror: (() => void) | null
  onblocked?: (() => void) | null
  onupgradeneeded?: (() => void) | null
}

function store(name: string): Map<string, Record<string, unknown>> {
  let found = rows.get(name)
  if (found === undefined) {
    found = new Map()
    rows.set(name, found)
  }
  return found
}

const KEY_PATHS: Record<string, string> = {
  unacknowledged: 'key',
  comments: 'clientId',
  undo: 'documentId',
}

function makeTransaction() {
  recorder.transactions++
  let inFlight = 0
  let settled = false

  const transaction: {
    error: unknown
    oncomplete: (() => void) | null
    onabort: (() => void) | null
    onerror: (() => void) | null
    objectStore: (name: string) => ReturnType<typeof objectStoreFor>
  } = {
    error: new Error('quota exceeded'),
    oncomplete: null,
    onabort: null,
    onerror: null,
    objectStore: (name: string) => objectStoreFor(name),
  }

  const maybeCommit = () => {
    queueMicrotask(() => {
      if (settled || inFlight > 0) return
      settled = true
      if (recorder.abortNext) transaction.onabort?.()
      else transaction.oncomplete?.()
    })
  }

  const schedule = (request: StubRequest, run: () => void) => {
    inFlight++
    queueMicrotask(() => {
      if (!recorder.abortNext) run()
      inFlight--
      maybeCommit()
    })
    return request
  }

  const issue = (compute: () => unknown): StubRequest => {
    const request: StubRequest = { result: undefined, error: null, onsuccess: null, onerror: null }
    return schedule(request, () => {
      request.result = compute()
      request.onsuccess?.()
    })
  }

  function objectStoreFor(name: string) {
    const keyPath = KEY_PATHS[name] ?? 'key'

    const openCursor = (documentId: string) => {
      const matching = [...store(name).entries()]
        .filter(([, value]) => value.documentId === documentId)
        .map(([key]) => key)
      let index = 0
      const request: StubRequest = { result: null, error: null, onsuccess: null, onerror: null }
      const step = () => {
        schedule(request, () => {
          if (index >= matching.length) {
            request.result = null
            request.onsuccess?.()
            return
          }
          const key = matching[index++]
          request.result = { delete: () => store(name).delete(key), continue: step }
          request.onsuccess?.()
        })
      }
      step()
      return request
    }

    return {
      put: (value: Record<string, unknown>) =>
        issue(() => store(name).set(String(value[keyPath]), value)),
      delete: (key: string) => issue(() => store(name).delete(key)),
      get: (key: string) => issue(() => store(name).get(key)),
      index: () => ({
        getAll: (documentId: string) =>
          issue(() => [...store(name).values()].filter((v) => v.documentId === documentId)),
        openCursor: (range: { only: string }) => openCursor(range.only),
      }),
    }
  }

  maybeCommit()
  return transaction
}

function install(): void {
  rows = new Map()
  Object.assign(recorder, { opens: 0, transactions: 0, abortNext: false })

  const database = {
    objectStoreNames: { contains: () => true },
    onclose: null,
    onversionchange: null,
    close: () => {},
    transaction: () => makeTransaction(),
  }

  Object.defineProperty(globalThis, 'indexedDB', {
    configurable: true,
    value: {
      open: () => {
        recorder.opens++
        const request: StubRequest = {
          result: database,
          error: null,
          onsuccess: null,
          onerror: null,
          onblocked: null,
          onupgradeneeded: null,
        }
        queueMicrotask(() => request.onsuccess?.())
        return request
      },
    },
  })
  Object.defineProperty(globalThis, 'IDBKeyRange', {
    configurable: true,
    value: { only: (value: string) => ({ only: value }) },
  })
}

install()
const { offlineStore } = await import('./offlineStore')

function op(lamport: number): Op {
  return { kind: 'insert', id: { replicaId: 1, lamport }, originLeft: null, text: 'x' }
}

beforeEach(() => {
  rows = new Map()
  recorder.transactions = 0
  recorder.abortNext = false
})

describe('the shared connection', () => {
  it('opens the database once for a run of writes', async () => {
    const before = recorder.opens
    for (let lamport = 0; lamport < 20; lamport++) {
      await offlineStore.remember('doc-1', op(lamport))
    }

    expect(recorder.opens - before).toBeLessThanOrEqual(1)
    expect(await offlineStore.pending('doc-1')).toHaveLength(20)
  })
})

describe('clearing a document buffer', () => {
  it('sweeps the whole buffer in one transaction', async () => {
    for (let lamport = 0; lamport < 25; lamport++) {
      await offlineStore.remember('doc-1', op(lamport))
    }
    await offlineStore.remember('doc-2', op(99))

    recorder.transactions = 0
    await offlineStore.clear('doc-1')

    expect(recorder.transactions).toBe(1)
    expect(await offlineStore.pending('doc-1')).toEqual([])
    expect(await offlineStore.pending('doc-2')).toHaveLength(1)
  })
})

describe('a transaction that aborts', () => {
  it('rejects instead of hanging for ever', async () => {
    recorder.abortNext = true

    await expect(offlineStore.remember('doc-1', op(1))).rejects.toThrow()
  })
})

describe('account isolation', () => {
  it('never loads or clears another user’s pending changes or undo', async () => {
    await offlineStore.remember('shared', op(1), 'anna')
    await offlineStore.remember('shared', op(2), 'marek')
    await offlineStore.saveUndo('shared', { undo: ['anna'] }, 'anna')
    expect(await offlineStore.pending('shared', 'marek')).toEqual([op(2)])
    expect(await offlineStore.loadUndo('shared', 'marek')).toBeNull()
    await offlineStore.clear('shared', 'marek')
    expect(await offlineStore.pending('shared', 'anna')).toEqual([op(1)])
  })

  it('isolates queued comments even when both users can access a document', async () => {
    await offlineStore.queueComment({ userId: 'anna', clientId: 'anna-1', documentId: 'shared',
      kind: 'thread', body: 'private draft', queuedAt: 1 })
    expect(await offlineStore.pendingComments('shared', 'marek')).toEqual([])
    expect(await offlineStore.pendingComments('shared', 'anna')).toHaveLength(1)
  })
})
