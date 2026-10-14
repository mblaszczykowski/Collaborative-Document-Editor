import { describe, expect, it } from 'vitest'
import { acquireWriterLease, type WriterLockProvider } from './writerLease'

function browserLocks(): WriterLockProvider {
  const held = new Set<string>()
  return { async request(name, _options, callback) {
    if (held.has(name)) return callback(null)
    held.add(name)
    try { return await callback({ name }) } finally { held.delete(name) }
  } }
}

describe('browser-wide offline actor ownership', () => {
  it('prevents two document tabs from generating the same actor IDs', async () => {
    const locks = browserLocks()
    const first = await acquireWriterLease('anna', 'doc', 7, locks)
    expect(first).not.toBeNull()
    expect(await acquireWriterLease('anna', 'doc', 7, locks)).toBeNull()
    await first!.release()
    const replacement = await acquireWriterLease('anna', 'doc', 7, locks)
    expect(replacement).not.toBeNull()
    replacement!.release()
  })

  it('allows independent actors, accounts and documents to edit', async () => {
    const locks = browserLocks()
    const leases = await Promise.all([
      acquireWriterLease('anna', 'doc', 7, locks),
      acquireWriterLease('anna', 'doc', 8, locks),
      acquireWriterLease('marek', 'doc', 7, locks),
      acquireWriterLease('anna', 'other', 7, locks),
    ])
    expect(leases.every(Boolean)).toBe(true)
    leases.forEach(lease => lease!.release())
  })

  it('retains the old actor until its owner releases after acquiring a new actor', async () => {
    const locks = browserLocks()
    const old = await acquireWriterLease('anna', 'doc', 7, locks)
    const next = await acquireWriterLease('anna', 'doc', 8, locks)
    expect(next).not.toBeNull()
    expect(await acquireWriterLease('anna', 'doc', 7, locks)).toBeNull()
    old!.release()
    next!.release()
  })

  it('does not grant cached offline write permission without Web Locks', async () => {
    expect(await acquireWriterLease('anna', 'doc', 7, null)).toBeNull()
    const failing: WriterLockProvider = { request: async () => { throw new Error('disabled') } }
    expect(await acquireWriterLease('anna', 'doc', 7, failing)).toBeNull()
  })
})
