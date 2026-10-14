import { type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { expect, test, freshDocument as sharedFreshDocument, expectOfflineErrors, watchConsole, expectDocumentUnavailableErrors } from './helpers'

const APP = process.env.E2E_APP_URL ?? 'http://localhost:5173'

const TYPING_DELAY = 45

async function moveToEnd(page: Page): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.press('ArrowRight')
}

async function joinAs(
  browser: Browser,
  name: string,
  documentId: string,
  scene: 'core' | 'product' = 'core',
  record = true,
): Promise<Page> {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 760 },
    recordVideo: record
      ? { dir : `demo-recordings/${scene}`, size: { width: 1280, height: 760 } }
      : undefined,
  })
  const page = await context.newPage()
  watchConsole(page, name)
  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.cm-content')).not.toHaveAttribute('aria-readonly', 'true', { timeout: 15_000 })
  return page
}

test.setTimeout(240_000)

test('demo: concurrent editing, working offline and rewinding a version', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Q3 Product Roadmap')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await anna.locator('.cm-content').click()
  await anna.keyboard.type(
    'The goal this quarter is to shorten integration rollout time.',
    { delay: TYPING_DELAY },
  )
  await expect(marek.locator('.cm-content')).toContainText('integration', { timeout: 10_000 })
  await marek.waitForTimeout(600)

  await moveToEnd(marek)
  await marek.keyboard.type(' Stabilising synchronisation remains the priority.', {
    delay: TYPING_DELAY,
  })
  await expect(anna.locator('.cm-content')).toContainText('Stabilising', { timeout: 10_000 })
  await anna.waitForTimeout(900)

  expectOfflineErrors(marek, true)
  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
  await marek.waitForTimeout(600)
  await moveToEnd(marek)
  await marek.keyboard.type(' This part was written with no network.', { delay: TYPING_DELAY })
  await marek.waitForTimeout(800)

  await moveToEnd(anna)
  await anna.keyboard.type(' And this one at the same time, online.', { delay: TYPING_DELAY })
  await anna.waitForTimeout(700)

  await marek.context().setOffline(false)
  await expect(anna.locator('.cm-content')).toContainText('no network', { timeout: 30_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 30_000 })
  expectOfflineErrors(marek, false)
  await anna.waitForTimeout(1200)

  await anna.getByRole('button', { name: 'Change history', exact: true }).click()
  await anna.locator('.history-entry').last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Loading…', {
    timeout: 10_000,
  })
  await anna.waitForTimeout(1200)
  await anna.getByRole('button', { name: 'Restore this version' }).click()

  await expect(marek.locator('.cm-content')).not.toContainText('no network', { timeout: 20_000 })
  await anna.waitForTimeout(1500)
  expectDocumentUnavailableErrors(anna, documentId, false)
  expectDocumentUnavailableErrors(marek, documentId, false)

  await anna.context().close()
  await marek.context().close()
})

async function select(page: Page, from: number, count: number): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < from; i++) await page.keyboard.press('ArrowRight')
  for (let i = 0; i < count; i++) await page.keyboard.press('Shift+ArrowRight')
}

const DEMO_TITLE = 'Meeting notes'

test('demo: formatting, comments and the bin', async ({ browser, request }) => {
  const documentId = await freshDocument(request, DEMO_TITLE)
  const anna = await joinAs(browser, 'Anna', documentId, 'product')
  const marek = await joinAs(browser, 'Marek', documentId, 'product', false)

  await anna.locator('.cm-content').click()
  await anna.keyboard.type('Decisions: rollout on Friday, review on Monday.', {
    delay: TYPING_DELAY,
  })
  await expect(marek.locator('.cm-content')).toContainText('Monday', { timeout: 10_000 })

  await select(anna, 11, 17)
  await anna.getByRole('button', { name: 'Bold', exact: true }).click()
  await expect(marek.locator('.cm-content .cm-mark-bold').first())
    .toBeVisible({ timeout: 10_000 })
  await marek.waitForTimeout(900)

  await select(marek, 20, 0)
  await marek.keyboard.type(' early', { delay: TYPING_DELAY })
  await expect(anna.locator('.cm-content .cm-mark-bold', { hasText: 'early' }))
    .toBeVisible({ timeout: 10_000 })
  await anna.waitForTimeout(1200)

  await marek.locator('.editor-toolbar button[aria-label="Comments"]').click()
  await select(marek, 0, 10)
  await marek.locator('.comment-draft').fill('Is Friday really realistic?')
  await marek.waitForTimeout(500)
  await marek.getByRole('button', { name: 'Comment', exact: true }).click()
  await expect(anna.locator('.cm-mark-comment')).toBeVisible({ timeout: 10_000 })
  await anna.locator('.editor-toolbar button[aria-label="Comments"]').click()
  await expect(anna.locator('.comment-thread')).toContainText('realistic', { timeout: 15_000 })
  await anna.waitForTimeout(1400)

  await select(anna, 0, 10)
  await anna.keyboard.press('Backspace')
  await expect(anna.locator('.comment-thread.detached')).toBeVisible({ timeout: 15_000 })
  await anna.waitForTimeout(1500)

  const row = anna.locator('.sb-row').filter({ has: anna.locator('.sb-item.active') })
  await row.hover()
  expectDocumentUnavailableErrors(anna, documentId, true)
  expectDocumentUnavailableErrors(marek, documentId, true)
  await row.locator('.sb-delete').click()
  await expect(anna.locator('.sb-item.active')).toHaveCount(0, { timeout: 10_000 })
  await anna.waitForTimeout(800)

  await anna.locator('.sb-trash-toggle').click()
  const trashed = anna.locator('.sb-row', { hasText: DEMO_TITLE }).last()
  await trashed.hover()
  await trashed.getByRole('button', { name: /Restore/ }).click()
  await expect(
    anna.locator('.sb-item:not(.deleted)', { hasText: DEMO_TITLE }).last(),
  ).toBeVisible({ timeout: 10_000 })
  await anna.waitForTimeout(1500)
  expectDocumentUnavailableErrors(anna, documentId, false)
  expectDocumentUnavailableErrors(marek, documentId, false)

  await anna.context().close()
  await marek.context().close()
})

async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  return sharedFreshDocument(request, title)
}
