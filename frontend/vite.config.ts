import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { defaultExclude } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    exclude: [...defaultExclude, 'e2e/**'],
  },
})
