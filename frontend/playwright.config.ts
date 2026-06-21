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
    baseURL: 'http://localhost:5173',
    // Video from every run — the same recording drives the demo GIF in CP9.
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
})
