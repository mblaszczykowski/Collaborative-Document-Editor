import { expect, test, freshDocument, joinAs, expectOfflineErrors } from './helpers'

test('first production visit can reload offline and preserve unsent edits', async ({ browser, request }) => {
  test.skip(process.env.E2E_PRODUCTION !== 'true', 'Service worker is enabled only in production builds')
  const id = await freshDocument(request, 'First visit offline shell')
  const page = await joinAs(browser, 'Offline reviewer', id)
  await page.locator('.cm-content').fill('Cached original')
  await expect(page.locator('.save-state')).toContainText('Saved')
  await page.waitForFunction(async () => {
    await navigator.serviceWorker.ready
    const keys = await (await caches.open('collabedit-public-shell-v1')).keys()
    return keys.some(key => /\/assets\/Editor-/.test(key.url))
  })
  expectOfflineErrors(page, true)
  await page.context().setOffline(true)
  await expect(page.locator('.save-state.offline')).toBeVisible()
  await page.locator('.cm-content').fill('Cached original and unsent draft')
  await page.reload()
  await expect(page.locator('.cm-content')).toContainText('Cached original and unsent draft')
  await expect(page.locator('.cm-content')).toHaveAttribute('contenteditable', 'true')
  await page.context().setOffline(false)
  await expect(page.locator('.save-state')).toContainText('Saved', { timeout: 30_000 })
  expectOfflineErrors(page, false)
  await page.context().close()
})
