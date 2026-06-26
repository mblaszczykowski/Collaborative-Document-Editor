import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Where the session is kept, which decides whether a second tab is signed in.
 *
 * <h2>What went wrong, and why no other test saw it</h2>
 * The session lived in `sessionStorage`, so opening the application in a second tab presented the
 * sign-in screen — reported as a bug, and it was one, but it had been a deliberate choice: a tab is
 * the unit of identity, so two tabs could be two people, which is the demo a collaborative editor
 * is judged on.
 *
 * <p>Federated sign-in removed the benefit and left the cost. A second identity used to be a typed
 * name; it is now a round trip to a provider that signs you into the same account you already have.
 *
 * <p>The Playwright suite could not catch any of this: it drives each participant with
 * `browser.newContext()`, which isolates every store, so it behaves identically whichever one this
 * module picks. That is precisely why these are unit tests over the storage decision itself.
 *
 * <table>
 *   <caption>Test -&gt; failure mode</caption>
 *   <tr><th>Test</th><th>What it catches</th></tr>
 *   <tr><td>{@code aSecondTabIsStillSignedIn}</td>
 *       <td>The reported defect returning: opening a second tab signs the user out.</td></tr>
 *   <tr><td>{@code anOptedInTabKeepsItsOwnIdentity}</td>
 *       <td>The two-people-two-windows demo, which the default would otherwise take away.</td></tr>
 *   <tr><td>{@code theOptInSurvivesTheSignInRedirect}</td>
 *       <td>The subtle one. The provider returns to a bare `?code=…`, so an opt-in read from the
 *           address would be lost exactly when the identity arrives.</td></tr>
 *   <tr><td>{@code signingOutClearsBothStores}</td>
 *       <td>A sign-out that leaves a session in the store it was not using — the user clicks it,
 *           the screen returns, and a reload signs them back in as the person they just left.</td></tr>
 *   <tr><td>{@code aCorruptEntryIsDiscardedRatherThanThrown}</td>
 *       <td>An unparseable entry taking the whole application down at its first line, with a
 *           blank page and no way to clear it short of the developer tools.</td></tr>
 * </table>
 */

/** The subset of `Storage` this module uses. A real one is not needed to answer "which store". */
function stubStorage(): Storage {
  const entries = new Map<string, string>()
  return {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, value),
    removeItem: (key: string) => void entries.delete(key),
    clear: () => entries.clear(),
    key: (index: number) => [...entries.keys()][index] ?? null,
    get length() {
      return entries.size
    },
  } as Storage
}

const SESSION = {
  userId: 'u-1',
  displayName: 'Anna',
  color: '#336699',
  token: 'token-anna',
}

/**
 * Loads the module fresh against a given address and pair of stores.
 *
 * <p>It has to be re-imported per case rather than imported once: the module reads the address at
 * load time, which is the thing under test. `resetModules` is what makes each `import()` a new
 * evaluation instead of a cache hit.
 */
async function load(search: string, local: Storage, session: Storage) {
  vi.resetModules()
  // `search` is the field under test. The rest of `location` is here because the import reaches
  // `config.ts` through `api.ts`, and that module derives the backend origin from the address —
  // so a location carrying only `search` fails at import time, before a single assertion runs.
  vi.stubGlobal('window', {
    location: {
      search,
      origin: 'http://localhost:5173',
      protocol: 'http:',
      hostname: 'localhost',
      port: '5173',
    },
  })
  vi.stubGlobal('localStorage', local)
  vi.stubGlobal('sessionStorage', session)
  return await import('./session')
}

describe('where the session is kept', () => {
  let local: Storage

  beforeEach(() => {
    // Shared across the "tabs" of a single test, because one browser has one localStorage — that
    // sharing is the entire mechanism being tested.
    local = stubStorage()
  })

  it('a second tab is still signed in', async () => {
    const first = await load('', local, stubStorage())
    first.rememberSession(SESSION)

    // A new tab: same browser, so the same localStorage, but a sessionStorage of its own.
    const second = await load('', local, stubStorage())

    expect(
      second.loadSession(),
      'Opening a second tab presented the sign-in screen. The session is being kept per tab, '
        + 'which no application the user has ever used does.',
    ).toEqual(SESSION)
  })

  it('a tab that asks for its own identity gets one', async () => {
    const shared = await load('', local, stubStorage())
    shared.rememberSession(SESSION)

    const opted = await load('?tab-session', local, stubStorage())

    expect(
      opted.loadSession(),
      'The opted-in tab inherited the browser-wide session, so two identities cannot be held at '
        + 'once and the two-people-in-two-windows demo is gone.',
    ).toBeNull()
  })

  it('the opt-in survives the sign-in redirect that drops the query string', async () => {
    const tabStore = stubStorage()
    await load('?tab-session', local, tabStore)

    // Back from the provider: same tab, so the same sessionStorage, but the address is now the
    // one the server redirected to and `?tab-session` is not in it.
    const returned = await load('?code=abc123', local, tabStore)
    returned.rememberSession(SESSION)

    expect(
      local.getItem('inkline.session'),
      'The identity landed in the browser-wide store, so signing in from an opted-in tab quietly '
        + 'reverted it to the shared session — the opt-in works until the moment it is used.',
    ).toBeNull()
    expect(tabStore.getItem('inkline.session')).not.toBeNull()
  })

  it('signing out clears both stores', async () => {
    const tabStore = stubStorage()
    const shared = await load('', local, tabStore)
    shared.rememberSession(SESSION)
    const opted = await load('?tab-session', local, tabStore)
    opted.rememberSession({ ...SESSION, userId: 'u-2', token: 'token-marek' })

    opted.forgetSession()

    expect(
      opted.loadSession(),
      'A session survived signing out. The user clicks it, the sign-in screen appears, and the '
        + 'next reload restores the identity they had just left.',
    ).toBeNull()
    const stillShared = await load('', local, stubStorage())
    expect(stillShared.loadSession()).toBeNull()
  })

  it('a corrupt entry is discarded rather than thrown', async () => {
    local.setItem('inkline.session', '{not json')
    const app = await load('', local, stubStorage())

    expect(
      app.loadSession(),
      'An unparseable entry propagated out of the first call the application makes, which is a '
        + 'blank page that no reload can clear.',
    ).toBeNull()
    expect(local.getItem('inkline.session'), 'the bad entry was left to fail again').toBeNull()
  })
})
