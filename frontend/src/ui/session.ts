import { setAuthToken, type Session } from '../api'

export const SESSION_KEY = 'collabedit.session'

export const DEBUG = new URLSearchParams(window.location.search).has('debug')

const TAB_SCOPED_KEY = 'collabedit.tab-scoped'

if (new URLSearchParams(window.location.search).has('tab-session')) {
  sessionStorage.setItem(TAB_SCOPED_KEY, '1')
}

function storage(): Storage {
  return sessionStorage.getItem(TAB_SCOPED_KEY) === null ? localStorage : sessionStorage
}

export function loadSession(): Session | null {
  const stored = storage().getItem(SESSION_KEY)
  if (stored === null) {
    setAuthToken(null)
    return null
  }
  try {
    const session = JSON.parse(stored) as Session
    setAuthToken(session.token)
    return session
  } catch {
    storage().removeItem(SESSION_KEY)
    setAuthToken(null)
    return null
  }
}

export function rememberSession(session: Session): void {
  storage().setItem(SESSION_KEY, JSON.stringify(session))
  setAuthToken(session.token)
}

export function forgetSession(): void {
  storage().removeItem(SESSION_KEY)
  setAuthToken(null)
}
