import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Stubbed BEFORE the module loads, and that ordering is the reason for the dynamic import below:
// `api.ts` reaches `config.ts`, which derives the backend origin from the address at import time,
// so a static import throws "window is not defined" in the Node test environment before a single
// assertion runs. The same shape as `ui/session.test.ts`, for the same reason.
vi.stubGlobal('window', {
  location: {
    search: '',
    origin: 'http://localhost:5173',
    protocol: 'http:',
    hostname: 'localhost',
    port: '5173',
  },
})

const { ApiError, api, setAuthToken, setUnauthorizedHandler } = await import('./api')

/**
 * What happens when the server stops recognising our token.
 *
 * <h2>The failure these catch</h2>
 * A token lasts twelve hours and the session outlives the browser, so it lapses far more often
 * while the application is OPEN than in any other way. Exactly one place used to notice — the
 * document-list bootstrap — and what the other callers did instead was worse than silence: the
 * editor showed "We cannot open this document. It may have been deleted, or you may not have
 * access to it — ask whoever sent you the link to share it again", which is untrue and sends the
 * user to bother a colleague who cannot help, and the side panels went on displaying data from
 * before the session ended.
 *
 * <p>Moving the check into `send` covers every REST call at once, including ones written later.
 * The rule that matters, and the one a future edit is most likely to break, is the second test:
 * a 401 counts as an expiry only when a token was actually presented.
 */

const OK = { ok: true, status: 200, json: async () => ({}) } as Response
const UNAUTHORIZED = { ok: false, status: 401 } as Response
const NOT_FOUND = { ok: false, status: 404 } as Response

describe('a 401 ends the session', () => {
  let expired: ReturnType<typeof vi.fn>

  beforeEach(() => {
    expired = vi.fn()
    setUnauthorizedHandler(expired)
  })

  afterEach(() => {
    setUnauthorizedHandler(null)
    setAuthToken(null)
    // Deliberately NOT `unstubAllGlobals`: that would take the `window` stub above with it, and
    // every later file sharing this worker would import `config.ts` into an empty global.
  })

  it('reports an expiry when a request made WITH a token is refused', async () => {
    setAuthToken('a-token-the-server-no-longer-likes')
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(api.listDocuments()).rejects.toBeInstanceOf(ApiError)

    expect(
      expired,
      `A 401 went unnoticed. Only one caller used to handle this, so an expiry while the
       application is open surfaced as whatever that particular screen says when a request
       fails — for the editor, a message telling the user to ask a colleague to re-share a
       document the colleague has not touched.`,
    ).toHaveBeenCalledOnce()
  })

  it('does NOT report an expiry for a request made with no token', async () => {
    // The sign-in exchange: no session yet, and 401 is the ordinary answer to a code that is
    // unknown, already spent, or older than a minute.
    setAuthToken(null)
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(api.session('a-code-nobody-issued')).rejects.toBeInstanceOf(ApiError)

    expect(
      expired,
      `A failed sign-in was treated as an expired session. The user is already looking at the
       sign-in screen, so at best this is noise — but the handler discards the stored identity,
       so it can also throw away a session a concurrent successful exchange has just
       established.`,
    ).not.toHaveBeenCalled()
  })

  it('leaves other refusals alone', async () => {
    setAuthToken('a-valid-token')
    vi.stubGlobal('fetch', vi.fn(async () => NOT_FOUND))

    await expect(api.listDocuments()).rejects.toBeInstanceOf(ApiError)

    expect(
      expired,
      'a 404 signed the user out — every deleted document would now end the session',
    ).not.toHaveBeenCalled()
  })

  it('still throws, so the caller can unwind', async () => {
    setAuthToken('a-token')
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(
      api.listDocuments(),
      `The rejection was swallowed once the handler existed. Whatever was awaiting the request
       would then wait for ever — a spinner that never resolves, on top of a session that has
       already ended.`,
    ).rejects.toMatchObject({ status: 401 })
  })

  it('a successful request reports nothing', async () => {
    setAuthToken('a-valid-token')
    vi.stubGlobal('fetch', vi.fn(async () => OK))

    await api.listDocuments()

    expect(expired).not.toHaveBeenCalled()
  })
})
