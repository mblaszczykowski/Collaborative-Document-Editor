import { type Page } from '@playwright/test'
import { expect, test, freshDocument, joinAs, typeInto } from './helpers'

async function documentText(page: Page): Promise<string> {
  return page.locator('.cm-content').evaluate((root) => {
    const clone = root.cloneNode(true) as HTMLElement
    clone.querySelectorAll('.remote-caret').forEach((node) => node.remove())
    return clone.textContent ?? ''
  })
}

async function crdtCharacters(page: Page): Promise<string> {
  return (await page.getByTestId('char-count').innerText()).trim()
}

test('two people type at once and see the same text', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Convergence')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'Goal for this quarter')

  await expect(marek.locator('.cm-content')).toContainText('Goal for this quarter', {
    timeout: 10_000,
  })

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' and the next', { delay: 25 })

  await expect(anna.locator('.cm-content')).toContainText('and the next', { timeout: 10_000 })

  expect(await documentText(anna)).toBe(await documentText(marek))
  expect(await crdtCharacters(anna)).toBe(await crdtCharacters(marek))

  await anna.context().close()
  await marek.context().close()
})

test("the other person's cursor is visible in the text", async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Cursors')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'shared text')
  await expect(marek.locator('.cm-content')).toContainText('shared text', { timeout: 10_000 })

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('Home')
  await marek.keyboard.press('ArrowRight')

  await expect(anna.locator('.remote-caret-flag')).toContainText('Marek', { timeout: 10_000 })

  await anna.context().close()
  await marek.context().close()
})

test('cutting the network does not block typing, and the changes merge on return', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, 'Offline')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'base. ')
  await expect(marek.locator('.cm-content')).toContainText('base.', { timeout: 10_000 })

  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type('offline', { delay: 25 })

  await expect(marek.locator('.cm-content')).toContainText('offline')
  await expect(anna.locator('.cm-content')).not.toContainText('offline')

  await anna.locator('.cm-content').click()
  await anna.keyboard.press('End')
  await anna.keyboard.type('online', { delay: 25 })

  await marek.context().setOffline(false)

  await expect(anna.locator('.cm-content')).toContainText('offline', { timeout: 30_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 30_000 })

  expect(await documentText(anna)).toBe(await documentText(marek))
  expect(await crdtCharacters(anna)).toBe(await crdtCharacters(marek))

  await anna.context().close()
  await marek.context().close()
})

test('history lets the document be rewound to an earlier version', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'History')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'good version')
  await expect(anna.getByTestId('char-count')).toContainText('12 characters')
  await marek.waitForTimeout(500)

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' SPOILED', { delay: 25 })
  await expect(anna.getByTestId('char-count')).toContainText('20 characters')
  await anna.waitForTimeout(500)

  const entries = anna.locator('.history-entry')
  await expect(entries.first()).toBeVisible({ timeout: 10_000 })

  await entries.last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Loading…', {
    timeout: 10_000,
  })

  await anna.getByRole('button', { name: 'Restore this version' }).click()

  await expect(anna.locator('.cm-content')).not.toContainText('SPOILED', { timeout: 15_000 })
  expect(await documentText(anna)).toContain('good version')

  await expect(marek.locator('.cm-content')).not.toContainText('SPOILED', { timeout: 15_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  await anna.context().close()
  await marek.context().close()
})

test('operations survive a page reload and arrive once it is back', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, 'Durable buffer')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'beginning ')
  await expect(marek.locator('.cm-content')).toContainText('beginning', { timeout: 10_000 })

  await marek.context().setOffline(true)
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('Control+End')
  await marek.keyboard.type('buffered', { delay: 25 })
  await expect(anna.locator('.cm-content')).not.toContainText('buffered')

  await marek.context().setOffline(false)
  await marek.reload()
  await expect(marek.locator('.editor-host')).toBeVisible({ timeout: 15_000 })

  await expect(anna.locator('.cm-content')).toContainText('buffered', { timeout: 20_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  await anna.context().close()
  await marek.context().close()
})

test("undo takes back one's own change but not somebody else's", async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Undo')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, "Anna's sentence. ")
  await expect(marek.locator('.cm-content')).toContainText("Anna's", { timeout: 10_000 })

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('ControlOrMeta+a')
  await marek.keyboard.press('ArrowRight')
  await marek.keyboard.type("Marek's sentence.", { delay: 25 })
  await expect(anna.locator('.cm-content')).toContainText("Marek's", { timeout: 10_000 })

  await anna.locator('.cm-content').click()
  await anna.keyboard.press('ControlOrMeta+z')

  await expect(anna.locator('.cm-content')).not.toContainText("Anna's", { timeout: 10_000 })
  await expect(anna.locator('.cm-content')).toContainText("Marek's")

  await expect(marek.locator('.cm-content')).not.toContainText("Anna's", { timeout: 10_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  await anna.keyboard.press('ControlOrMeta+Shift+z')
  await expect(anna.locator('.cm-content')).toContainText("Anna's", { timeout: 10_000 })

  await anna.context().close()
  await marek.context().close()
})
