/**
 * The identity this browser remembers, plus the diagnostics toggle.
 *
 * Split out of App.tsx, because these are the only places that reach into web storage and the page
 * address — that is, into state from outside the application. Keeping them together means one can
 * say at a glance what this application reads from its environment.
 */
import { setAuthToken, type Session } from '../api'

/** Exported so the cross-tab listener in App.tsx can recognise its own key rather than guess it. */
export const SESSION_KEY = 'collabedit.session'

/** Diagnostics mode — only for working on the project and for the chaos tests. */
export const DEBUG = new URLSearchParams(window.location.search).has('debug')

/**
 * Where the session is kept — and it used to be `sessionStorage` unconditionally, which meant
 * <b>opening the application in a second tab signed you out</b>.
 *
 * <h2>The reason that was chosen, and why it stopped being a reason</h2>
 * `sessionStorage` is scoped to a tab, so two tabs could hold two different identities, and "two
 * people editing in two windows" is the demo a collaborative editor lives or dies by. That was a
 * fair trade when signing in meant typing a name: a second identity cost one keystroke.
 *
 * <p>Federated sign-in invalidated both halves of it. Getting an identity now costs a round trip to
 * Google, and the identity that comes back is <b>the same one</b> — the provider signs you into the
 * account you are already signed into, so a second tab cannot be a second person however the token
 * is stored. The cost stayed and the benefit went away.
 *
 * <p>So the default is `localStorage`: signed in once per browser, as every application the user
 * has ever used behaves. The per-tab behaviour survives as an explicit request, because it is still
 * how the demo is driven when {@code COLLABEDIT_DEV_LOGIN=true} makes a second identity free again.
 *
 * <h2>Why the opt-in is a stored marker rather than a look at the address</h2>
 * `?tab-session` cannot simply be read on each load, because the sign-in flow navigates away to the
 * provider and comes back to a bare `?code=…` — the parameter would not survive the trip that
 * matters. A marker in `sessionStorage` does: it is scoped to the tab, and a tab keeps it across
 * navigations, including one that leaves the origin and returns.
 */
const TAB_SCOPED_KEY = 'collabedit.tab-scoped'

// Recorded when the module loads, exactly like DEBUG above, and NOT on the first call that happens
// to need a store. That distinction has teeth: written lazily, the opt-in is only honoured if
// something reads or writes a session, so `?tab-session` on a tab that goes straight to the
// provider would be forgotten by the time the identity came back — the one journey it exists for.
// A test covers it (`the opt-in survives the sign-in redirect`), because it is invisible otherwise.
if (new URLSearchParams(window.location.search).has('tab-session')) {
  sessionStorage.setItem(TAB_SCOPED_KEY, '1')
}

function storage(): Storage {
  return sessionStorage.getItem(TAB_SCOPED_KEY) === null ? localStorage : sessionStorage
}

/** The session survives a page reload — and, by default, closing the browser. */
export function loadSession(): Session | null {
  const stored = storage().getItem(SESSION_KEY)
  if (stored === null) {
    setAuthToken(null)
    return null
  }
  try {
    const session = JSON.parse(stored) as Session
    // The token is armed HERE, where the session is read, rather than by the caller.
    //
    // It has to be set before the first REST request goes out — including for a restored session,
    // where there is no login to set it at. App.tsx used to do that from inside a `useState`
    // initialiser whose value was never read and whose setter was never called, kept alive by a
    // `void` to satisfy noUnusedLocals: a fake piece of state existing only to run a side effect
    // before the first render. This file already owns every read from storage and says so at the
    // top, so the ordering rule belongs with it.
    setAuthToken(session.token)
    return session
  } catch {
    storage().removeItem(SESSION_KEY)
    setAuthToken(null)
    return null
  }
}

/** Records a freshly obtained identity, and arms the token for the requests that follow. */
export function rememberSession(session: Session): void {
  storage().setItem(SESSION_KEY, JSON.stringify(session))
  setAuthToken(session.token)
}

/**
 * Discards the identity — <b>from the store this tab is actually using</b>.
 *
 * <p>It cleared both, with a comment claiming a tab-scoped tab "has a session in each, so clearing
 * one would sign the user out of a session they can still see". That reasoning was backwards, and
 * tracing the two stores shows why. A session reaches `sessionStorage` only in a tab holding the
 * marker, and such a tab reads only `sessionStorage` — so clearing it from an ordinary tab was
 * always a no-op, and the only half that did anything was an opted-in tab wiping `localStorage`,
 * which destroys the identity belonging to the OTHER window. The code did the exact thing the
 * comment gave as the reason for not doing it, and it broke the one workflow the opt-in exists
 * for: signing out of the scratch identity also signed out the primary window.
 *
 * <p>So: one store, the one in use. An ordinary sign-out is browser-wide because an ordinary
 * session is; a tab-scoped sign-out ends only the tab-scoped identity.
 *
 * <p>There is nothing to tell the server. Tokens are signed rather than stored ({@code
 * TokenService}: no refresh, no revocation, a twelve-hour expiry), so discarding it here is the
 * whole of signing out. That is a real limitation and it is written down in the README rather than
 * hidden behind a request that would only pretend to do something.
 */
export function forgetSession(): void {
  storage().removeItem(SESSION_KEY)
  setAuthToken(null)
}
