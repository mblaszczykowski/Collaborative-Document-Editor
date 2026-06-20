import { expect, test } from '@playwright/test'
import { APP, joinAs, typeInto } from './helpers'

/**
 * Sharing a document (CP21) in a real browser.
 *
 * The server-side integration tests (`AccessIT`) check that the rule is right: who may grant a
 * role, what cannot be granted, and when an invitation stops working. What they cannot check is
 * the thing tested here — **whether that rule can be used at all**. Until CP21 it could not:
 * `grant` and `revoke` existed for the tests only, and every document created through the
 * interface was editable by anybody signed in, so no role except owner was reachable in the
 * product.
 *
 * That is why the first scenario starts from a document created **in the browser** rather than
 * through `freshDocument`: the fixture switches visibility to `link`, which bypasses exactly the
 * mechanism under examination here.
 */

/** A fresh user and their own (private) document. */
async function ownerWithDocument(browser: Parameters<typeof joinAs>[0], name: string) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(APP)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  // An empty document list ends with the first one being created — and since CP21 it is private.
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

test('an invitation lets somebody in with exactly the role it carries', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')
  await typeInto(anna, "Anna's text. ")

  await anna.getByRole('button', { name: 'Share' }).click()
  const panel = anna.getByRole('dialog', { name: 'Share this document' })
  await expect(panel).toBeVisible()

  await panel.getByRole('combobox').first().selectOption('commenter')
  await panel.getByRole('button', { name: 'Create link' }).click()

  // The link is shown once — the server keeps only its hash. We read it from the field rather
  // than from the clipboard: clipboard access in Playwright needs browser permissions, and that
  // would be testing browser permissions rather than sharing.
  const link = await panel.locator('input').first().inputValue()
  expect(link).toContain('invite=')

  const bartek = await browser.newContext()
  const bartekPage = await bartek.newPage()
  await bartekPage.goto(link)
  await bartekPage.getByPlaceholder('Your name').fill('Bartek')
  await bartekPage.getByRole('button', { name: 'Get started' }).click()

  // The access is real: he can see Anna's text.
  await expect(bartekPage.locator('.cm-content')).toContainText("Anna's text.", {
    timeout: 15_000,
  })
  // And it is EXACTLY the role the invitation carried — commenter, not editor.
  await expect(bartekPage.locator('.role-badge')).toHaveText('You can comment')

  await anna.context().close()
  await bartek.close()
})

test('without an invitation the address alone lets nobody in', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')
  const url = anna.url()

  const stranger = await browser.newContext()
  const strangerPage = await stranger.newPage()
  await strangerPage.goto(url)
  await strangerPage.getByPlaceholder('Your name').fill('Stranger')
  await strangerPage.getByRole('button', { name: 'Get started' }).click()

  // The same screen as for a deleted document — the server answers 404 to "no access" and to
  // "does not exist" identically, on purpose.
  await expect(strangerPage.locator('.unavailable-banner')).toBeVisible({ timeout: 15_000 })

  await anna.context().close()
  await stranger.close()
})

test('raising a role takes effect without a reload', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')

  await anna.getByRole('button', { name: 'Share' }).click()
  const panel = anna.getByRole('dialog', { name: 'Share this document' })
  await panel.getByRole('combobox').first().selectOption('viewer')
  await panel.getByRole('button', { name: 'Create link' }).click()
  const link = await panel.locator('input').first().inputValue()
  await panel.getByRole('button', { name: 'Close' }).click()

  const bartek = await browser.newContext()
  const bartekPage = await bartek.newPage()
  await bartekPage.goto(link)
  await bartekPage.getByPlaceholder('Your name').fill('Bartek')
  await bartekPage.getByRole('button', { name: 'Get started' }).click()
  await expect(bartekPage.locator('.role-badge')).toHaveText('Read-only', {
    timeout: 15_000,
  })

  // Anna raises his role. This is the thing the README listed as open: the role arrived once,
  // with the initial state, so granting permissions did NOTHING until a reload — which is to
  // say a working feature looked broken.
  await anna.getByRole('button', { name: 'Share' }).click()
  const reopened = anna.getByRole('dialog', { name: 'Share this document' })
  const member = reopened.locator('.share-list li', { hasText: 'Bartek' })
  await member.getByRole('combobox').selectOption('editor')

  // With no refresh: the role badge disappears, because the role stopped restricting anything.
  await expect(bartekPage.locator('.role-badge')).toHaveCount(0, { timeout: 15_000 })

  await anna.context().close()
  await bartek.close()
})
