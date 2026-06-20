import { expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Shared setup for the end-to-end scenarios.
 *
 * Split out when the second test file arrived (the document list, CP11) — until then these
 * functions lived in the only scenario there was and there was nothing to share.
 */

export const APP = 'http://localhost:5173'
export const API = 'http://localhost:8080/api'

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
