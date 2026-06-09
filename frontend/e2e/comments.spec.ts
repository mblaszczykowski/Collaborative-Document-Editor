import { expect, test } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

/**
 * Komentarze (CP14) na prawdziwych przeglądarkach.
 *
 * Testy integracyjne po stronie serwera sprawdzają uprawnienia i trwałość wątków.
 * Tutaj chodzi o to, czego one sprawdzić nie mogą: czy komentarz da się założyć gestem,
 * czy widzi go druga osoba i czy rozmowa zostaje, gdy zakotwiczony tekst zniknie.
 */

/** Zaznacza `count` znaków od początku dokumentu. */
async function selectFromStart(page: import('@playwright/test').Page, count: number) {
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < count; i++) await page.keyboard.press('Shift+ArrowRight')
}

async function openCommentsPanel(page: import('@playwright/test').Page) {
  // Celujemy w PASEK NARZĘDZI, nie w nazwę dostępną: `getByRole` dopasowuje nazwę
  // po fragmencie, więc trafiał również w dokumenty o tytule zawierającym to słowo.
  // Ten sam błąd wyszedł już raz przy przycisku „Kosz" w documents.spec.ts.
  await page.locator('.editor-toolbar button[aria-label="Komentarze"]').click()
  await expect(page.locator('.rp-header')).toContainText('Komentarze')
}

test('komentarz założony przez jedną osobę jest widoczny dla drugiej', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, `Rozmowa-${Date.now()}`)
  const ania = await joinAs(browser, 'Ania', documentId)
  const bartek = await joinAs(browser, 'Bartek', documentId)

  await typeInto(ania, 'zdanie do omowienia')
  await expect(bartek.locator('.cm-content')).toContainText('zdanie do omowienia', {
    timeout: 10_000,
  })

  await openCommentsPanel(ania)
  await selectFromStart(ania, 6)

  await expect(ania.locator('.comment-quote')).toContainText('zdanie', { timeout: 10_000 })
  await ania.locator('.comment-draft').fill('Czy to jest jasne?')
  await ania.getByRole('button', { name: 'Skomentuj' }).click()

  await expect(ania.locator('.comment-thread')).toContainText('Czy to jest jasne?', {
    timeout: 10_000,
  })

  // Druga osoba widzi rozmowę po otwarciu panelu — treść idzie REST-em, więc pojawia się
  // przy odświeżeniu listy, a nie natychmiast.
  await openCommentsPanel(bartek)
  await expect(bartek.locator('.comment-thread')).toContainText('Czy to jest jasne?', {
    timeout: 15_000,
  })

  // Kotwica idzie WebSocketem jako znacznik CRDT, więc podświetlenie musi dotrzeć
  // do drugiej przeglądarki bez żadnego odświeżania.
  await expect(bartek.locator('.cm-content .cm-mark-comment')).toBeVisible({ timeout: 10_000 })

  await ania.context().close()
  await bartek.context().close()
})

test('wątek zostaje po skasowaniu zakotwiczonego tekstu i jest oznaczony jako odłączony', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, `Kotwica-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'fragment do usuniecia')
  await openCommentsPanel(page)
  await selectFromStart(page, 8)
  await page.locator('.comment-draft').fill('Czy to zostaje?')
  await page.getByRole('button', { name: 'Skomentuj' }).click()
  await expect(page.locator('.comment-thread')).toContainText('Czy to zostaje?', { timeout: 10_000 })

  // Kasujemy zakotwiczony fragment.
  await selectFromStart(page, 8)
  await page.keyboard.press('Backspace')
  await expect(page.locator('.cm-content')).toContainText('do usuniecia', { timeout: 10_000 })

  // Rozmowa ZOSTAJE, z zacytowanym fragmentem i oznaczeniem — kasowanie rozmowy
  // w reakcji na edycję tekstu jest zachowaniem, którego nikt się nie spodziewa.
  await expect(page.locator('.comment-thread')).toContainText('Czy to zostaje?')
  await expect(page.locator('.comment-thread.detached')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.comment-quote').last()).toContainText('fragment')

  await page.context().close()
})

test('rozwiązanie wątku chowa go z listy, ale rozmowa zostaje', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Watek-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'tekst do skomentowania')
  await openCommentsPanel(page)
  await selectFromStart(page, 5)
  await page.locator('.comment-draft').fill('Do rozwiązania')
  await page.getByRole('button', { name: 'Skomentuj' }).click()
  await expect(page.locator('.comment-thread')).toContainText('Do rozwiązania', { timeout: 10_000 })

  await page.getByRole('button', { name: 'Rozwiąż' }).click()
  await expect(page.locator('.comment-thread')).toHaveCount(0, { timeout: 10_000 })

  // …ale rozmowa nie zniknęła — jest pod przełącznikiem.
  await page.getByRole('button', { name: 'Pokaż rozwiązane' }).click()
  await expect(page.locator('.comment-thread.resolved')).toContainText('Do rozwiązania', {
    timeout: 10_000,
  })

  await page.context().close()
})
