import { expect, test, APP, joinAs, typeInto } from './helpers'

async function ownerWithDocument(browser: Parameters<typeof joinAs>[0], name: string) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(APP)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })

  await page.getByRole('button', { name: '+ New document' }).click()
  await page.waitForURL(/[?&]doc=/, { timeout : 15_000 })
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

test('an invitation lets somebody in with exactly the role it carries', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')
  await typeInto(anna, "Anna's text. ")

  await anna.getByRole('button', { name: 'Share', exact: true }).click()
  const panel = anna.getByRole('dialog', { name: 'Share this document' })
  await expect(panel).toBeVisible()

  await panel.getByRole('combobox').first().selectOption('commenter')
  await panel.getByRole('button', { name: 'Create link' }).click()

  const link = await panel.locator('input').first().inputValue()
  expect(link).toContain('invite=')

  const bartek = await browser.newContext()
  const bartekPage = await bartek.newPage()
  await bartekPage.goto(link)
  await bartekPage.getByPlaceholder('Your name').fill('Bartek')
  await bartekPage.getByRole('button', { name: 'Get started' }).click()

  await expect(bartekPage.locator('.cm-content')).toContainText("Anna's text.", {
    timeout: 15_000,
  })
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

  await expect(strangerPage.locator('.unavailable-banner')).toBeVisible({ timeout: 15_000 })

  await anna.context().close()
  await stranger.close()
})

test('raising a role takes effect without a reload', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')

  await anna.getByRole('button', { name: 'Share', exact: true }).click()
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

  await anna.getByRole('button', { name: 'Share', exact: true }).click()
  const reopened = anna.getByRole('dialog', { name: 'Share this document' })
  const member = reopened.locator('.share-list li', { hasText: 'Bartek' })
  await member.getByRole('combobox').selectOption('editor')

  await expect(bartekPage.locator('.role-badge')).toHaveCount(0, { timeout: 15_000 })

  await anna.context().close()
  await bartek.close()
})
