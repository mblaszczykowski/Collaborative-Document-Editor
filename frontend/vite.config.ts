import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { defaultExclude } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // Katalog e2e/ należy do Playwrighta, nie do Vitesta.
    //
    // Domyślny wzorzec Vitesta łapie każdy `*.spec.ts`, więc bez tego wykluczenia zbiera
    // też scenariusze Playwrighta i wywala się na nich komunikatem "Playwright Test did not
    // expect test() to be called here". Zestaw jednostkowy przechodzi, ale `npm run test`
    // kończy się błędem — czyli krok CI, który miał pilnować zgodności implementacji
    // w TypeScripcie z tą w Javie, jest CZERWONY z powodu niezwiązanym z kodem.
    //
    // Oba zestawy uruchamiane są osobno i celowo: `npm run test` nie potrzebuje niczego
    // poza Node, a `npm run e2e` wymaga działającego backendu, bazy i przeglądarki.
    exclude: [...defaultExclude, 'e2e/**'],
  },
})
