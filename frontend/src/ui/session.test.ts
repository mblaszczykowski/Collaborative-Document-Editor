import { beforeEach, describe, expect, it, vi } from 'vitest'

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

async function load(search: string, local: Storage, session: Storage) {
  vi.resetModules()
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
    local = stubStorage()
  })

  it('a second tab is still signed in', async () => {
    const first = await load('', local, stubStorage())
    first.rememberSession(SESSION)

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

    const returned = await load('?code=abc123', local, tabStore)
    returned.rememberSession(SESSION)

    expect(
      local.getItem('collabedit.session'),
      'The identity landed in the browser-wide store, so signing in from an opted-in tab quietly '
        + 'reverted it to the shared session - the opt-in works until the moment it is used.',
    ).toBeNull()
    expect(tabStore.getItem('collabedit.session')).not.toBeNull()
  })

  it('signing out ends the session the tab is actually using', async () => {
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
  })

  it('signing out of a tab-scoped identity leaves the browser-wide one alone', async () => {
    const tabStore = stubStorage()
    const shared = await load('', local, tabStore)
    shared.rememberSession(SESSION)
    const opted = await load('?tab-session', local, tabStore)
    opted.rememberSession({ ...SESSION, userId: 'u-2', token: 'token-marek' })

    opted.forgetSession()

    const primary = await load('', local, stubStorage())
    expect(
      primary.loadSession(),
      `Signing out of the scratch identity signed out the main window too. That is the one
       workflow the opt-in exists for - two identities side by side - and it means ending the
       throwaway one costs you the real one.`,
    ).toEqual(SESSION)
  })

  it('a corrupt entry is discarded rather than thrown', async () => {
    local.setItem('collabedit.session', '{not json')
    const app = await load('', local, stubStorage())

    expect(
      app.loadSession(),
      'An unparseable entry propagated out of the first call the application makes, which is a '
        + 'blank page that no reload can clear.',
    ).toBeNull()
    expect(local.getItem('collabedit.session'), 'the bad entry was left to fail again').toBeNull()
  })
})

describe('untrusted or unavailable browser storage', () => {
  it('discards valid JSON without a session shape', async () => {
    const local = stubStorage()
    local.setItem('collabedit.session', JSON.stringify({ userId: 'u' }))
    const app = await load('', local, stubStorage())
    expect(app.loadSession()).toBeNull()
    expect(local.getItem('collabedit.session')).toBeNull()
  })

  it('can sign in and sign out when browser storage is blocked', async () => {
    const blocked = stubStorage()
    blocked.getItem = () => { throw new Error('blocked') }
    blocked.setItem = () => { throw new Error('blocked') }
    blocked.removeItem = () => { throw new Error('blocked') }
    const app = await load('', blocked, blocked)
    expect(app.loadSession()).toBeNull()
    app.rememberSession(SESSION)
    expect(app.loadSession()).toEqual(SESSION)
    app.forgetSession()
    expect(app.loadSession()).toBeNull()
  })
})
