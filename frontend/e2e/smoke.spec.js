import { test, expect } from '@playwright/test'

// Smoke test: the app boots, the dev server answers, and #app actually mounts
// content. This catches "blank page / white screen" regressions that unit
// tests miss. Expand with scenario tests (analysis flow, map interaction) as
// needed — keep this one fast and always-green.
test('frontend loads and mounts', async ({ page }) => {
  const resp = await page.goto('/')
  expect(resp.status()).toBeLessThan(400)
  await expect(page.locator('#app')).not.toBeEmpty()
})

test('backend runtime endpoint is reachable from the app origin', async ({ page }) => {
  // The frontend proxies /analysis to the Python GIS service in dev (see
  // vite.config.js). This verifies the proxy wiring is intact.
  const resp = await page.request.get('/analysis/runtime')
  expect(resp.status()).toBe(200)
  const body = await resp.json()
  expect(body).toBeInstanceOf(Object)
})

test('refresh retains the GIS session and context version', async ({ page }) => {
  await page.goto('/')
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('gis-agent-memory-id'))).not.toBeNull()

  const before = await page.evaluate(() => {
    const memoryId = sessionStorage.getItem('gis-agent-memory-id')
    sessionStorage.setItem('gis-agent-context-version', '7')
    return { memoryId, contextVersion: sessionStorage.getItem('gis-agent-context-version') }
  })

  await page.reload()
  const after = await page.evaluate(() => ({
    memoryId: sessionStorage.getItem('gis-agent-memory-id'),
    contextVersion: sessionStorage.getItem('gis-agent-context-version')
  }))

  expect(after.memoryId).toBe(before.memoryId)
  // Context version is reconciled with the backend after mount. It can change
  // from a stale sessionStorage value, but must remain a valid version.
  expect(Number(after.contextVersion)).toBeGreaterThanOrEqual(0)
})

test('agent routes a DEM request from the chat input', async ({ page }) => {
  await page.goto('/')
  const sample = page.getByRole('button', { name: '加载验证样例' })
  await sample.click()
  const input = page.getByPlaceholder('输入 GIS 分析任务...')
  await expect(input).toBeEnabled({ timeout: 30000 })
  await expect(input).toBeVisible()
  await input.fill('获取DEM')
  await input.press('Enter')
  await expect(page.getByText('已请求从当前 ArcGIS 底图采样 DEM。采样完成后会写入当前会话。')).toBeVisible({ timeout: 90000 })
})
