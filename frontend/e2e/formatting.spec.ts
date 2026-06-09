import { expect, test } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

/**
 * Formatowanie tekstu (CP13) na dwóch prawdziwych przeglądarkach.
 *
 * Property testy dowodzą zbieżności warstwy znaczników, a wektory konformancyjne spinają
 * implementację w Javie z tą w TypeScripcie. Żadne z nich nie odpowiada na pytanie, czy
 * **to, co widzi użytkownik**, zgadza się z modelem — między CRDT a DOM-em jest jeszcze
 * CodeMirror, dekoracje i przeliczanie odcinków na offsety.
 */

/** Czy fragment tekstu jest narysowany z danym formatowaniem. */
async function hasMark(page: import('@playwright/test').Page, cssClass: string, text: string) {
  return page.locator(`.cm-content .${cssClass}`, { hasText: text })
}

test('pogrubienie nadane przez jedną osobę pojawia się u drugiej', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Formatowanie-${Date.now()}`)
  const ania = await joinAs(browser, 'Ania', documentId)
  const bartek = await joinAs(browser, 'Bartek', documentId)

  await typeInto(ania, 'alfa beta gamma')
  await expect(bartek.locator('.cm-content')).toContainText('alfa beta gamma', { timeout: 10_000 })

  // Ania zaznacza „beta" i pogrubia je skrótem klawiszowym.
  await ania.locator('.cm-content').click()
  await ania.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await ania.keyboard.press('ArrowRight')
  for (let i = 0; i < 4; i++) await ania.keyboard.press('Shift+ArrowRight')
  await ania.keyboard.press('ControlOrMeta+b')

  await expect(await hasMark(ania, 'cm-mark-bold', 'beta')).toBeVisible({ timeout: 10_000 })
  await expect(await hasMark(bartek, 'cm-mark-bold', 'beta'))
    .toBeVisible({ timeout: 10_000 })

  await ania.context().close()
  await bartek.context().close()
})

test('pisanie na końcu pogrubienia kontynuuje je', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Rozszerzanie-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'alfa beta gamma')

  // Pogrubiamy „beta".
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowRight')
  for (let i = 0; i < 4; i++) await page.keyboard.press('Shift+ArrowRight')
  await page.keyboard.press('ControlOrMeta+b')
  await expect(await hasMark(page, 'cm-mark-bold', 'beta')).toBeVisible({ timeout: 10_000 })

  // Kursor tuż za „beta", dopisujemy — pogrubienie ma objąć nowy tekst.
  await page.keyboard.press('ArrowRight')
  await page.keyboard.type('XY', { delay: 30 })

  await expect(await hasMark(page, 'cm-mark-bold', 'betaXY'))
    .toBeVisible({ timeout: 10_000 })

  await page.context().close()
})

test('formatowanie przeżywa przeładowanie strony', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Trwałość-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'alfa beta gamma')
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < 4; i++) await page.keyboard.press('Shift+ArrowRight')
  await page.keyboard.press('ControlOrMeta+i')

  await expect(await hasMark(page, 'cm-mark-italic', 'alfa')).toBeVisible({ timeout: 10_000 })

  // Przeładowanie odtwarza dokument z logu operacji — formatowanie musi wrócić razem
  // z tekstem, bo jest zapisane jako operacje, a nie jako wynik.
  await page.reload()
  await expect(page.locator('.cm-content')).toContainText('alfa beta gamma', { timeout: 15_000 })
  await expect(await hasMark(page, 'cm-mark-italic', 'alfa'))
    .toBeVisible({ timeout: 10_000 })

  await page.context().close()
})
