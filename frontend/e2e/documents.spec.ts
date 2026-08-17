import { expect, test, deleteDocument, freshDocument, joinAs, typeInto } from './helpers'

function unique(name: string): string {
  return `${name}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

test('a new document appears on the list and can be opened', async ({ browser, request }) => {
  const documentId = await freshDocument(request, unique('Starting point'))
  const page = await joinAs(browser, 'Anna', documentId)

  await page.getByRole('button', { name: '+ New document' }).click()

  await expect(page.locator('.sb-item.active')).toContainText('Untitled document', {
    timeout: 10_000,
  })
  await expect(page).toHaveURL(/\?doc=/)

  await page.context().close()
})

test('renaming from the list is visible immediately', async ({ browser, request }) => {
  const before = unique('Old name')
  const after = unique('New name')
  const documentId = await freshDocument(request, before)
  const page = await joinAs(browser, 'Anna', documentId)

  await page.locator('.sb-item', { hasText: before }).dblclick()
  const field = page.locator('.sb-rename')
  await expect(field).toBeVisible()
  await field.fill(after)
  await field.press('Enter')

  await expect(page.locator('.sidebar')).toContainText(after, { timeout: 10_000 })
  await expect(page.locator('.sidebar')).not.toContainText(before)

  await page.context().close()
})

test('a deleted document disappears from the list, and the bin restores it', async ({
  browser,
  request,
}) => {
  const doomedTitle = unique('For the bin')
  const doomed = await freshDocument(request, doomedTitle)
  const page = await joinAs(browser, 'Anna', doomed)

  const row = page.locator('.sb-row', { hasText: doomedTitle })
  await row.hover()
  await row.getByRole('button', { name: /Delete/ }).click()

  await expect(page.locator('.sidebar')).not.toContainText(doomedTitle, { timeout: 10_000 })

  await page.locator('.sb-trash-toggle').click()
  const trashed = page.locator('.sb-row', { hasText: doomedTitle })
  await expect(trashed).toBeVisible({ timeout: 10_000 })

  await trashed.hover()
  await trashed.getByRole('button', { name: /Restore/ }).click()

  await expect(
    page.locator('.sb-item:not(.deleted)', { hasText: doomedTitle }),
  ).toBeVisible({ timeout: 10_000 })

  await page.context().close()
})

test('search finds a document by content, not only by title', async ({
  browser,
  request,
}) => {
  const marker = unique('hexadecimal').replace(/-/g, '')
  const title = unique('Title with no marker')
  const documentId = await freshDocument(request, title)
  const page = await joinAs(browser, 'Anna', documentId)

  await typeInto(page, `${marker} `.repeat(12))

  const search = page.locator('.sb-search')
  await expect(async () => {
    await search.fill('')
    await search.fill(marker)
    await expect(page.locator('.sb-item', { hasText: title })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 30_000 })

  await page.context().close()
})

test('a document that cannot be opened says so plainly', async ({ browser, request }) => {
  const title = unique('Will vanish')
  const documentId = await freshDocument(request, title)
  const page = await joinAs(browser, 'Anna', documentId)
  await expect(page.locator('.cm-content')).toBeVisible({ timeout: 15_000 })

  await deleteDocument(request, documentId)

  const returning = await joinAs(browser, 'Anna', documentId, { waitForEditor: false })

  await expect(returning.locator('.unavailable-banner')).toBeVisible({ timeout: 15_000 })
  await expect(returning.locator('.cm-content')).toHaveCount(0)

  await page.context().close()
  await returning.context().close()
})
