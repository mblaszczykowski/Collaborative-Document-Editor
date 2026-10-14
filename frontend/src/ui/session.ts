import { setAuthToken, type Session } from '../api'

export const SESSION_KEY = 'collabedit.session'
export const DEBUG = new URLSearchParams(window.location.search).has('debug')
const TAB_SCOPED_KEY = 'collabedit.tab-scoped'
let memory: Session | null = null
let memoryOnly = false
let tabScoped = new URLSearchParams(window.location.search).has('tab-session')
try {
  if (tabScoped) sessionStorage.setItem(TAB_SCOPED_KEY, '1')
  tabScoped ||= sessionStorage.getItem(TAB_SCOPED_KEY) !== null
} catch { /* Browsers may disable storage; keep this tab usable. */ }

function storage(): Storage { return tabScoped ? sessionStorage : localStorage }
function valid(value: unknown): value is Session {
  if (!value || typeof value !== 'object') return false
  const session = value as Record<string, unknown>
  return ['userId', 'displayName', 'color', 'token'].every(key =>
    typeof session[key] === 'string' && session[key].length > 0)
}

export function loadSession(): Session | null {
  try {
    if (!memoryOnly) {
      const stored = storage().getItem(SESSION_KEY)
      const parsed: unknown = stored === null ? null : JSON.parse(stored)
      if (parsed !== null && !valid(parsed)) {
        storage().removeItem(SESSION_KEY)
        memory = null
      } else memory = parsed as Session | null
    }
  } catch {
    try { storage().removeItem(SESSION_KEY) } catch { memoryOnly = true }
    if (!memoryOnly) memory = null
  }
  setAuthToken(memory?.token ?? null)
  return memory
}

export function rememberSession(session: Session): void {
  memory = session
  try { storage().setItem(SESSION_KEY, JSON.stringify(session)); memoryOnly = false }
  catch { memoryOnly = true }
  setAuthToken(session.token)
}

export function forgetSession(): void {
  memory = null
  try { storage().removeItem(SESSION_KEY) } catch { memoryOnly = true }
  setAuthToken(null)
}
