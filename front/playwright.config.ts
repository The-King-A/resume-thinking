import { defineConfig } from '@playwright/test'
import { existsSync } from 'node:fs'

const localChromeChannel = process.platform === 'win32' && existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
  ? 'chrome'
  : undefined
const defaultApiBase = 'http://127.0.0.1:8080'

function isExactLoopbackHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port !== '' && url.pathname === '/' && !url.username && !url.password && !url.search && !url.hash
  } catch { return false }
}

const requestedApiBase = process.env.E2E_API_BASE_URL || defaultApiBase
const frontendApiBase = isExactLoopbackHttpUrl(requestedApiBase) ? requestedApiBase.replace(/\/$/, '') : defaultApiBase

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? localChromeChannel,
  },
  webServer: {
    command: 'corepack pnpm exec vite --host 127.0.0.1 --port 4173',
    env: { ...process.env, VITE_API_BASE_URL: frontendApiBase },
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
})
