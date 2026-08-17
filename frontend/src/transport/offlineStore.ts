import { encodeOp, decodeOp, type WireOp } from '../crdt/codec'
import type { Op } from '../crdt/types'

const DATABASE = 'collabedit'
const STORE = 'unacknowledged'

const UNDO_STORE = 'undo'

const COMMENT_STORE = 'comments'
const VERSION = 3

export interface QueuedComment {
  clientId: string
  documentId: string
  kind: 'thread' | 'reply'
  threadId?: string
  quotedText?: string
  anchor?: { from: { replicaId: number; lamport: number }; to: { replicaId: number; lamport: number } }
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
    request.onblocked = () =>
      reject(new Error('another tab is holding an older version of the buffer open'))
  })
}

let connection: Promise<IDBDatabase> | null = null

function database(): Promise<IDBDatabase> {
  if (connection !== null) return connection
  const opening = openDatabase().then(
    (open) => {
      const forget = () => {
        if (connection === opening) connection = null
      }
      open.onclose = forget
      open.onversionchange = () => {
        open.close()
        forget()
      }
      return open
    },
    (error: unknown) => {
      if (connection === opening) connection = null
      throw error
    },
  )
  connection = opening
  return opening
}

function transact<T>(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => IDBRequest<T>,
  storeName: string = STORE,
): Promise<T> {
  return database().then(
    (open) =>
      new Promise<T>((resolve, reject) => {
        const transaction = open.transaction(storeName, mode)
        const request = action(transaction.objectStore(storeName))
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
        transaction.onabort = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction was aborted`))
      }),
  )
}

function transactUntilComplete(
  mode: IDBTransactionMode,
  action: (store: IDBObjectStore) => void,
  storeName: string = STORE,
): Promise<void> {
  return database().then(
    (open) =>
      new Promise<void>((resolve, reject) => {
        const transaction = open.transaction(storeName, mode)
        action(transaction.objectStore(storeName))
        transaction.oncomplete = () => resolve()
        transaction.onabort = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction was aborted`))
        transaction.onerror = () =>
          reject(transaction.error ?? new Error(`the ${storeName} transaction failed`))
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

  async pending(documentId: string): Promise<Op[]> {
    const all = await transact<StoredEntry[]>('readonly', (store) =>
      store.index('byDocument').getAll(documentId),
    )
    return all.sort((a, b) => a.queuedAt - b.queuedAt).map((entry) => decodeOp(entry.op))
  },

  async clear(documentId: string): Promise<void> {
    await transactUntilComplete('readwrite', (store) => {
      const scan = store.index('byDocument').openCursor(IDBKeyRange.only(documentId))
      scan.onsuccess = () => {
        const cursor = scan.result
        if (cursor === null) return
        cursor.delete()
        cursor.continue()
      }
    })
  },

  async saveUndo(documentId: string, state: unknown): Promise<void> {
    await transact('readwrite', (store) => store.put({ documentId, state }), UNDO_STORE)
  },

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
