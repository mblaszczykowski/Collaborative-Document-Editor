/**
 * The identity remembered for the lifetime of a browser TAB, plus the diagnostics toggle.
 *
 * Split out of App.tsx, because these are the only places that reach into `sessionStorage` and the
 * page address — that is, into state from outside the application. Keeping them together means one
 * can say at a glance what this application reads from its environment.
 */
import type { Session } from '../api'

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
  if (stored === null) return null
  try {
    return JSON.parse(stored) as Session
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}
