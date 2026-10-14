import { describe, expect, it, vi } from 'vitest'
import { draftKey, readDraft, writeDraft, updateDraft } from './scopedDraft'

describe('unsubmitted panel drafts', () => {
  it('persists text synchronously so closing a panel does not erase it', () => {
    const values = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    })
    const key = draftKey('anna', 'doc', 'comment')
    writeDraft(key, 'unfinished thought')
    expect(values.get(key)).toBe('unfinished thought')
    expect(readDraft(key)).toBe('unfinished thought')
    writeDraft(key, '')
    expect(values.has(key)).toBe(false)
    expect(readDraft(key)).toBe('')
  })

  it('isolates account, document and compose mode', () => {
    writeDraft(draftKey('owner', 'one', 'reply'), 'private draft')
    expect(readDraft(draftKey('other', 'one', 'reply'))).toBe('')
    expect(readDraft(draftKey('owner', 'two', 'reply'))).toBe('')
    expect(readDraft(draftKey('owner', 'one', 'comment'))).toBe('')
  })

  it('preserves drafts in memory when browser persistence is blocked', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => { throw new Error('blocked') },
      setItem: () => { throw new Error('blocked') },
      removeItem: () => { throw new Error('blocked') },
    })
    const key = draftKey('offline', 'doc', 'suggestion')
    writeDraft(key, 'proposed text')
    expect(readDraft(key)).toBe('proposed text')
  })
})


it('a stale submission completion cannot erase a newer remounted panel draft', () => {
  const key = draftKey('remount-owner', 'doc', 'comment')
  writeDraft(key, 'submitted from old panel')
  const submitted = readDraft(key)
  writeDraft(key, 'new text written after reopening the panel')
  updateDraft(key, current => current === submitted ? '' : current)
  expect(readDraft(key)).toBe('new text written after reopening the panel')
})
