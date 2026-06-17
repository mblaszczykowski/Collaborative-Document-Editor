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
 * <p>Scenariuszy są DWA i nagrywają się do osobnych katalogów, bo powstają z nich dwa
 * osobne GIF-y. Jeden byłby albo za długi, żeby ktokolwiek obejrzał go do końca, albo
 * za pobieżny, żeby cokolwiek pokazać.
 * <ul>
 *   <li><b>rdzeń</b> — współbieżna edycja, praca offline, cofnięcie wersji (CP1–CP9),</li>
 *   <li><b>produkt</b> — formatowanie, komentarze, kosz (CP11–CP14).</li>
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

/**
 * Otwiera dokument w osobnej przeglądarce.
 *
 * `record: false` wyłącza nagrywanie tej przeglądarki. Scenariusz produktowy korzysta z tego,
 * żeby w katalogu został DOKŁADNIE JEDEN plik — inaczej skrypt składający GIF-a musiałby
 * zgadywać, które nagranie jest tym właściwym, a jedyne kryterium, jakie ma (rozmiar pliku),
 * zależy od tego, ile się w danym oknie działo. Widok drugiej osoby nic nie traci: wszystko,
 * co robi Marek, widać u Anny — o to w tym demie chodzi.
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
  await anna.getByRole('button', { name: 'Restore this version' }).click()

  // Przywrócenie wraca zwykłym kanałem operacji, więc widzą je OBIE przeglądarki.
  await expect(marek.locator('.cm-content')).not.toContainText('bez sieci', { timeout: 20_000 })
  await anna.waitForTimeout(1500)

  await anna.context().close()
  await marek.context().close()
})

/**
 * Zaznacza `count` znaków, licząc od `from`, na dokumencie jednolinijkowym.
 *
 * Klawiszami, a nie myszą: przeciągnięcie zależy od szerokości znaków i zawijania,
 * więc nagranie wychodziłoby za każdym razem inne.
 */
async function select(page: Page, from: number, count: number): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.press('Home')
  for (let i = 0; i < from; i++) await page.keyboard.press('ArrowRight')
  for (let i = 0; i < count; i++) await page.keyboard.press('Shift+ArrowRight')
}

const DEMO_TITLE = 'Notatka ze spotkania'

test('demo: formatowanie, komentarze i kosz', async ({ browser, request }) => {
  const documentId = await freshDocument(request, DEMO_TITLE)
  const anna = await joinAs(browser, 'Anna', documentId, 'product')
  const marek = await joinAs(browser, 'Marek', documentId, 'product', false)

  // --- SCENA 1: formatowanie i pisanie W ŚRODKU sformatowanego zakresu ------
  // To jest sedno CP13 i jedyna rzecz, którą warto z formatowania pokazać.
  // Anna pogrubia fragment; Marek — nie widząc tego — dopisuje w jego środku.
  // Przy formatowaniu zapisanym jako atrybut znaku powstałaby dziura, której nikt nie zrobił.
  await anna.locator('.cm-content').click()
  await anna.keyboard.type('Ustalenia: wdrozenie w piatek, przeglad w poniedzialek.', {
    delay: TYPING_DELAY,
  })
  await expect(marek.locator('.cm-content')).toContainText('poniedzialek', { timeout: 10_000 })

  await select(anna, 11, 18) // „wdrozenie w piatek”
  await anna.getByRole('button', { name: 'Pogrubienie' }).click()
  // `.first()`, bo CodeMirror rozbija dekorację na tyle elementów, na ile bloków dzieli się
  // zakres w CRDT — goły selektor trafia w kilka naraz i pada na niejednoznaczności.
  await expect(marek.locator('.cm-content .cm-mark-bold').first())
    .toBeVisible({ timeout: 10_000 })
  await marek.waitForTimeout(900)

  // Marek stawia kursor W ŚRODKU pogrubienia i dopisuje. Pogrubienie ma objąć nowy tekst,
  // a nie zostawić w sobie dziury — to jest cała różnica między znacznikiem jako ZAKRESEM
  // MIĘDZY KOTWICAMI a formatowaniem zapisanym jako atrybut znaku (ADR-0010).
  await select(marek, 20, 0)
  await marek.keyboard.type(' rano', { delay: TYPING_DELAY })
  // Filtr po treści, nie samo `.cm-mark-bold`: CodeMirror dzieli dekorację na kilka
  // elementów, więc goły selektor trafia w kilka naraz i asercja pada na niejednoznaczności.
  await expect(anna.locator('.cm-content .cm-mark-bold', { hasText: 'rano' }))
    .toBeVisible({ timeout: 10_000 })
  await anna.waitForTimeout(1200)

  // --- SCENA 2: komentarz — kotwica idzie natychmiast, rozmowa REST-em ------
  await marek.locator('.editor-toolbar button[aria-label="Komentarze"]').click()
  await select(marek, 0, 10)
  await marek.locator('.comment-draft').fill('Czy piatek na pewno jest realny?')
  await marek.waitForTimeout(500)
  await marek.getByRole('button', { name: 'Skomentuj' }).click()

  // Podświetlenie zakotwiczonego fragmentu pojawia się u Anny BEZ odświeżania —
  // kotwica to zwykła operacja CRDT, więc idzie tym samym kanałem co tekst.
  await expect(anna.locator('.cm-mark-comment')).toBeVisible({ timeout: 10_000 })
  await anna.locator('.editor-toolbar button[aria-label="Komentarze"]').click()
  await expect(anna.locator('.comment-thread')).toContainText('realny', { timeout: 15_000 })
  await anna.waitForTimeout(1400)

  // --- SCENA 3: skasowanie zakotwiczonego tekstu NIE kasuje rozmowy --------
  await select(anna, 0, 10)
  await anna.keyboard.press('Backspace')
  await expect(anna.locator('.comment-thread.detached')).toBeVisible({ timeout: 15_000 })
  await anna.waitForTimeout(1500)

  // --- SCENA 4: kosz — usunięcie jest miękkie i odwracalne -----------------
  // Wiersz wskazywany przez OTWARTY dokument (`.sb-item.active`), a nie po tytule:
  // baza żyje między przebiegami, więc ten sam tytuł potrafi być na liście kilka razy
  // i selektor po nazwie pada na niejednoznaczności. Ten sam błąd wyszedł już w CP11.
  const row = anna.locator('.sb-row').filter({ has: anna.locator('.sb-item.active') })
  await row.hover()
  await row.locator('.sb-delete').click()
  await expect(anna.locator('.sb-item.active')).toHaveCount(0, { timeout: 10_000 })
  await anna.waitForTimeout(800)

  await anna.locator('.sb-trash-toggle').click()
  const trashed = anna.locator('.sb-row', { hasText: DEMO_TITLE }).last()
  await trashed.hover()
  await trashed.getByRole('button', { name: /Przywróć/ }).click()
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
