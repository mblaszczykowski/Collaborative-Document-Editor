import { expect, test } from '@playwright/test'
import { APP, joinAs, typeInto } from './helpers'

/**
 * Udostępnianie dokumentu (CP21) na prawdziwej przeglądarce.
 *
 * Testy integracyjne po stronie serwera (`AccessIT`) sprawdzają, że reguła jest właściwa:
 * kto może nadać rolę, czego nadać nie można i kiedy zaproszenie przestaje działać. Tutaj
 * sprawdzana jest rzecz, której tamte sprawdzić nie mogą — **czy tą regułą da się posłużyć**.
 * Do CP21 nie dało się: `grant` i `revoke` istniały wyłącznie dla testów, a każdy dokument
 * z interfejsu był domyślnie edytowalny dla wszystkich zalogowanych, więc żadna rola poza
 * właścicielem nie była w produkcie osiągalna.
 *
 * Dlatego pierwszy scenariusz zaczyna się od dokumentu utworzonego **w przeglądarce**, a nie
 * przez `freshDocument`: fixture przełącza widoczność na `link`, czyli omija dokładnie ten
 * mechanizm, który jest tu przedmiotem badania.
 */

/** Świeży użytkownik i jego własny (prywatny) dokument. */
async function ownerWithDocument(browser: Parameters<typeof joinAs>[0], name: string) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(APP)
  await page.getByPlaceholder('Twoje imię').fill(name)
  await page.getByRole('button', { name: 'Rozpocznij' }).click()
  // Pusta lista dokumentów kończy się utworzeniem pierwszego — a od CP21 jest on prywatny.
  await expect(page.locator('.editor-host')).toBeVisible({ timeout: 15_000 })
  return page
}

test('zaproszenie wpuszcza dokładnie z tą rolą, którą niesie', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')
  await typeInto(anna, 'Tekst Anny. ')

  await anna.getByRole('button', { name: 'Udostępnij' }).click()
  const panel = anna.getByRole('dialog', { name: 'Share this document' })
  await expect(panel).toBeVisible()

  await panel.getByRole('combobox').first().selectOption('commenter')
  await panel.getByRole('button', { name: 'Create link' }).click()

  // Link pokazujemy raz — serwer trzyma wyłącznie jego skrót. Czytamy go z pola,
  // a nie ze schowka: schowek w Playwrighcie wymaga uprawnień przeglądarki, a to
  // testowałoby uprawnienia przeglądarki, nie udostępnianie.
  const link = await panel.locator('input').first().inputValue()
  expect(link).toContain('invite=')

  const bartek = await browser.newContext()
  const bartekPage = await bartek.newPage()
  await bartekPage.goto(link)
  await bartekPage.getByPlaceholder('Twoje imię').fill('Bartek')
  await bartekPage.getByRole('button', { name: 'Rozpocznij' }).click()

  // Dostęp jest prawdziwy: widzi tekst Anny.
  await expect(bartekPage.locator('.cm-content')).toContainText('Tekst Anny.', {
    timeout: 15_000,
  })
  // I jest DOKŁADNIE tą rolą, którą niosło zaproszenie — komentator, nie edytor.
  await expect(bartekPage.locator('.role-badge')).toHaveText('Możesz komentować')

  await anna.context().close()
  await bartek.close()
})

test('bez zaproszenia sam adres dokumentu nie wpuszcza', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')
  const url = anna.url()

  const obcy = await browser.newContext()
  const obcyPage = await obcy.newPage()
  await obcyPage.goto(url)
  await obcyPage.getByPlaceholder('Twoje imię').fill('Obcy')
  await obcyPage.getByRole('button', { name: 'Rozpocznij' }).click()

  // Ten sam ekran co przy dokumencie usuniętym — serwer odpowiada 404 na brak dostępu
  // i na nieistnienie tak samo, celowo.
  await expect(obcyPage.locator('.unavailable-banner')).toBeVisible({ timeout: 15_000 })

  await anna.context().close()
  await obcy.close()
})

test('podniesienie roli działa bez przeładowania strony', async ({ browser }) => {
  const anna = await ownerWithDocument(browser, 'Anna')

  await anna.getByRole('button', { name: 'Udostępnij' }).click()
  const panel = anna.getByRole('dialog', { name: 'Share this document' })
  await panel.getByRole('combobox').first().selectOption('viewer')
  await panel.getByRole('button', { name: 'Create link' }).click()
  const link = await panel.locator('input').first().inputValue()
  await panel.getByRole('button', { name: 'Close' }).click()

  const bartek = await browser.newContext()
  const bartekPage = await bartek.newPage()
  await bartekPage.goto(link)
  await bartekPage.getByPlaceholder('Twoje imię').fill('Bartek')
  await bartekPage.getByRole('button', { name: 'Rozpocznij' }).click()
  await expect(bartekPage.locator('.role-badge')).toHaveText('Tylko do odczytu', {
    timeout: 15_000,
  })

  // Anna podnosi mu rolę. To jest ta rzecz, której brak README wypisywało jako pozycję
  // otwartą: rola przyjeżdżała raz, ze stanem początkowym, więc nadanie uprawnień nie
  // robiło NIC do czasu przeładowania — czyli działająca funkcja wyglądała na zepsutą.
  await anna.getByRole('button', { name: 'Udostępnij' }).click()
  const reopened = anna.getByRole('dialog', { name: 'Share this document' })
  const member = reopened.locator('.share-list li', { hasText: 'Bartek' })
  await member.getByRole('combobox').selectOption('editor')

  // Bez odświeżania: odznaka roli znika, bo rola przestała cokolwiek ograniczać.
  await expect(bartekPage.locator('.role-badge')).toHaveCount(0, { timeout: 15_000 })

  await anna.context().close()
  await bartek.close()
})
