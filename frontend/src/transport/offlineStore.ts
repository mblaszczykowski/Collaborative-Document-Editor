import { encodeOp, decodeOp, type WireOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

/**
 * A durable buffer of operations not yet acknowledged as written.
 *
 * An in-memory buffer is enough for losing the network. It is not enough for what a user actually
 * does: closes the tab, puts the laptop to sleep, reloads the page because "something froze". In
 * all of those cases operations that were sent but not acknowledged would be lost — even though the
 * user had seen them on screen.
 *
 * Hence IndexedDB rather than `localStorage`: the latter is synchronous and blocks the UI thread on
 * every write, that is, on every keystroke. On a document with a long buffer that would be
 * noticeable exactly when the network is down and everything is irritating anyway.
 *
 * An entry disappears only once the server acknowledges a DURABLE WRITE (ADR-0003) — never on the
 * broadcast alone.
 */

const DATABASE = 'inkline'
const STORE = 'unacknowledged'

/**
 * The undo stacks, kept per document (CP25).
 *
 * A second store rather than a second database: they share a lifetime (both belong to "what this
 * browser knows about a document that is not on the server yet") and an upgrade path.
 */
const UNDO_STORE = 'undo'

/**
 * Comments written without a connection (CP27).
 *
 * The buffer above holds CRDT operations, which travel the WebSocket. A comment travels REST, so
 * before CP27 one written offline simply disappeared — the README listed it as a limit of CP14.
 */
const COMMENT_STORE = 'comments'
const VERSION = 3

/** A comment waiting for a connection (CP27). */
export interface QueuedComment {
  clientId: string
  documentId: string
  /** A new conversation, or a reply to an existing one. */
  kind: 'thread' | 'reply'
  threadId?: string
  quotedText?: string
  body: string
  queuedAt: number
}

interface StoredEntry {
  key: string
  documentId: string
  op: WireOp
  queuedAt: number
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      if (!database.objectStoreNames.contains(STORE)) {
        const store = database.createObjectStore(STORE, { keyPath: 'key' })
        store.createIndex('byDocument', 'documentId')
      }
      // Created without touching the store above: a browser that already has a buffer from
      // before CP25 must keep it. An upgrade that dropped unacknowledged operations would lose
      // exactly the work this whole mechanism exists to protect.
      if (!database.objectStoreNames.contains(UNDO_STORE)) {
        database.createObjectStore(UNDO_STORE, { keyPath: 'documentId' })
      }
      if (!database.objectStoreNames.contains(COMMENT_STORE)) {
        const store = database.createObjectStore(COMMENT_STORE, { keyPath: 'clientId' })
        store.createIndex('byDocument', 'documentId')
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function transact<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
  storeName: string = STORE,
): Promise<T> {
  return openDatabase().then(
    (database) =>
      new Promise<T>((resolve, reject) => {
        const transaction = database.transaction(storeName, mode)
        const request = action(transaction.objectStore(storeName))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
        transaction.oncomplete = () => database.close()
      }),
  )
}

export const offlineStore = {
  async remember(documentId: string, op: Op): Promise<void> {
    const entry: StoredEntry = {
      key: `${documentId}:${op.id.replicaId}@${op.id.lamport}`,
      documentId,
      op: encodeOp(op),
      queuedAt: Date.now(),
    }
    await transact('readwrite', (store) => store.put(entry))
  },

  async forget(documentId: string, op: Op): Promise<void> {
    await transact('readwrite', (store) =>
      store.delete(`${documentId}:${op.id.replicaId}@${op.id.lamport}`),
    )
  },

  /**
   * The operations remembered for a document, oldest first.
   *
   * The order does not matter for correctness (merging is commutative — ADR-0001), but it does
   * matter for legible logs and for what you see while debugging.
   */
  async pending(documentId: string): Promise<Op[]> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt).map((entry) => decodeOp(entry.op))
  },

  /**
   * Clears a document's buffer.
   *
   * Used only on `RESYNC_REQUIRED` (ADR-0005): operations the server can no longer merge have no
   * reason to occupy space and retry forever. Their content is rescued separately at that point, as
   * a recovered draft.
   */
  async clear(documentId: string): Promise<void> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    for (const entry of all) {
      await transact('readwrite', (store) => store.delete(entry.key))
    }
  },

  /**
   * Saves the undo stacks for a document (CP25).
   *
   * The stacks used to live in the tab's memory and die with a reload, which the README listed as
   * a limit: reloading a page cost you the ability to undo work you could still see on screen.
   * Storing them is cheap because the entries are already made of things that outlive a session —
   * character identifiers and text, never positions.
   */
  async saveUndo(documentId: string, state: unknown): Promise<void> {
    await transact('readwrite', (store) => store.put({ documentId, state }), UNDO_STORE)
  },

  /**
   * Remembers a comment that could not be posted (CP27).
   *
   * The `clientId` is generated here and travels with every retry, so the server can recognise
   * an attempt that already arrived. That is the whole mechanism: what an offline comment needs
   * is not merging but exactly-once, and exactly-once is a key rather than a data structure
   * (ADR-0011 held).
   */
  async queueComment(entry: QueuedComment): Promise<void> {
    await transact('readwrite', (store) => store.put(entry), COMMENT_STORE)
  },

  async pendingComments(documentId: string): Promise<QueuedComment[]> {
    const all = await transact<QueuedComment[]>(
      'readonly',
      (store) => store.index('byDocument').getAll(documentId),
      COMMENT_STORE,
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt)
  },

  async forgetComment(clientId: string): Promise<void> {
    await transact('readwrite', (store) => store.delete(clientId), COMMENT_STORE)
  },

  async loadUndo<T>(documentId: string): Promise<T | null> {
    const row = await transact<{ documentId: string; state: T } | undefined>(
      'readonly',
      (store) => store.get(documentId),
      UNDO_STORE,
    )
    return row?.state ?? null
  },
}
