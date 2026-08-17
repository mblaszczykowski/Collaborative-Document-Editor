import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
  })

  it('reports an expiry when a request made WITH a token is refused', async () => {
    setAuthToken('a-token-the-server-no-longer-likes')
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(api.listDocuments()).rejects.toBeInstanceOf(ApiError)

    expect(
      expired,
      `A 401 went unnoticed. Only one caller used to handle this, so an expiry while the
       application is open surfaced as whatever that particular screen says when a request
       fails - for the editor, a message telling the user to ask a colleague to re-share a
       document the colleague has not touched.`,
    ).toHaveBeenCalledOnce()
  })

  it('does NOT report an expiry for a request made with no token', async () => {
    setAuthToken(null)
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(api.session('a-code-nobody-issued')).rejects.toBeInstanceOf(ApiError)

    expect(
      expired,
      `A failed sign-in was treated as an expired session. The user is already looking at the
       sign-in screen, so at best this is noise - but the handler discards the stored identity,
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
      'a 404 signed the user out - every deleted document would now end the session',
    ).not.toHaveBeenCalled()
  })

  it('still throws, so the caller can unwind', async () => {
    setAuthToken('a-token')
    vi.stubGlobal('fetch', vi.fn(async () => UNAUTHORIZED))

    await expect(
      api.listDocuments(),
      `The rejection was swallowed once the handler existed. Whatever was awaiting the request
       would then wait for ever - a spinner that never resolves, on top of a session that has
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
