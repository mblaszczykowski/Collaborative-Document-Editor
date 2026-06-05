import { defineConfig, devices } from '@playwright/test'

/**
 * Testy E2E zakładają, że backend (:8080) i frontend (:5173) już działają.
 *
 * Świadomie NIE podnoszę tu backendu przez `webServer`: wymagałby Postgresa i Redisa,
 * czyli całego `docker compose up`, i zamieniłby ten plik w drugi, gorszy compose.
 * Uruchomienie jest opisane w README i w CI jako jawne kroki — widoczne, zamiast ukryte
 * w konfiguracji testów.
 */
export default defineConfig({
  testDir: './e2e',
  // Kolejno, nie równolegle: testy dzielą jeden backend i jedną bazę, a każdy z nich
  // tworzy dokumenty i sprawdza propagację. Równoległe przebiegi widziałyby swoje
  // wzajemne zmiany i padały z powodów niemających nic wspólnego z algorytmem.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://localhost:5173',
    // Wideo z każdego przebiegu — to samo nagranie napędza GIF demo w CP9.
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
})
