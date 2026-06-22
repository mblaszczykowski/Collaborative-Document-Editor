/**
 * The identity remembered for the lifetime of a browser TAB, plus the diagnostics toggle.
 *
 * Split out of App.tsx, because these are the only places that reach into `sessionStorage` and the
 * page address — that is, into state from outside the application. Keeping them together means one
 * can say at a glance what this application reads from its environment.
 */
import { setAuthToken, type Session } from '../api'

export const SESSION_KEY = 'inkline.session'

/** Diagnostics mode — only for working on the project and for the chaos tests. */
export const DEBUG = new URLSearchParams(window.location.search).has('debug')

/**
 * The session survives a page reload.
 *
 * `sessionStorage`, not `localStorage`: the identity is meant to be tied to a TAB, not to the
 * browser. With `localStorage` two tabs open side by side would share an account — so the "two
 * people in two windows" scenario would stop working.
 */
export function loadSession(): Session | null {
  const stored = sessionStorage.getItem(SESSION_KEY)
  if (stored === null) {
    setAuthToken(null)
    return null
  }
  try {
    const session = JSON.parse(stored) as Session
    // The token is armed HERE, where the session is read, rather than by the caller.
    //
    // It has to be set before the first REST request goes out — including for a session restored
    // from sessionStorage, where there is no login to set it at. App.tsx used to do that from
    // inside a `useState` initialiser whose value was never read and whose setter was never
    // called, kept alive by a `void` to satisfy noUnusedLocals: a fake piece of state existing
    // only to run a side effect before the first render. This file already owns every read from
    // sessionStorage and says so at the top, so the ordering rule belongs with it.
    setAuthToken(session.token)
    return session
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    setAuthToken(null)
    return null
  }
}
