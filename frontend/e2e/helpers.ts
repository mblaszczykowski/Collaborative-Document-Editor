import { expect, test as base, type APIRequestContext, type Browser, type Page } from '@playwright/test'

export { expect }

export const APP = process.env.E2E_APP_URL ?? 'http://localhost:5173'
export const API = `${process.env.E2E_API_URL ?? 'http://localhost:8090'}/api`

export async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Fixture' } })
  const { token } = (await login.json()) as { token: string }
  const headers = { Authorization: `Bearer ${token}` }

  const response = await request.post(`${API}/documents`, { data: { title }, headers })
  const id = (await response.json()).id as string

  await request.put(`${API}/documents/${id}/access/visibility`, {
    data: { visibility: 'link' },
    headers,
  })
  return id
}

const EXPECTED_CONSOLE_ERRORS: RegExp[] = [
  /Failed to load resource/,
  /WebSocket connection to .* failed/,
  /could not load the version history/,
]

const watched: { name: string; problems: string[] }[] = []

export const test = base.extend<{ cleanConsole: void }>({
  cleanConsole: [
    async ({}, use) => {
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

export async function deleteDocument(request: APIRequestContext, documentId: string): Promise<void> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Cleaner' } })
  const { token } = (await login.json()) as { token: string }
  const response = await request.delete(`${API}/documents/${documentId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status(), 'the document could not be deleted - the test would be checking something else')
    .toBe(204)
}

export async function typeInto(page: Page, text: string): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}
