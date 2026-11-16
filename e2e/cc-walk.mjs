import { chromium } from '@playwright/test'
import { fileURLToPath } from 'node:url'
const OUT = fileURLToPath(new URL('./test-results/cc/', import.meta.url))
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', storageState: './test-results/walk/state.json' })
const page = await ctx.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
await page.goto('http://127.0.0.1:8080/create')
const apps = await page.evaluate(async () => (await (await fetch('/api/apps')).json()))
const list = apps.items ?? apps
let target = null
for (const a of list) {
  const s = await page.evaluate(async (id) => (await (await fetch(`/api/apps/${id}/cloud`)).json()), a.id)
  if (s.enabled) { target = a; break }
}
console.log('app', target?.id, target?.name)
await page.goto(`http://127.0.0.1:8080/apps/${target.id}`)
await page.waitForTimeout(2500)
await page.screenshot({ path: OUT + '1-preview.png' })
await page.getByRole('tab', { name: /云服务/ }).click()
await page.waitForTimeout(1500)
await page.screenshot({ path: OUT + '2-console.png' })
await page.getByRole('button', { name: '新增' }).click()
await page.waitForTimeout(500)
await page.screenshot({ path: OUT + '3-new.png' })
await page.keyboard.press('Escape')
await page.locator('.cc-tabs').getByRole('tab', { name: '访问与权限' }).click()
await page.waitForTimeout(800)
await page.screenshot({ path: OUT + '4-access.png' })
await page.locator('.cc-tabs').getByRole('tab', { name: '文件' }).click()
await page.waitForTimeout(500)
await page.screenshot({ path: OUT + '5-files.png' })
await page.getByRole('button', { name: /^发布/ }).first().click().catch(() => {})
await page.waitForTimeout(1000)
await page.screenshot({ path: OUT + '6-publish.png' })
console.log('errors', errors)
await browser.close()
