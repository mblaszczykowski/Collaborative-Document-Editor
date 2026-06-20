import { expect, test } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

/**
 * Text formatting (CP13) across two real browsers.
 *
 * The property tests prove the mark layer converges, and the conformance vectors tie the Java
 * implementation to the TypeScript one. Neither answers whether **what the user sees** matches the
 * model — between the CRDT and the DOM sit CodeMirror, the decorations, and the conversion of runs
 * into offsets.
 */

/** Whether a fragment of text is drawn with the given formatting. */
async function hasMark(page: import('@playwright/test').Page, cssClass: string, text: string) {
  return page.locator(`.cm-content .${cssClass}`, { hasText: text })
}

test('bold applied by one person appears for the other', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Formatting-${Date.now()}`)
  const ania = await joinAs(browser, 'Ania', documentId)
  const bartek = await joinAs(browser, 'Bartek', documentId)

  await typeInto(ania, 'alfa beta gamma')
  await expect(bartek.locator('.cm-content')).toContainText('alfa beta gamma', { timeout: 10_000 })

  // Ania selects "beta" and bolds it with a keyboard shortcut.
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

test('typing at the end of a bold range continues it', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Expanding-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'alfa beta gamma')

  // Bold "beta".
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowRight')
  for (let i = 0; i < 4; i++) await page.keyboard.press('Shift+ArrowRight')
  await page.keyboard.press('ControlOrMeta+b')
  await expect(await hasMark(page, 'cm-mark-bold', 'beta')).toBeVisible({ timeout: 10_000 })

  // Cursor just after "beta", then type — the bold should cover the new text.
  await page.keyboard.press('ArrowRight')
  await page.keyboard.type('XY', { delay: 30 })

  await expect(await hasMark(page, 'cm-mark-bold', 'betaXY'))
    .toBeVisible({ timeout: 10_000 })

  await page.context().close()
})

test('formatting survives a page reload', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Durability-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'alfa beta gamma')
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < 4; i++) await page.keyboard.press('Shift+ArrowRight')
  await page.keyboard.press('ControlOrMeta+i')

  await expect(await hasMark(page, 'cm-mark-italic', 'alfa')).toBeVisible({ timeout: 10_000 })

  // A reload rebuilds the document from the operation log — the formatting has to come back with
  // the text, because it is stored as operations rather than as a result.
  await page.reload()
  await expect(page.locator('.cm-content')).toContainText('alfa beta gamma', { timeout: 15_000 })
  await expect(await hasMark(page, 'cm-mark-italic', 'alfa'))
    .toBeVisible({ timeout: 10_000 })

  await page.context().close()
})
