import { expect, test } from '@playwright/test'
import { freshDocument, joinAs, typeInto } from './helpers'

/**
 * Lista dokumentów (CP11) na prawdziwej przeglądarce.
 *
 * Testy integracyjne po stronie serwera sprawdzają, że katalog zwraca właściwe dane.
 * Tutaj chodzi o coś innego: czy użytkownik potrafi tymi danymi POSŁUŻYĆ SIĘ — czyli
 * czy zmiana nazwy trafia na listę, czy usunięty dokument znika, i czy da się go
 * odzyskać, gdy usunięcie było pomyłką.
 *
 * <b>Tytuły muszą być unikalne między przebiegami.</b> W odróżnieniu od scenariuszy
 * współbieżności, gdzie izolację daje świeży dokument, tutaj testowanym elementem jest
 * LISTA — a ta pokazuje wszystko, co kiedykolwiek powstało. Pierwsza wersja tych testów
 * używała stałych tytułów i przechodziła dokładnie raz: przy drugim przebiegu selektor
 * trafiał na dwa wiersze o tej samej nazwie i padał na niejednoznaczności.
 */

/** Znacznik unikalny dla przebiegu — patrz komentarz nad plikiem. */
function unique(name: string): string {
  return `${name}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

test('nowy dokument pojawia się na liście i daje się otworzyć', async ({ browser, request }) => {
  const documentId = await freshDocument(request, unique('Punkt wyjścia'))
  const page = await joinAs(browser, 'Anna', documentId)

  await page.getByRole('button', { name: '+ Nowy dokument' }).click()

  await expect(page.locator('.sb-item.active')).toContainText('Dokument bez tytułu', {
    timeout: 10_000,
  })
  // Adres musi odpowiadać temu, co jest otwarte — inaczej odświeżenie strony
  // wraca do innego dokumentu niż ten, który użytkownik widzi.
  await expect(page).toHaveURL(/\?doc=/)

  await page.context().close()
})

test('zmiana nazwy z listy jest widoczna od razu', async ({ browser, request }) => {
  const before = unique('Stara nazwa')
  const after = unique('Nowa nazwa')
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

test('usunięty dokument znika z listy, a kosz pozwala go przywrócić', async ({
  browser,
  request,
}) => {
  const doomedTitle = unique('Do kosza')
  const doomed = await freshDocument(request, doomedTitle)
  const page = await joinAs(browser, 'Anna', doomed)

  const row = page.locator('.sb-row', { hasText: doomedTitle })
  await row.hover()
  await row.getByRole('button', { name: /Usuń/ }).click()

  await expect(page.locator('.sidebar')).not.toContainText(doomedTitle, { timeout: 10_000 })

  // Selektor po klasie, nie po nazwie dostępnej: `name: 'Kosz'` dopasowuje się PO FRAGMENCIE,
  // więc trafiał też w dokumenty nazwane „Do kosza" i wywalał się na niejednoznaczności.
  await page.locator('.sb-trash-toggle').click()
  const trashed = page.locator('.sb-row', { hasText: doomedTitle })
  await expect(trashed).toBeVisible({ timeout: 10_000 })

  await trashed.hover()
  await trashed.getByRole('button', { name: /Przywróć/ }).click()

  // Po przywróceniu dokument wraca na listę główną. Sprawdzamy pozycję NIEprzekreśloną,
  // bo wpis w koszu też zawiera ten tytuł.
  await expect(
    page.locator('.sb-item:not(.deleted)', { hasText: doomedTitle }),
  ).toBeVisible({ timeout: 10_000 })

  await page.context().close()
})

test('wyszukiwanie znajduje dokument po treści, nie tylko po tytule', async ({
  browser,
  request,
}) => {
  const marker = unique('heksadecymalny').replace(/-/g, '')
  const title = unique('Tytuł bez znacznika')
  const documentId = await freshDocument(request, title)
  const page = await joinAs(browser, 'Anna', documentId)

  // Treść trafia do indeksu przez podgląd, a ten powstaje przy snapshotowaniu — stąd
  // ponawianie zapytania zamiast pojedynczego sprawdzenia.
  await typeInto(page, `${marker} `.repeat(12))

  const search = page.locator('.sb-search')
  await expect(async () => {
    await search.fill('')
    await search.fill(marker)
    await expect(page.locator('.sb-item', { hasText: title })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 30_000 })

  await page.context().close()
})
