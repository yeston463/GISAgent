import { defineConfig, devices } from '@playwright/test'

// E2E smoke for the GIS frontend. Run `npm run test:e2e` (after
// `npx playwright install`). It boots the Vite dev server and asserts the app
// mounts. Add scenario tests under e2e/ as the app grows.
export default defineConfig({
  testDir: './e2e',
  timeout: 30000,
  expect: { timeout: 10000 },
  fullyParallel: true,
  retries: 0,
  use: {
    baseURL: 'http://127.0.0.1:5173',
    headless: true,
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
