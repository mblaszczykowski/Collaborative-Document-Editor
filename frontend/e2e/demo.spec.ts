import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * The demo scenario for the README's GIF recording (CP9).
 *
 * The recording is a **script, not a screen capture of manual clicking**. The reasons:
 * - it can be repeated after every interface change, so the GIF in the README does not silently go
 *   stale,
 * - it is simultaneously a test — if the demo stops working, the run fails instead of producing a
 *   misleading recording,
 * - it shows exactly what CI checks, not a prettier version of events.
 *
 * There are TWO scenarios and they record into separate directories, because two separate GIFs come
 * out of them. One would be either too long for anyone to watch to the end, or too cursory to show
 * anything.
 * - **core** — concurrent editing, working offline, rewinding a version (CP1–CP9),
 * - **product** — formatting, comments, the bin (CP11–CP14).
 *
 * To run: `npm run demo`, then `./tools/make-gif.sh`.
 */

const APP = 'http://localhost:5173'
const API = 'http://localhost:8080/api'

/** Slower than in the tests — a recording should be legible, not fast. */
const TYPING_DELAY = 45

/**
 * Puts the cursor at the END of the document.
 *
 * Not by keyboard shortcut: `Control+End` is not bound to the end of the document on macOS
 * (CodeMirror uses `Cmd` there), so the cursor stayed wherever the click landed — mid-sentence. The
 * recording then showed correctly merged but shredded text, which is the worst possible material
 * for a demo of a merging algorithm.
 *
 * Clicking the edge of a line is not enough either: with wrapping, a `.cm-line` is one LOGICAL line
 * laid out over several visual ones, so you land in the middle of the text.
 *
 * Selecting everything and pressing right collapses the selection to its end — it behaves the same
 * everywhere and does not depend on wrapping.
 */
async function moveToEnd(page: Page): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.press('ArrowRight')
}

/**
 * Opens the document in a separate browser.
 *
 * `record: false` disables recording for that browser. The product scenario uses it so that
 * EXACTLY ONE file is left in the directory — otherwise the GIF assembly script would have to guess
 * which recording is the right one, and the only criterion it has (file size) depends on how much
 * happened in a given window. The second person's view loses nothing: everything Marek does is
 * visible on Anna's side — which is the point of this demo.
 */
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
      ? { dir: `demo-recordings/${scene}`, size: { width: 1280, height: 760 } }
      : undefined,
  })
  const page = await context.newPage()
  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Your name').fill(name)
  await page.getByRole('button', { name: 'Get started' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

// The demo types SLOWER than the tests so the recording is legible — three scenes do not fit in
// the 60 s default timeout meant for tests.
test.setTimeout(240_000)

test('demo: concurrent editing, working offline and rewinding a version', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Q3 Product Roadmap')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  // --- SCENE 1: two people typing in the same paragraph -------------------
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

  // --- SCENE 2: Marek loses the network, keeps typing, comes back ----------
  // A real network cut on the browser — the interface shows it as "Working offline", which is
  // exactly what a user with dead wi-fi sees. No diagnostics panel in the recording.
  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
  await marek.waitForTimeout(600)
  // Focus MUST return to the editor. After clicking the button it remains the active element, and
  // the text typed next starts with a space — which on a button acts as a click and silently
  // restores connectivity. The scene then looked correct right up to the moment where "Restore
  // connectivity" did not exist.
  await moveToEnd(marek)
  await marek.keyboard.type(' This part was written with no network.', { delay: TYPING_DELAY })
  await marek.waitForTimeout(800)

  // Anna types at the same time — once connectivity returns BOTH sides have to be merged.
  await moveToEnd(anna)
  await anna.keyboard.type(' And this one at the same time, online.', { delay: TYPING_DELAY })
  await anna.waitForTimeout(700)

  await marek.context().setOffline(false)
  await expect(anna.locator('.cm-content')).toContainText('no network', { timeout: 30_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 30_000 })
  await anna.waitForTimeout(1200)

  // --- SCENE 3: rewinding to an earlier version ---------------------------
  // History is visible straight away — the side panel no longer has tabs.
  await anna.locator('.history-entry').last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Loading…', {
    timeout: 10_000,
  })
  await anna.waitForTimeout(1200)
  await anna.getByRole('button', { name: 'Restore this version' }).click()

  // The restore comes back over the ordinary operation channel, so BOTH browsers see it.
  await expect(marek.locator('.cm-content')).not.toContainText('no network', { timeout: 20_000 })
  await anna.waitForTimeout(1500)

  await anna.context().close()
  await marek.context().close()
})

/**
 * Selects `count` characters starting at `from`, on a single-line document.
 *
 * By keyboard rather than mouse: a drag depends on character widths and wrapping, so the recording
 * would come out different every time.
 */
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

  // --- SCENE 1: formatting, and typing INSIDE a formatted range ------------
  // This is the crux of CP13 and the only thing about formatting worth showing. Anna bolds a
  // fragment; Marek — not having seen it — types in the middle of it. With formatting stored as a
  // character attribute there would be a hole nobody made.
  await anna.locator('.cm-content').click()
  await anna.keyboard.type('Decisions: rollout on Friday, review on Monday.', {
    delay: TYPING_DELAY,
  })
  await expect(marek.locator('.cm-content')).toContainText('Monday', { timeout: 10_000 })

  await select(anna, 11, 17) // "rollout on Friday"
  await anna.getByRole('button', { name: 'Bold' }).click()
  // `.first()`, because CodeMirror splits a decoration into as many elements as the range splits
  // into blocks in the CRDT — a bare selector hits several at once and fails on ambiguity.
  await expect(marek.locator('.cm-content .cm-mark-bold').first())
    .toBeVisible({ timeout: 10_000 })
  await marek.waitForTimeout(900)

  // Marek puts the cursor INSIDE the bold range and types. The bold should cover the new text
  // rather than leave a hole in itself — that is the whole difference between a mark as a RANGE
  // BETWEEN ANCHORS and formatting stored as a character attribute (ADR-0010).
  await select(marek, 20, 0)
  await marek.keyboard.type(' early', { delay: TYPING_DELAY })
  // Filtering by content rather than `.cm-mark-bold` alone: CodeMirror splits a decoration into
  // several elements, so a bare selector hits several at once and the assertion fails on ambiguity.
  await expect(anna.locator('.cm-content .cm-mark-bold', { hasText: 'early' }))
    .toBeVisible({ timeout: 10_000 })
  await anna.waitForTimeout(1200)

  // --- SCENE 2: a comment — the anchor goes instantly, the conversation over REST ---
  await marek.locator('.editor-toolbar button[aria-label="Comments"]').click()
  await select(marek, 0, 10)
  await marek.locator('.comment-draft').fill('Is Friday really realistic?')
  await marek.waitForTimeout(500)
  await marek.getByRole('button', { name: 'Comment' }).click()

  // The highlight on the anchored fragment appears for Anna WITHOUT a refresh — an anchor is an
  // ordinary CRDT operation, so it travels over the same channel as the text.
  await expect(anna.locator('.cm-mark-comment')).toBeVisible({ timeout: 10_000 })
  await anna.locator('.editor-toolbar button[aria-label="Comments"]').click()
  await expect(anna.locator('.comment-thread')).toContainText('realistic', { timeout: 15_000 })
  await anna.waitForTimeout(1400)

  // --- SCENE 3: deleting the anchored text does NOT delete the conversation ---
  await select(anna, 0, 10)
  await anna.keyboard.press('Backspace')
  await expect(anna.locator('.comment-thread.detached')).toBeVisible({ timeout: 15_000 })
  await anna.waitForTimeout(1500)

  // --- SCENE 4: the bin — deletion is soft and reversible ------------------
  // The row is identified by the OPEN document (`.sb-item.active`) rather than by title: the
  // database lives between runs, so the same title can appear on the list several times and a
  // name-based selector fails on ambiguity. The same bug already showed up in CP11.
  const row = anna.locator('.sb-row').filter({ has: anna.locator('.sb-item.active') })
  await row.hover()
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

  await anna.context().close()
  await marek.context().close()
})

async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post(`${API}/documents`, { data: { title } })
  return (await response.json()).id as string
}
