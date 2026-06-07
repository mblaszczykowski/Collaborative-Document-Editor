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
  const response = await request.post(`${API}/documents`, { data: { title } })
  return (await response.json()).id as string
}

export async function joinAs(browser: Browser, name: string, documentId: string): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Twoje imię').fill(name)
  await page.getByRole('button', { name: 'Rozpocznij' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

export async function typeInto(page: Page, text: string): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}
