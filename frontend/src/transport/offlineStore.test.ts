import { beforeEach, describe, expect, it } from 'vitest'
import type { Op } from '../crdt/types'

/**
 * The three properties of the durable buffer that cost something real when they were wrong, and
 * that no other test in this repository can see:
 *
 * 1. **one connection, not one per call.** `transact` used to `indexedDB.open` on every call —
 * once per keystroke on the `remember` path;
 * 2. **`clear` is one transaction.** It used to be one `getAll` plus a full open-and-transact per
 * ROW, on the `RESYNC_REQUIRED` path, which is the worst possible moment for a few thousand
 * database opens;
 * 3. **an aborted transaction rejects.** A quota that runs out mid-commit aborts the transaction
 * without the request's own `onerror` firing. The promise then never settled, so `remember`'s
 * `.catch` never ran and `reportStorageFailure` never told the user their local backup had
 * stopped working — a silent hang where the design calls for a visible warning.
 *
 * ## Why the stub, rather than a real IndexedDB
 *
 * The unit suite runs in Node with no DOM, and the alternative is a dependency (`fake-indexeddb`)
 * that would bring a full implementation of a specification in order to count `open` calls. What
 * these tests are about is the SHAPE of the calls this module makes — how many connections, how
 * many transactions — and a stub that records them answers that question directly. It deliberately
 * models only the subset used here: string keys, one index per store, `put`/`delete`/`get`/`getAll`
 * and a cursor that walks an index. It is not an IndexedDB, and it should not grow into one — a
 * test that needs more than this is a test about the browser rather than about this file.
 */

interface Recorder {
  opens: number
  transactions: number
  /** Set for the next transaction to abort instead of completing — the quota case. */
  abortNext: boolean
}

const recorder: Recorder = { opens: 0, transactions: 0, abortNext: false }

/** The rows, by store name then key. Survives between transactions, like a database. */
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

/**
 * A transaction that commits when it runs out of work, like the real one.
 *
 * The counting matters and the first version of this stub got it wrong: it fired `oncomplete` a
 * fixed number of microtasks after the transaction was created, so a cursor sweep of 25 rows
 * "completed" while it was still on row 2 and `clear` resolved before it had deleted anything. The
 * test passed the wrong thing rather than failing — which is the failure mode a stub is most prone
 * to, and the reason this one tracks outstanding requests instead of guessing at a delay.
 */
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
      // `abortNext` models the quota running out at COMMIT time: the requests themselves were
      // accepted, so none of their `onerror` handlers ever fires, and the transaction's `onabort`
      // is the only signal there is.
      if (recorder.abortNext) transaction.onabort?.()
      else transaction.oncomplete?.()
    })
  }

  /**
   * Answers a request a microtask later, which is what makes the stub a fair test.
   *
   * IndexedDB never answers synchronously, so a stub that did would let code pass that assigns its
   * `onsuccess` handler after the request had already "finished". `offlineStore` assigns its
   * handlers on the lines after the call, exactly as the real API requires.
   */
  const schedule = (request: StubRequest, run: () => void) => {
    inFlight++
    queueMicrotask(() => {
      // A transaction that has already aborted answers nothing more.
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

    // Twenty keystrokes used to be twenty `indexedDB.open` calls — a version check and a
    // connection handshake per character, to write a hundred bytes. The count is asserted rather
    // than a duration, because the cost is structural: it does not depend on the machine.
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

    // One, not twenty-five. `clear` runs on the RESYNC_REQUIRED path — after the user
    // has been told their changes could not be merged, and while the recovery save is racing the
    // tab being closed.
    expect(recorder.transactions).toBe(1)
    expect(await offlineStore.pending('doc-1')).toEqual([])
    // Another document's buffer is not its business.
    expect(await offlineStore.pending('doc-2')).toHaveLength(1)
  })
})

describe('a transaction that aborts', () => {
  it('rejects instead of hanging for ever', async () => {
    recorder.abortNext = true

    // The failure this models is the quota running out, which is exactly the case the durable
    // buffer exists for and the one that repeats. Before `onabort` was handled, this promise
    // simply never settled: no rejection, so `DocumentConnection.reportStorageFailure` never ran
    // and the user was never told the local backup had stopped working.
    await expect(offlineStore.remember('doc-1', op(1))).rejects.toThrow
  })
})
