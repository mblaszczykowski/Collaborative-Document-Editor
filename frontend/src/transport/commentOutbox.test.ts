import { afterEach, describe, expect, it, vi } from 'vitest'
import type { QueuedComment } from './offlineStore'

const calls = vi.hoisted(() => ({
  open: vi.fn(), reply: vi.fn(), pending: vi.fn(), forget: vi.fn(), queue: vi.fn(),
}))
vi.mock('../api', () => ({
  ApiError: class extends Error {
    status: number
    constructor(_method: string, _path: string, status: number) { super('refused'); this.status = status }
  },
  api: { openThread: calls.open, replyToThread: calls.reply },
}))
vi.mock('./offlineStore', () => ({ offlineStore: {
  pendingComments: calls.pending, forgetComment: calls.forget, queueComment: calls.queue,
} }))
vi.stubGlobal('window', { dispatchEvent: () => {} })
const { drainComments } = await import('./commentOutbox')
const { ApiError } = await import('../api')
const entry: QueuedComment = { userId: 'anna', clientId: 'stable-request', documentId: 'doc',
  kind: 'thread', body: 'hello', queuedAt: 1,
  anchor: { from: { replicaId: 1, lamport: 0 }, to: { replicaId: 1, lamport: 3 } } }
afterEach(() => vi.resetAllMocks())

describe('durable comment delivery', () => {
  it('retains the same id and captured anchor until the document is ready', async () => {
    calls.pending.mockResolvedValue([entry])
    calls.open.mockResolvedValue({ id: 'thread' })
    const anchor = vi.fn().mockReturnValueOnce('not-ready').mockReturnValue('anchored')
    await drainComments('doc', 'anna', anchor, vi.fn())
    expect(calls.forget).not.toHaveBeenCalled()
    await drainComments('doc', 'anna', anchor, vi.fn())
    expect(calls.open.mock.calls.map(args => args[3])).toEqual(['stable-request', 'stable-request'])
    expect(anchor).toHaveBeenLastCalledWith('thread', entry.anchor)
    expect(calls.forget).toHaveBeenCalledWith('stable-request')
  })

  it('retries a lost response without allocating a new request identity', async () => {
    calls.pending.mockResolvedValue([entry])
    calls.open.mockRejectedValueOnce(new Error('response lost')).mockResolvedValue({ id: 'thread' })
    await drainComments('doc', 'anna', () => 'anchored', vi.fn())
    expect(calls.forget).not.toHaveBeenCalled()
    await drainComments('doc', 'anna', () => 'anchored', vi.fn())
    expect(calls.open.mock.calls.map(args => args[3])).toEqual(['stable-request', 'stable-request'])
    expect(calls.forget).toHaveBeenCalledTimes(1)
  })

  it('stops before posting when the owning document or session changes', async () => {
    calls.pending.mockResolvedValue([entry])
    await drainComments('doc', 'anna', () => 'anchored', vi.fn(), () => false)
    expect(calls.open).not.toHaveBeenCalled()
    expect(calls.forget).not.toHaveBeenCalled()
  })

  it('shares one flight across reconnect and periodic retry triggers', async () => {
    calls.pending.mockResolvedValue([entry])
    let finish!: (value: { id: string }) => void
    calls.open.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const first = drainComments('doc', 'anna', () => 'anchored', vi.fn())
    const second = drainComments('doc', 'anna', () => 'anchored', vi.fn())
    await Promise.resolve()
    expect(calls.open).toHaveBeenCalledTimes(1)
    finish({ id: 'thread' })
    await Promise.all([first, second])
    expect(calls.forget).toHaveBeenCalledTimes(1)
  })
})

it('keeps a refused queued comment recoverable instead of deleting its body', async () => {
  calls.pending.mockResolvedValue([entry])
  calls.open.mockRejectedValue(new ApiError('POST', '/comments', 400))
  calls.queue.mockResolvedValue(undefined)
  await drainComments('doc', 'anna', () => 'anchored', vi.fn())
  expect(calls.forget).not.toHaveBeenCalled()
  expect(calls.queue).toHaveBeenCalledWith(expect.objectContaining({
    clientId: entry.clientId, body: entry.body, blocked: expect.any(String),
  }))
})

it('waits for the anchor’s durable receipt before forgetting a posted thread', async () => {
  calls.pending.mockResolvedValue([entry])
  calls.open.mockResolvedValue({ id: 'thread' })
  let acknowledge!: (value: 'anchored') => void
  const anchor = new Promise<'anchored'>(resolve => { acknowledge = resolve })
  const flight = drainComments('doc', 'anna', () => anchor, vi.fn())
  await Promise.resolve()
  await Promise.resolve()
  expect(calls.forget).not.toHaveBeenCalled()
  acknowledge('anchored')
  await flight
  expect(calls.forget).toHaveBeenCalledWith(entry.clientId)
})

it('retains the posted thread for retry when the anchor disconnects before acknowledgement', async () => {
  calls.pending.mockResolvedValue([entry])
  calls.open.mockResolvedValue({ id: 'thread' })
  await drainComments('doc', 'anna', () => Promise.reject(new Error('disconnected')), vi.fn())
  expect(calls.forget).not.toHaveBeenCalled()
})
