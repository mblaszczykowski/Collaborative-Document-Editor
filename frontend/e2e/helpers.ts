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
  // Zalogowanie się PRZED utworzeniem dokumentu (CP16 + CP21).
  //
  // Ta funkcja tworzyła dokument BEZ tokenu. Do CP16 działało, bo endpoint nie wymagał
  // tożsamości — a od CP16 wymaga jej z konkretnego powodu: dokument bez właściciela jest
  // dokumentem, którego nikt nigdy nie zabezpieczy. Żądanie bez tokenu dostaje więc 401,
  // a `response.json()` wywala się na pustej odpowiedzi. Czyli te scenariusze były czerwone
  // od CP16, a plan v3 §10.1 zapisał, że e2e nie zostały uruchomione — jedno tłumaczy drugie.
  const login = await request.post(`${API}/login`, { data: { displayName: 'Fixture' } })
  const { token } = (await login.json()) as { token: string }
  const headers = { Authorization: `Bearer ${token}` }

  const response = await request.post(`${API}/documents`, { data: { title }, headers })
  const id = (await response.json()).id as string

  // Od CP21 nowy dokument jest PRYWATNY, więc przeglądarki logujące się jako inne osoby
  // nie zobaczyłyby go wcale. Te scenariusze sprawdzają zbieżność, nie kontrolę dostępu,
  // więc dokument fixture'owy jest jawnie przełączany na „każdy zalogowany z linkiem".
  // Scenariusz sprawdzający samo udostępnianie robi to odwrotnie — patrz sharing.spec.ts.
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
