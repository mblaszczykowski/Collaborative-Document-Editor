import { expect, test, freshDocument, joinAs, typeInto } from './helpers'

async function selectFromStart(page: import('@playwright/test').Page, count: number) {
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < count; i++) await page.keyboard.press('Shift+ArrowRight')
}

async function openCommentsPanel(page: import('@playwright/test').Page) {
  await page.locator('.editor-toolbar button[aria-label="Comments"]').click()
  await expect(page.locator('.rp-header')).toContainText('Comments')
}

test('a comment created by one person is visible to the other', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, `Conversation-${Date.now()}`)
  const ania = await joinAs(browser, 'Ania', documentId)
  const bartek = await joinAs(browser, 'Bartek', documentId)

  await typeInto(ania, 'a sentence to discuss')
  await expect(bartek.locator('.cm-content')).toContainText('a sentence to discuss', {
    timeout: 10_000,
  })

  await openCommentsPanel(ania)
  await selectFromStart(ania, 10)

  await expect(ania.locator('.comment-quote')).toContainText('a sentence', { timeout: 10_000 })
  await ania.locator('.comment-draft').fill('Is this clear?')
  await ania.getByRole('button', { name: 'Comment', exact: true }).click()

  await expect(ania.locator('.comment-thread')).toContainText('Is this clear?', {
    timeout: 10_000,
  })

  await openCommentsPanel(bartek)
  await expect(bartek.locator('.comment-thread')).toContainText('Is this clear?', {
    timeout: 15_000,
  })

  await expect(bartek.locator('.cm-content .cm-mark-comment')).toBeVisible({ timeout: 10_000 })

  await ania.context().close()
  await bartek.context().close()
})

test('a thread stays after its anchored text is deleted and is marked detached', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, `Anchor-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'fragment to be deleted')
  await openCommentsPanel(page)
  await selectFromStart(page, 8)
  await page.locator('.comment-draft').fill('Does this stay?')
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  await expect(page.locator('.comment-thread')).toContainText('Does this stay?', { timeout : 10_000 })

  await selectFromStart(page, 8)
  await page.keyboard.press('Backspace')
  await expect(page.locator('.cm-content')).toContainText('to be deleted', { timeout: 10_000 })

  await expect(page.locator('.comment-thread')).toContainText('Does this stay?')
  await expect(page.locator('.comment-thread.detached')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.comment-quote').last()).toContainText('fragment')

  await page.context().close()
})

test('resolving a thread hides it from the list, but the conversation stays', async ({ browser, request }) => {
  const documentId = await freshDocument(request, `Thread-${Date.now()}`)
  const page = await joinAs(browser, 'Ania', documentId)

  await typeInto(page, 'text to comment on')
  await openCommentsPanel(page)
  await selectFromStart(page, 4)
  await page.locator('.comment-draft').fill('To be resolved')
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  await expect(page.locator('.comment-thread')).toContainText('To be resolved', { timeout: 10_000 })

  await page.getByRole('button', { name: 'Resolve', exact: true }).click()
  await expect(page.locator('.comment-thread')).toHaveCount(0, { timeout: 10_000 })

  await page.getByRole('button', { name: 'Show resolved' }).click()
  await expect(page.locator('.comment-thread.resolved')).toContainText('To be resolved', {
    timeout: 10_000,
  })

  await page.context().close()
})
