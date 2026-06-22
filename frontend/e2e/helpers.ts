import { expect, test as base, type APIRequestContext, type Browser, type Page } from '@playwright/test'

// Re-exported so a spec imports `test` and `expect` from ONE place — the place that also
// installs the console guard. Importing `test` from '@playwright/test' would silently opt out.
export { expect }

/**
 * Shared setup for the end-to-end scenarios.
 *
 * Split out when the second test file arrived (the document list, CP11) — until then these
 * functions lived in the only scenario there was and there was nothing to share.
 */

/**
 * Where the running stack is.
 *
 * Hardcoded values with an environment override, rather than plain constants. The defaults are the
 * ports the README and CI use, so nothing changes for either — but a machine that already has
 * something on 8080 or 5173 could not run these at all, and the failure was confusing rather than
 * obvious: the browser talked to one backend while the fixtures below created documents on
 * another, so tests failed with "this document is not available" and looked like authorization
 * bugs.
 */
export const APP = process.env.E2E_APP_URL ?? 'http://localhost:5173'
export const API = `${process.env.E2E_API_URL ?? 'http://localhost:8080'}/api`

/**
 * A note on `exact: true`, because this trap costs a debugging session every time.
 *
 * Playwright matches an accessible `name` as a case-insensitive SUBSTRING by default. So
 * `getByRole('button', { name: 'Comment' })` also matches the toolbar's "Comments", and
 * `{ name: 'Share' }` also matches a sidebar entry for a document somebody happened to call
 * "shared text". Both give a strict-mode violation rather than a wrong click, so the failure is
 * loud — but it is about the locator, not about the product.
 */

/**
 * A fresh document per test.
 *
 * Without it every test opens the first document on the list, sees the others' changes, and fails
 * for reasons unrelated to what it checks. Isolation by way of a separate document is cheaper than
 * clearing the database between tests and needs no database access from a test.
 */
export async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  // Log in BEFORE creating the document (CP16 + CP21).
  //
  // This helper used to create a document with NO token. That worked until CP16, when the
  // endpoint started requiring an identity for a concrete reason: a document with no owner is
  // a document nobody can ever secure. A request without a token now gets a 401, and
  // `response.json()` throws on the empty body — so these scenarios have been red since CP16.
  // Plan v3 §10.1 recorded that e2e were never run; the two facts explain each other.
  const login = await request.post(`${API}/login`, { data: { displayName: 'Fixture' } })
  const { token } = (await login.json()) as { token: string }
  const headers = { Authorization: `Bearer ${token}` }

  const response = await request.post(`${API}/documents`, { data: { title }, headers })
  const id = (await response.json()).id as string

  // Since CP21 a new document is PRIVATE, so browsers logging in as other people would not
  // see it at all. These scenarios test convergence rather than access control, so the fixture
  // document is switched explicitly to "anyone signed in with the link". The scenario that
  // tests sharing itself does the opposite — see sharing.spec.ts.
  await request.put(`${API}/documents/${id}/access/visibility`, {
    data: { visibility: 'link' },
    headers,
  })
  return id
}

/**
 * Console output that is the BROWSER's, not the application's.
 *
 * Chromium logs a failed network request to the console as an error, and this application makes
 * requests it expects to fail: the sharing scenario opens documents the signed-in user may not
 * read, and the server answers 404 by design (it is the same answer for "deleted", "never existed"
 * and "not yours" — see `AccessController`). Those are handled, on purpose, in code that says so.
 *
 * The list is deliberately this short, and every addition to it needs a reason written next to it.
 * An allow-list that grows without argument is how a clean-console check stops being one.
 */
const EXPECTED_CONSOLE_ERRORS: RegExp[] = [
  /Failed to load resource/,
  /**
   * The two scenarios that cut the network on purpose (`collaboration.spec.ts`) make the browser
   * report the things a browser reports when there is no network. Both are the condition under
   * test, not a defect:
   *
   *   - the STOMP client retries while offline, and each attempt logs a failed WebSocket connect;
   *   - `HistoryPanel` refreshes on a debounce keyed to the character count, so typing while
   *     offline runs `api.history`, whose `fetch` rejects with a bare `TypeError`.
   *
   * Allow-listed rather than silenced at the source: an editor that says nothing when its history
   * request fails is worse than one that says so in the console, and these two tests are the only
   * place the condition is deliberate.
   */
  /WebSocket connection to .* failed/,
  /could not load the version history/,
]

/** Pages opened by `joinAs` in the current test, with whatever they have complained about. */
const watched: { name: string; problems: string[] }[] = []

/**
 * Fails a test whose browser complained.
 *
 * ## Why this is worth four lines
 *
 * Two of the defects that prompted this pass — the replica keeping identifier 0 for a whole
 * session, and `adoptReplicaId` throwing on every reconnect — announce themselves LOUDLY in the
 * console and not at all in the DOM. `@stomp/stompjs` swallows exceptions thrown out of a frame
 * handler, so the only trace either of them left was an uncaught error nobody was looking at. The
 * e2e suite drove the exact code path both times, watched the text converge, and passed.
 *
 * A test that checks what the user sees and ignores what the browser is screaming is testing half
 * the product. `pageerror` catches anything thrown and not caught; `console` errors catch what the
 * application decided to report about itself, which in this repository is a deliberately short
 * list (`USER_FACING_ERRORS`, the model-divergence assertion in `Editor`) and therefore a
 * meaningful signal.
 *
 * Delivered as an AUTO FIXTURE on the `test` this module exports, rather than as a module-scope
 * `test.afterEach`.
 *
 * That is not a style preference. `helpers.ts` is imported by every spec file, and Node evaluates
 * a module **once per worker process** — so a module-scope `afterEach` registers into the suite of
 * whichever spec file happened to trigger that first import, and into no other. With
 * `workers: 1` and `collaboration.spec.ts` loading first, the hook covered 6 of the 23 tests and
 * silently skipped the rest: comments, documents, formatting, sharing and demo were never checked
 * at all, while the `watched` array they filled was never read. Worse, Playwright restarts a
 * worker after a failure, so which file got the hook depended on what had failed earlier.
 *
 * A fixture runs per test wherever the test is declared. Specs import `test` from here instead of
 * from `@playwright/test`, which is the one line that makes "importing this file is enough" true.
 */
export const test = base.extend<{ cleanConsole: void }>({
  cleanConsole: [
    // The empty pattern is required by Playwright, which reads the first argument's destructuring
    // to work out which fixtures this one depends on — `_fixtures` makes it refuse to start with
    // "First argument must use the object destructuring pattern". This fixture depends on none of
    // them, so the pattern has to be empty and the lint rule has to be told why.
    // oxlint-disable-next-line no-empty-pattern
    async ({}, use) => {
      watched.length = 0
      await use()
      const problems = watched.flatMap(({ name, problems: found }) =>
        found.map((problem) => `[${name}] ${problem}`))
      watched.length = 0
      expect(
        problems,
        'the browser reported errors during this test — an uncaught exception or a console.error '
        + 'is a defect even when the assertions pass',
      ).toEqual([])
    },
    { auto: true },
  ],
})

/**
 * Opens a document in a fresh browser.
 *
 * `waitForEditor: false` for scenarios where the editor is NOT supposed to appear — a deleted
 * document, or one without access. The default wait for `.editor-host` would be a guaranteed
 * timeout there, and one that looks like a rig failure rather than the behaviour under test.
 */
export async function joinAs(
  browser: Browser,
  name: string,
  documentId: string,
  options: { waitForEditor?: boolean } = {},
): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()

  // Attached BEFORE the first navigation, so nothing that happens during startup is missed — and
  // startup is where the replica identifier is negotiated, which is where two of these defects
  // lived.
  const problems: string[] = []
  watched.push({ name, problems })
  page.on('console', (message) => {
    if (message.type() !== 'error') return
    const text = message.text()
    if (EXPECTED_CONSOLE_ERRORS.some((pattern) => pattern.test(text))) return
    problems.push(`console.error: ${text}`)
  })
  page.on('pageerror', (error) => problems.push(`uncaught: ${error.message}`))

  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  if (options.waitForEditor !== false) {
    await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  }
  return page
}

/**
 * Deletes a document from outside the browser.
 *
 * It requires a login: since CP12 deletion needs write permission, and a request with no token has
 * no role at all and gets a 404 — that is, it would silently delete nothing.
 */
export async function deleteDocument(request: APIRequestContext, documentId: string): Promise<void> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Cleaner' } })
  const { token } = (await login.json()) as { token: string }
  const response = await request.delete(`${API}/documents/${documentId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status(), 'the document could not be deleted — the test would be checking something else')
    .toBe(204)
}

export async function typeInto(page: Page, text: string): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}
