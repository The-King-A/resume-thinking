import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// Keep Playwright suites out of Vitest's broad *.spec.ts file discovery.
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    exclude: ['**/node_modules/**', '**/e2e/**', '**/dist/**'],
  },
})
