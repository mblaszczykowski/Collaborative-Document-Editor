import { expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Wspólne przygotowanie scenariuszy end-to-end.
 *
 * Wydzielone, gdy doszedł drugi plik testów (lista dokumentów, CP11) — do tego momentu
 * te funkcje żyły w jedynym istniejącym scenariuszu i nie było czego dzielić.
 */

export const APP = 'http://localhost:5173'
export const API = 'http://localhost:8080/api'

/**
 * Świeży dokument na każdy test.
 *
 * Bez tego wszystkie testy otwierają pierwszy dokument z listy, widzą nawzajem swoje
 * zmiany i padają z powodów niemających nic wspólnego z tym, co sprawdzają. Izolacja
 * przez osobny dokument jest tańsza niż czyszczenie bazy między testami i nie wymaga
 * dostępu do niej z poziomu testu.
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
 * Otwiera dokument w świeżej przeglądarce.
 *
 * `waitForEditor: false` dla scenariuszy, w których edytor NIE MA się pojawić — dokument
 * usunięty albo bez dostępu. Domyślne czekanie na `.editor-host` byłoby tam gwarantowanym
 * przekroczeniem czasu, i to takim, które wygląda jak awaria stanowiska zamiast jak
 * sprawdzane zachowanie.
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
  await page.getByPlaceholder('Twoje imię').fill(name)
  await page.getByRole('button', { name: 'Rozpocznij' }).click()
  if (options.waitForEditor !== false) {
    await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  }
  return page
}

/**
 * Usuwa dokument spoza przeglądarki.
 *
 * Wymaga zalogowania: od CP12 usuwanie potrzebuje prawa zapisu, a żądanie bez tokenu
 * nie ma żadnej roli i dostaje 404 — czyli po cichu nic by nie skasowało.
 */
export async function deleteDocument(request: APIRequestContext, documentId: string): Promise<void> {
  const login = await request.post(`${API}/login`, { data: { displayName: 'Sprzątacz' } })
  const { token } = (await login.json()) as { token: string }
  const response = await request.delete(`${API}/documents/${documentId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status(), 'nie udało się usunąć dokumentu — test sprawdzałby coś innego')
    .toBe(204)
}

export async function typeInto(page: Page, text: string): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}
