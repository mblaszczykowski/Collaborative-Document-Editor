import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { defaultExclude } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    // The e2e/ directory belongs to Playwright, not to Vitest.
    //
    // Vitest's default pattern catches every `*.spec.ts`, so without this exclusion it also picks
    // up the Playwright scenarios and blows up on them with "Playwright Test did not expect test()
    // to be called here". The unit suite passes, but `npm run test` exits with an error — so the
    // CI step meant to keep the TypeScript implementation in step with the Java one is RED for a
    // reason unrelated to the code.
    //
    // The two suites are run separately and deliberately: `npm run test` needs nothing beyond
    // Node, while `npm run e2e` needs a running backend, a database and a browser.
    exclude: [...defaultExclude, 'e2e/**'],
  },
})
