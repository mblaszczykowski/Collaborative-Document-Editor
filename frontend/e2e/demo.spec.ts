import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Scenariusz demo do nagrania GIF-a w README (CP9).
 *
 * <p>Nagranie jest <b>skryptem, nie zrzutem ekranu z ręcznego klikania</b>. Powody:
 * <ul>
 *   <li>da się je powtórzyć po każdej zmianie interfejsu, więc GIF w README nie zestarzeje się
 *       po cichu,</li>
 *   <li>jest jednocześnie testem — jeśli demo przestanie działać, przebieg padnie,
 *       zamiast wyprodukować mylące nagranie,</li>
 *   <li>pokazuje dokładnie to, co sprawdza CI, a nie ładniejszą wersję zdarzeń.</li>
 * </ul>
 *
 * Uruchomienie: {@code npm run demo}, potem {@code ./tools/make-gif.sh}.
 */

const APP = 'http://localhost:5173'
const API = 'http://localhost:8080/api'

/** Wolniej niż w testach — nagranie ma być czytelne dla oka, nie szybkie. */
const TYPING_DELAY = 45

/**
 * Ustawia kursor na KOŃCU dokumentu.
 *
 * Nie przez skrót klawiszowy: `Control+End` nie jest na macOS związany z końcem dokumentu
 * (CodeMirror używa tam `Cmd`), więc kursor zostawał tam, gdzie trafiło kliknięcie —
 * w środku zdania. Nagranie pokazywało wtedy poprawnie scalony, ale poszatkowany tekst,
 * co jest najgorszym możliwym materiałem na demo algorytmu scalającego.
 *
 * Kliknięcie w krawędź linii też nie wystarcza: przy zawijaniu `.cm-line` to jedna linia
 * LOGICZNA rozłożona na kilka wizualnych, więc trafia się w środek tekstu.
 *
 * Zaznaczenie całości i strzałka w prawo zwija zaznaczenie do jego końca — działa tak samo
 * wszędzie i nie zależy od zawijania.
 */
async function moveToEnd(page: Page): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.press('ArrowRight')
}

async function joinAs(browser: Browser, name: string, documentId: string): Promise<Page> {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 760 },
    recordVideo: { dir: 'demo-recordings', size: { width: 1280, height: 760 } },
  })
  const page = await context.newPage()
  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Twoje imię').fill(name)
  await page.getByRole('button', { name: 'Rozpocznij' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

// Demo pisze WOLNIEJ niż testy, żeby nagranie było czytelne — trzy sceny nie mieszczą się
// w domyślnym limicie 60 s przeznaczonym dla testów.
test.setTimeout(240_000)

test('demo: współbieżna edycja, praca offline i cofnięcie wersji', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Q3 Product Roadmap')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  // --- SCENA 1: dwie osoby piszą w tym samym akapicie ---------------------
  await anna.locator('.cm-content').click()
  await anna.keyboard.type(
    'Celem na ten kwartal jest skrocenie czasu wdrozenia integracji.',
    { delay: TYPING_DELAY },
  )
  await expect(marek.locator('.cm-content')).toContainText('integracji', { timeout: 10_000 })
  await marek.waitForTimeout(600)

  await moveToEnd(marek)
  await marek.keyboard.type(' Priorytetem pozostaje stabilizacja synchronizacji.', {
    delay: TYPING_DELAY,
  })
  await expect(anna.locator('.cm-content')).toContainText('stabilizacja', { timeout: 10_000 })
  await anna.waitForTimeout(900)

  // --- SCENA 2: Marek traci sieć, pisze dalej, wraca ----------------------
  // Prawdziwe odcięcie sieci przeglądarce — w interfejsie widać to jako „Praca offline",
  // czyli dokładnie to, co zobaczy użytkownik z padniętym wi-fi. Żadnego panelu
  // diagnostycznego na nagraniu.
  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
  await marek.waitForTimeout(600)
  // Fokus MUSI wrócić do edytora. Po kliknięciu przycisku zostaje on aktywnym elementem,
  // a wpisywany tekst zaczyna się od spacji — która na przycisku działa jak kliknięcie
  // i po cichu przywraca łączność. Scena wyglądała wtedy poprawnie aż do momentu,
  // w którym „Przywróć łączność" nie istniało.
  await moveToEnd(marek)
  await marek.keyboard.type(' Ten fragment powstal bez sieci.', { delay: TYPING_DELAY })
  await marek.waitForTimeout(800)

  // Anna pisze w tym samym czasie — po powrocie łączności trzeba scalić OBIE strony.
  await moveToEnd(anna)
  await anna.keyboard.type(' A ten w tym samym czasie online.', { delay: TYPING_DELAY })
  await anna.waitForTimeout(700)

  await marek.context().setOffline(false)
  await expect(anna.locator('.cm-content')).toContainText('bez sieci', { timeout: 30_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 30_000 })
  await anna.waitForTimeout(1200)

  // --- SCENA 3: cofnięcie do wcześniejszej wersji -------------------------
  // Historia jest widoczna od razu — panel boczny nie ma już zakładek.
  await anna.locator('.history-entry').last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Odtwarzam', {
    timeout: 10_000,
  })
  await anna.waitForTimeout(1200)
  await anna.getByRole('button', { name: 'Przywróć tę wersję' }).click()

  // Przywrócenie wraca zwykłym kanałem operacji, więc widzą je OBIE przeglądarki.
  await expect(marek.locator('.cm-content')).not.toContainText('bez sieci', { timeout: 20_000 })
  await anna.waitForTimeout(1500)

  await anna.context().close()
  await marek.context().close()
})

async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post(`${API}/documents`, { data: { title } })
  return (await response.json()).id as string
}
