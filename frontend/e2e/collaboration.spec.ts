import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Test end-to-end na prawdziwych przeglądarkach.
 *
 * Testy integracyjne po stronie serwera (CP3) dowodzą, że backend scala poprawnie.
 * Property testy (CP2) dowodzą, że algorytm jest zbieżny. Żadne z nich nie odpowiada
 * na pytanie, czy **to, co widzi użytkownik**, zgadza się z tym, co jest w modelu —
 * a między CRDT a DOM-em jest jeszcze CodeMirror i cała warstwa mostkująca (ADR-0006).
 *
 * Ten sam scenariusz napędza nagranie demo w CP9. Jedna implementacja, dwa zastosowania:
 * dzięki temu GIF w README pokazuje dokładnie to, co jest sprawdzane w CI, a nie ręcznie
 * odtworzoną wersję zdarzeń.
 */

const APP = 'http://localhost:5173'
const API = 'http://localhost:8080/api'

/**
 * Świeży dokument na każdy test.
 *
 * Bez tego wszystkie testy otwierają pierwszy dokument z listy, widzą nawzajem swoje
 * zmiany i padają z powodów niemających nic wspólnego z tym, co sprawdzają. Izolacja
 * przez osobny dokument jest tańsza niż czyszczenie bazy między testami i nie wymaga
 * dostępu do niej z poziomu testu.
 */
async function freshDocument(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post(`${API}/documents`, { data: { title } })
  return (await response.json()).id as string
}

async function joinAs(browser: Browser, name: string, documentId: string): Promise<Page> {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(`${APP}/?doc=${documentId}`)
  await page.getByPlaceholder('Twoje imię').fill(name)
  await page.getByRole('button', { name: 'Wejdź' }).click()
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

async function typeInto(page: Page, text: string): Promise<void> {
  await page.locator('.cm-content').click()
  await page.keyboard.type(text, { delay: 25 })
}

/**
 * Sam tekst dokumentu, bez chorągiewek kursorów.
 *
 * Kursory innych osób są widgetami WEWNĄTRZ `.cm-content`, więc `innerText` zwraca również
 * ich imiona. Pierwsza wersja tego testu porównywała surowy `innerText` i padała, mimo że
 * dokument po obu stronach był identyczny — porównywała rozmieszczenie kursorów, a nie treść.
 */
async function documentText(page: Page): Promise<string> {
  return page.locator('.cm-content').evaluate((root) => {
    const clone = root.cloneNode(true) as HTMLElement
    clone.querySelectorAll('.remote-caret').forEach((node) => node.remove())
    return clone.textContent ?? ''
  })
}

/**
 * Liczba znaków według CRDT (z paska stanu), a nie według DOM-u.
 *
 * Porównanie tej wartości między przeglądarkami sprawdza zbieżność o poziom głębiej niż
 * porównanie tekstu: gdyby model i widok się rozjechały, tekst mógłby wyglądać tak samo,
 * a modele już nie.
 */
async function crdtCharacters(page: Page): Promise<string> {
  return (await page.locator('.statusbar .metric').first().innerText()).trim()
}

test('dwie osoby piszą jednocześnie i widzą ten sam tekst', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Zbieżność')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'Cel na ten kwartal')

  // Marek musi zobaczyć tekst Anny bez odświeżania strony.
  await expect(marek.locator('.cm-content')).toContainText('Cel na ten kwartal', {
    timeout: 10_000,
  })

  // Teraz obie osoby dopisują — do RÓŻNYCH miejsc, ale w tym samym akapicie.
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' i nastepny', { delay: 25 })

  await expect(anna.locator('.cm-content')).toContainText('i nastepny', { timeout: 10_000 })

  // Sedno: obie strony mają IDENTYCZNY tekst. Nie „podobny", nie „zawierający obie zmiany".
  expect(await documentText(anna)).toBe(await documentText(marek))
  expect(await crdtCharacters(anna)).toBe(await crdtCharacters(marek))

  await anna.context().close()
  await marek.context().close()
})

test('kursor drugiej osoby jest widoczny w tekście', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Kursory')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'tekst wspolny')
  await expect(marek.locator('.cm-content')).toContainText('tekst wspolny', { timeout: 10_000 })

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('Home')
  await marek.keyboard.press('ArrowRight')

  // Chorągiewka z imieniem — to jest ta część demo, którą widać na pierwszy rzut oka.
  await expect(anna.locator('.remote-caret-flag')).toContainText('Marek', { timeout: 10_000 })

  await anna.context().close()
  await marek.context().close()
})

test('odcięcie sieci nie blokuje pisania, a po powrocie zmiany się scalają', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, 'Offline')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'baza. ')
  await expect(marek.locator('.cm-content')).toContainText('baza.', { timeout: 10_000 })

  // Marek odcina sobie sieć symulatorem i pisze dalej — lokalnie ma działać normalnie.
  await marek.getByRole('button', { name: 'Odetnij sieć' }).click()
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type('offline', { delay: 25 })

  await expect(marek.locator('.cm-content')).toContainText('offline')
  // Anna NIE widzi jeszcze zmian Marka — to jest dowód, że odcięcie faktycznie działa,
  // a nie że symulator jest atrapą.
  await expect(anna.locator('.cm-content')).not.toContainText('offline')

  // Anna pisze w tym czasie swoje, więc po powrocie łączności trzeba scalić OBIE strony.
  await anna.locator('.cm-content').click()
  await anna.keyboard.press('End')
  await anna.keyboard.type('online', { delay: 25 })

  await marek.getByRole('button', { name: 'Przywróć łączność' }).click()

  await expect(anna.locator('.cm-content')).toContainText('offline', { timeout: 15_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 15_000 })

  expect(await documentText(anna)).toBe(await documentText(marek))
  expect(await crdtCharacters(anna)).toBe(await crdtCharacters(marek))

  await anna.context().close()
  await marek.context().close()
})
