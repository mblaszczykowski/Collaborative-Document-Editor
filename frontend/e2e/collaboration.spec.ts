import { expect, test, type Page } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

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

/**
 * Świeży dokument na każdy test.
 *
 * Bez tego wszystkie testy otwierają pierwszy dokument z listy, widzą nawzajem swoje
 * zmiany i padają z powodów niemających nic wspólnego z tym, co sprawdzają. Izolacja
 * przez osobny dokument jest tańsza niż czyszczenie bazy między testami i nie wymaga
 * dostępu do niej z poziomu testu.
 */



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
  // `data-testid`, nie klasa CSS: licznik jest odczytem ze stanu CRDT, więc test ma się
  // trzymać jego tożsamości, a nie stylowania — inaczej każda zmiana wyglądu paska stanu
  // psuje testy z powodów niemających nic wspólnego z zachowaniem.
  return (await page.getByTestId('char-count').innerText()).trim()
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

  // PRAWDZIWE odcięcie sieci przeglądarce — zrywa też WebSocket, więc test obejmuje
  // ponowne połączenie i resynchronizację, a nie tylko wstrzymanie wysyłki.
  await marek.context().setOffline(true)
  await expect(marek.locator('.save-state.offline')).toBeVisible({ timeout: 20_000 })
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

  await marek.context().setOffline(false)

  await expect(anna.locator('.cm-content')).toContainText('offline', { timeout: 30_000 })
  await expect(marek.locator('.cm-content')).toContainText('online', { timeout: 30_000 })

  expect(await documentText(anna)).toBe(await documentText(marek))
  expect(await crdtCharacters(anna)).toBe(await crdtCharacters(marek))

  await anna.context().close()
  await marek.context().close()
})

test('historia pozwala cofnąć dokument do wcześniejszej wersji', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Historia')
  const anna = await joinAs(browser, 'Anna', documentId)
  // DWÓCH autorów, nie jeden: oś czasu grupuje kolejne zmiany tego samego autora w jeden
  // wpis (okno 2 minut), więc gdyby oba fragmenty napisała Anna, powstałaby JEDNA wersja
  // i „cofnięcie do poprzedniej" nie miałoby do czego wrócić. Pierwsza wersja tego testu
  // właśnie tak wyglądała i przechodziła obok sedna.
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'wersja dobra')
  // Odczekanie na potwierdzenie zapisu: historia czyta log operacji, więc wpis pojawi się
  // dopiero po zapisie, a nie po samym rozgłoszeniu (ADR-0003).
  await expect(anna.getByTestId('char-count')).toContainText('12 znaków')
  await marek.waitForTimeout(500)

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('End')
  await marek.keyboard.type(' ZEPSUTA', { delay: 25 })
  await expect(anna.getByTestId('char-count')).toContainText('20 znaków')
  await anna.waitForTimeout(500)

  // Historia jest widoczna od razu — panel boczny nie ma zakładek, bo nie ma już czego
  // przełączać: podgląd stanu CRDT zniknął z interfejsu użytkownika.
  const entries = anna.locator('.history-entry')
  await expect(entries.first()).toBeVisible({ timeout: 10_000 })

  // Wpisy są od najnowszych; ostatni to najstarszy stan.
  await entries.last().click()
  await expect(anna.locator('.history-preview')).not.toContainText('Odtwarzam', {
    timeout: 10_000,
  })

  await anna.getByRole('button', { name: 'Restore this version' }).click()

  // Przywrócenie wraca zwykłym kanałem operacji — edytor ma się zaktualizować sam,
  // bez przeładowania strony. To jest sedno decyzji z ADR-0003 pkt 6.
  await expect(anna.locator('.cm-content')).not.toContainText('ZEPSUTA', { timeout: 15_000 })
  expect(await documentText(anna)).toContain('wersja dobra')

  // Przywrócenie wraca zwykłym kanałem operacji, więc DRUGA przeglądarka też ma je
  // zobaczyć bez odświeżania — to jest cała teza ADR-0003 pkt 6.
  await expect(marek.locator('.cm-content')).not.toContainText('ZEPSUTA', { timeout: 15_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  await anna.context().close()
  await marek.context().close()
})

test('operacje przeżywają przeładowanie strony i docierają po powrocie', async ({
  browser,
  request,
}) => {
  const documentId = await freshDocument(request, 'Bufor trwały')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'poczatek ')
  await expect(marek.locator('.cm-content')).toContainText('poczatek', { timeout: 10_000 })

  // PRAWDZIWE odcięcie sieci przeglądarce, nie symulator w aplikacji.
  // Symulator wstrzymywał tylko wysyłkę operacji; `setOffline` zrywa też połączenie
  // WebSocket, więc test obejmuje pełną ścieżkę: utrata łączności → praca lokalna →
  // ponowne połączenie → resynchronizacja. To jest ta sama droga, którą przechodzi
  // użytkownik z padniętym wi-fi.
  await marek.context().setOffline(true)
  await marek.locator('.cm-content').click()
  await marek.keyboard.press('Control+End')
  await marek.keyboard.type('zbuforowane', { delay: 25 })
  await expect(anna.locator('.cm-content')).not.toContainText('zbuforowane')

  // ...i ZAMYKA KARTĘ przeładowaniem. Bufor w pamięci przepadłby w tym momencie razem
  // z całym stanem strony — dlatego niepotwierdzone operacje są też w IndexedDB.
  await marek.context().setOffline(false)
  await marek.reload()
  await expect(marek.locator('.editor-host')).toBeVisible({ timeout: 15_000 })

  // Po odtworzeniu strony bufor jest wczytywany PRZED resynchronizacją, więc operacje
  // dolatują do serwera i stamtąd do Anny — mimo że powstały w innej sesji przeglądarki.
  await expect(anna.locator('.cm-content')).toContainText('zbuforowane', { timeout: 20_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  await anna.context().close()
  await marek.context().close()
})


test('cofnięcie zdejmuje własną zmianę, ale nie cudzą', async ({ browser, request }) => {
  const documentId = await freshDocument(request, 'Cofanie')
  const anna = await joinAs(browser, 'Anna', documentId)
  const marek = await joinAs(browser, 'Marek', documentId)

  await typeInto(anna, 'zdanie Anny. ')
  await expect(marek.locator('.cm-content')).toContainText('Anny', { timeout: 10_000 })

  await marek.locator('.cm-content').click()
  await marek.keyboard.press('ControlOrMeta+a')
  await marek.keyboard.press('ArrowRight')
  await marek.keyboard.type('zdanie Marka.', { delay: 25 })
  await expect(anna.locator('.cm-content')).toContainText('Marka', { timeout: 10_000 })

  // Anna cofa. Zdejmuje SWOJE zdanie, a zdanie Marka musi zostać nietknięte —
  // undo na tekście (historia CodeMirrora) zdjęłoby oba i skasowało cudzą pracę.
  await anna.locator('.cm-content').click()
  await anna.keyboard.press('ControlOrMeta+z')

  await expect(anna.locator('.cm-content')).not.toContainText('Anny', { timeout: 10_000 })
  await expect(anna.locator('.cm-content')).toContainText('Marka')

  // Cofnięcie jest zwykłą operacją, więc dociera też do drugiej przeglądarki.
  await expect(marek.locator('.cm-content')).not.toContainText('Anny', { timeout: 10_000 })
  expect(await documentText(marek)).toBe(await documentText(anna))

  // Ponowienie przywraca zdjęty tekst.
  await anna.keyboard.press('ControlOrMeta+Shift+z')
  await expect(anna.locator('.cm-content')).toContainText('Anny', { timeout: 10_000 })

  await anna.context().close()
  await marek.context().close()
})
