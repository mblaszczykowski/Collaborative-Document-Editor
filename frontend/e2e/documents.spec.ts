import { expect, test, deleteDocument, freshDocument, joinAs, typeInto } from './helpers'

/**
 * The document list (CP11) on a real browser.
 *
 * The server-side integration tests check that the catalog returns the right data. This is about
 * something else: whether a user can USE that data — whether a rename reaches the list, whether a
 * deleted document disappears, and whether it can be recovered when the deletion was a mistake.
 *
 * <b>Titles have to be unique between runs.</b> Unlike the concurrency scenarios, where a fresh
 * document provides isolation, here the thing under test is the LIST — and it shows everything ever
 * created. The first version of these tests used fixed titles and passed exactly once: on the
 * second run the selector hit two rows with the same name and failed on ambiguity.
 */

/** A per-run unique marker — see the comment above the file. */
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
  // The address has to match what is open — otherwise reloading the page returns to a different
  // document from the one the user is looking at.
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

  // A class selector rather than an accessible name: `name: 'Bin'` matches BY SUBSTRING, so it
  // also hit documents named "For the bin" and failed on ambiguity.
  await page.locator('.sb-trash-toggle').click()
  const trashed = page.locator('.sb-row', { hasText: doomedTitle })
  await expect(trashed).toBeVisible({ timeout: 10_000 })

  await trashed.hover()
  await trashed.getByRole('button', { name: /Restore/ }).click()

  // After a restore the document returns to the main list. We check the NOT struck-through entry,
  // because the bin entry contains the same title.
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

  // Content enters the index through the preview, and that is created at snapshot time — hence
  // retrying the query rather than checking once.
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

  // Delete the document OUTSIDE the browser and visit its address from a clean tab. The server
  // answers 404 — the same as it answers for somebody else's private document.
  await deleteDocument(request, documentId)

  const returning = await joinAs(browser, 'Anna', documentId, { waitForEditor: false })

  // Before this message existed, a refusal ended in a BLANK PAGE: the rest of the interface drew
  // itself, the editor never appeared, and the only trace was a rejected Promise in the console. A
  // symptom indistinguishable from a crash — and a regression introduced along with authorization.
  await expect(returning.locator('.unavailable-banner')).toBeVisible({ timeout: 15_000 })
  await expect(returning.locator('.cm-content')).toHaveCount(0)

  await page.context().close()
  await returning.context().close()
})
