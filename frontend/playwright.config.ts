import { defineConfig, devices } from '@playwright/test'

/**
 * The E2E tests assume the backend (:8080) and the frontend (:5173) are already running.
 *
 * The backend is deliberately NOT started here via `webServer`: it would need Postgres and Redis,
 * that is, the whole `docker compose up`, and would turn this file into a second, worse compose.
 * Starting up is described in the README and in CI as explicit steps — visible rather than hidden
 * in a test configuration.
 */
export default defineConfig({
  testDir: './e2e',
  // Sequential, not parallel: the tests share one backend and one database, and each of them
  // creates documents and checks propagation. Parallel runs would see each other's changes and
  // fail for reasons unrelated to the algorithm.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    // The same override the fixtures read (`E2E_APP_URL` in e2e/helpers.ts), so a run against a
    // throwaway stack does not have one half of the suite talking to it and the other half to
    // whatever happens to be on the default ports.
    baseURL: process.env.E2E_APP_URL ?? 'http://localhost:5173',
    // Kept on failure, so a red e2e run in CI carries a recording of what happened. The demo
    // GIF (CP9) comes from `npm run demo`, whose scenes pass — so this setting does NOT drive
    // it; `tools/make-gif.sh` reads what Playwright leaves behind for that run specifically.
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
})
