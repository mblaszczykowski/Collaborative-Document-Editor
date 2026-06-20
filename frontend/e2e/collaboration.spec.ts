import { expect, test, type Page } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

/**
 * The end-to-end test on real browsers.
 *
 * The server-side integration tests (CP3) prove the backend merges correctly. The property tests
 * (CP2) prove the algorithm converges. Neither answers the question of whether **what the user
 * sees** matches what is in the model — and between the CRDT and the DOM sit CodeMirror and the
 * whole bridging layer (ADR-0006).
 *
 * The same scenario drives the demo recording in CP9. One implementation, two uses: that way the
 * GIF in the README shows exactly what CI checks rather than a hand-reconstructed version of
 * events.
 */

/**
 * The document's text alone, without the cursor flags.
 *
 * Other people's cursors are widgets INSIDE `.cm-content`, so `innerText` returns their names too.
 * The first version of this test compared raw `innerText` and failed even though the document was
 * identical on both sides — it was comparing cursor placement, not content.
 */
async function documentText(page: Page): Promise<string> {
  return page.locator('.cm-content').evaluate((root) => {
    const clone = root.cloneNode(true) as HTMLElement
    clone.querySelectorAll('.remote-caret').forEach((node) => node.remove())
    return clone.textContent ?? ''
  })
}

/**
 * The character count according to the CRDT (from the status bar), not according to the DOM.
 *
 * Comparing this value between browsers checks convergence one level deeper than comparing text: if
 * the model and the view diverged, the text could look the same while the models no longer did.
 */
async function crdtCharacters(page: Page): Promise<string> {
  // `data-testid`, not a CSS class: the counter is a read of the CRDT state, so the test should
  // hold on to its identity rather than its styling — otherwise every restyle of the status bar
  // breaks tests for reasons unrelated to behaviour.
  return (await page.getByTestId('char-count').innerText()).trim()
}

test('two people type at once and see the same text', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Convergence')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'Goal for this quarter')

  // Marek has to see Anna's text without reloading the page.
  await expect(marek.locator('.cm-content')).toContainText('Goal for this quarter', {
    timeout: 10_000,
  })

  // Now both people append — to DIFFERENT places, but in the same paragraph.
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' and the next', { delay: 25 })

  await expect(anna.locator('.cm-content')).toContainText('and the next', { timeout: 10_000 })

  // The crux: both sides have IDENTICAL text. Not "similar", not "containing both changes".
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

  // The name flag — the part of the demo you notice at a glance.
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

  // A REAL network cut on the browser — it also tears down the WebSocket, so the test covers
  // reconnection and resynchronisation, not just a paused send.
  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type('offline', { delay: 25 })

  await expect(marek.locator('.cm-content')).toContainText('offline')
  // Anna does NOT see Marek's changes yet — proof that the cut actually works, rather than the
  // simulator being a stub.
  await expect(anna.locator('.cm-content')).not.toContainText('offline')

  // Anna types her own meanwhile, so once connectivity returns BOTH sides have to be merged.
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
  // TWO authors, not one: the timeline groups an author's consecutive changes into one entry (a
  // 2-minute window), so if Anna wrote both fragments there would be ONE version and "rewind to
  // the previous one" would have nothing to return to. The first version of this test looked
  // exactly like that and missed the point.
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'good version')
  // Waiting for the write acknowledgement: history reads the operation log, so an entry appears
  // only after the write and not after the broadcast alone (ADR-0003).
  await expect(anna.getByTestId('char-count')).toContainText('12 characters')
  await marek.waitForTimeout(500)

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' SPOILED', { delay: 25 })
  await expect(anna.getByTestId('char-count')).toContainText('20 characters')
  await anna.waitForTimeout(500)

  // History is visible straight away — the side panel has no tabs, because there is nothing left
  // to switch between: the CRDT state view has left the user interface.
  const entries = anna.locator('.history-entry')
  await expect(entries.first()).toBeVisible({ timeout: 10_000 })

  // The entries are newest first; the last one is the oldest state.
  await entries.last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Loading…', {
    timeout: 10_000,
  })

  await anna.getByRole('button', { name: 'Restore this version' }).click()

  // The restore comes back over the ordinary operation channel — the editor should update itself
  // without a page reload. That is the crux of the decision in ADR-0003, point 6.
  await expect(anna.locator('.cm-content')).not.toContainText('SPOILED', { timeout: 15_000 })
  expect(await documentText(anna)).toContain('good version')

  // The restore comes back over the ordinary operation channel, so the SECOND browser should see
  // it without a reload too — that is the whole thesis of ADR-0003, point 6.
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

  // A REAL network cut on the browser, not the in-app simulator. The simulator only held back the
  // sending of operations; `setOffline` also tears down the WebSocket, so the test covers the full
  // path: connectivity lost -> local work -> reconnect -> resynchronisation. That is the same road
  // a user with dead wi-fi travels.
  await marek.context().setOffline(true)
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('Control+End')
  await marek.keyboard.type('buffered', { delay: 25 })
  await expect(anna.locator('.cm-content')).not.toContainText('buffered')

  // ...and CLOSES THE TAB by reloading. An in-memory buffer would be lost at that moment along
  // with the whole page state — which is why unacknowledged operations are also in IndexedDB.
  await marek.context().setOffline(false)
  await marek.reload()
  await expect(marek.locator('.editor-host')).toBeVisible({ timeout: 15_000 })

  // Once the page is rebuilt the buffer is loaded BEFORE resynchronisation, so the operations
  // reach the server and from there Anna — even though they were created in another browser
  // session.
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

  // Anna undoes. It takes back HER sentence, and Marek's has to stay untouched — an undo on the
  // text (CodeMirror's history) would take back both and delete somebody else's work.
  await anna.locator('.cm-content').click()
  await anna.keyboard.press('ControlOrMeta+z')

  await expect(anna.locator('.cm-content')).not.toContainText("Anna's", { timeout: 10_000 })
  await expect(anna.locator('.cm-content')).toContainText("Marek's")

  // An undo is an ordinary operation, so it reaches the other browser too.
  await expect(marek.locator('.cm-content')).not.toContainText("Anna's", { timeout: 10_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  // Redo brings the removed text back.
  await anna.keyboard.press('ControlOrMeta+Shift+z')
  await expect(anna.locator('.cm-content')).toContainText("Anna's", { timeout: 10_000 })

  await anna.context().close()
  await marek.context().close()
})
