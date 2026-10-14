import { expect, test as base, type APIRequestContext, type Browser, type Page } from '@playwright/test'

export { expect }

export const APP = process.env.E2E_APP_URL ?? 'http://localhost:5173'
export const API = `${process.env.E2E_API_URL ?? 'http://localhost:8090'}/api`

export async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Fixture' } })
  expect(login.ok(), 'fixture login succeeds').toBeTruthy()
  const { token } = (await login.json()) as { token: string }
  const headers = { Authorization: `Bearer ${token}` }

  const response = await request.post(`${API}/documents`, { data: { title }, headers })
  expect(response.ok(), 'fixture document creation succeeds').toBeTruthy()
  const id = (await response.json()).id as string

  const visibility = await request.put(`${API}/documents/${id}/access/visibility`, {
    data: { visibility: 'link' },
    headers,
  })
  expect(visibility.status(), 'fixture visibility change succeeds').toBe(204)
  return id
}

const watched: { name: string; problems: string[] }[] = []
const offlineWindows = new WeakSet<Page>()
const unavailableWindows = new WeakMap<Page, Set<string>>()

export function expectDocumentUnavailableErrors(page: Page, documentId: string, expected: boolean): void {
  const documents = unavailableWindows.get(page) ?? new Set<string>()
  if (expected) documents.add(documentId)
  else documents.delete(documentId)
  unavailableWindows.set(page, documents)
}

/** Scope browser transport errors to the page whose network is deliberately cut. */
export function expectOfflineErrors(page: Page, expected: boolean): void {
  if (expected) offlineWindows.add(page)
  else offlineWindows.delete(page)
}

export function watchConsole(page: Page, name: string, unavailableDocument?: string): void {
  if (unavailableDocument) expectDocumentUnavailableErrors(page, unavailableDocument, true)
  const problems: string[] = []
  watched.push({ name, problems })
  page.on('console', message => {
    if (message.type() !== 'error') return
    const text = message.text()
    if (offlineWindows.has(page) && (
      /net::ERR_(INTERNET_DISCONNECTED|NETWORK_CHANGED|CONNECTION_CLOSED)/.test(text)
      || /WebSocket connection to .* failed/.test(text)
    )) return
    if (/Failed to load resource.*404/.test(text)) {
      const location = message.location().url
      let url: URL
      try { url = new URL(location) } catch { problems.push(`console.error: ${text}`); return }
      if ([...(unavailableWindows.get(page) ?? [])].some(documentId => {
        const documentPath = `/api/documents/${documentId}`
        return [new URL(API).origin, new URL(APP).origin].includes(url.origin)
          && (url.pathname === documentPath || url.pathname.startsWith(`${documentPath}/`))
      })) return
    }
    problems.push(`console.error: ${text}`)
  })
  page.on('pageerror', error => problems.push(`uncaught: ${error.message}`))
}

export const test = base.extend<{ cleanConsole: void }>({
  cleanConsole: [
    async ({ browser: _browser }, use) => {
      watched.length = 0
      await use()
      const problems = watched.flatMap(({ name, problems: found }) =>
        found.map((problem) => `[${name}] ${problem}`))
      watched.length = 0
      expect(
        problems,
        'the browser reported errors during this test - an uncaught exception or a console.error '
        + 'is a defect even when the assertions pass',
      ).toEqual([])
    },
    { auto: true },
  ],
})

export async function joinAs(
  browser: Browser,
  name: string,
  documentId: string,
  options: { waitForEditor?: boolean } = {},
): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()

  watchConsole(page, name, options.waitForEditor === false ? documentId : undefined)

  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  if (options.waitForEditor !== false) {
    await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.cm-content')).not.toHaveAttribute('aria-readonly', 'true', { timeout: 15_000 })
  }
  return page
}

export async function deleteDocument(request: APIRequestContext, documentId: string): Promise<void> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Cleaner' } })
  expect(login.ok(), 'cleanup fixture login succeeds').toBeTruthy()
  const { token } = (await login.json()) as { token: string }
  const response = await request.delete(`${API}/documents/${documentId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status(), 'the document could not be deleted - the test would be checking something else')
    .toBe(204)
}

export async function typeInto(page: Page, text: string): Promise<void> {
  await expect(page.locator('.cm-content')).not.toHaveAttribute('aria-readonly', 'true')
  await expect(page.locator('.cm-content')).toHaveAttribute('contenteditable', 'true')
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}
