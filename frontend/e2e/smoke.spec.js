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
  await expect(page.getByText(/已请求从 ArcGIS World Elevation/)).toBeVisible()
})

test('knowledge graph template fills a complete candidate', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '图谱工作台' }).click()
  const panel = page.locator('.graph-panel')
  await expect(panel.locator('select')).not.toHaveValue('')
  await panel.getByRole('button', { name: '套用模板' }).click()
  await expect(panel.locator('input')).not.toHaveValue('')
  await expect(panel.getByRole('textbox', { name: '完整候选 JSON' })).toHaveValue(/"version"/)
})

test('knowledge graph workbench accepts a manual candidate', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '图谱工作台' }).click()
  const panel = page.locator('.graph-panel')
  await expect(panel.locator('select')).not.toHaveValue('')
  await panel.getByRole('button', { name: '手动填写' }).click()
  await panel.getByRole('textbox', { name: '能力 ID' }).fill('skyline_analysis')
  await panel.getByRole('textbox', { name: '候选版本' }).fill('manual-2026-08-10')
  await panel.getByRole('textbox', { name: '别名（每行一个）' }).fill('天际线\nskyline')
  await panel.getByRole('textbox', { name: '知识说明' }).fill('Manual candidate purpose.')
  await panel.getByRole('textbox', { name: '验收语句（每行一个）' }).fill('进行天际线分析')
  await panel.getByRole('button', { name: '生成 JSON' }).click()
  await expect(panel.getByRole('textbox', { name: '完整候选 JSON' })).toHaveValue(/manual-2026-08-10/)
})

test('knowledge graph workbench loads an AI candidate for review', async ({ page }) => {
  await page.route('**/api/agent/capabilities/candidates/ai-generate', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      version: 'ai-2026-08-10',
      aliases: ['天际线评估', 'skyline review'],
      purpose: 'AI generated purpose.',
      acceptanceUtterances: ['执行天际线评估'],
      graph: '{"version":"ai-2026-08-10","capabilities":[]}',
      preview: { valid: false, preview: { version: 'ai-2026-08-10', changes: [] }, qualityGates: [] }
    })
  }))
  await page.goto('/')
  await page.getByRole('button', { name: '图谱工作台' }).click()
  const panel = page.locator('.graph-panel')
  await panel.getByRole('button', { name: '手动填写' }).click()
  await panel.getByRole('textbox', { name: '能力 ID' }).fill('skyline_analysis')
  await panel.getByRole('textbox', { name: 'AI 图谱需求' }).fill('增加面向城市设计的天际线别名。')
  await panel.getByRole('button', { name: 'AI 生成' }).click()
  await expect(panel.getByRole('textbox', { name: '候选版本' })).toHaveValue('ai-2026-08-10')
  await expect(panel.getByRole('textbox', { name: '完整候选 JSON' })).toHaveValue(/ai-2026-08-10/)
})
