export interface WriterLease { release: () => Promise<void> }
export interface WriterLockProvider {
  request: (name: string, options: { mode: 'exclusive'; ifAvailable: true },
    callback: (lock: unknown | null) => Promise<void>) => Promise<unknown>
}

/** Keep a browser-wide actor lock until the owning document effect is disposed. */
export function acquireWriterLease(userId: string, documentId: string, replicaId: number,
  locks: WriterLockProvider | null | undefined = typeof navigator === 'undefined' ? undefined : navigator.locks,
): Promise<WriterLease | null> {
  if (!locks) return Promise.resolve(null)
  return new Promise(resolve => {
    let settled = false
    const answer = (lease: WriterLease | null) => { settled = true; resolve(lease) }
    const requestCompleted = Promise.resolve().then(() => locks.request(JSON.stringify(['collabedit-writer', userId, documentId, replicaId]),
      { mode: 'exclusive', ifAvailable: true }, async lock => {
        if (!lock) { answer(null); return }
        let release!: () => void
        const held = new Promise<void>(finish => { release = finish })
        answer({ release: () => { release(); return requestCompleted.then(() => {}) } })
        await held
      })).catch(() => { if (!settled) answer(null) })
    void requestCompleted
  })
}

export function writerLocksSupported(): boolean {
  return typeof navigator !== 'undefined' && navigator.locks !== undefined
}
