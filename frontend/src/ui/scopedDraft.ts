import { useCallback, useState, type Dispatch, type SetStateAction } from 'react'

const memory = new Map<string, string>()
export function draftKey(userId: string, documentId: string, kind: string): string {
  return `collabedit.draft:${JSON.stringify([userId, documentId, kind])}`
}
export function readDraft(key: string): string {
  if (memory.has(key)) return memory.get(key)!
  try {
    const value = sessionStorage.getItem(key)
    if (value !== null) { memory.set(key, value); return value }
  } catch { /* A blocked browser store still permits drafts within this page. */ }
  return ''
}
export function writeDraft(key: string, value: string): void {
  memory.set(key, value)
  try {
    if (value === '') sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, value)
  } catch { /* Keep the memory copy when browser persistence is unavailable. */ }
}
export function updateDraft(key: string, next: SetStateAction<string>): string {
  const current = readDraft(key)
  const value = typeof next === 'function' ? next(current) : next
  writeDraft(key, value)
  return value
}

export function useScopedDraft(userId: string, documentId: string, kind: string):
  [string, Dispatch<SetStateAction<string>>] {
  const key = draftKey(userId, documentId, kind)
  const [stored, setStored] = useState(() => ({ key, value: readDraft(key) }))
  const value = stored.key === key ? stored.value : readDraft(key)
  const setValue = useCallback<Dispatch<SetStateAction<string>>>(next => {
    // A prior panel instance may finish submitting after a new instance has edited this key.
    const value = updateDraft(key, next)
    setStored({ key, value })
  }, [key])
  return [value, setValue]
}
