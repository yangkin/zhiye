import { chromium } from '@playwright/test'
import { fileURLToPath } from 'node:url'
const OUT = fileURLToPath(new URL('./test-results/cc/', import.meta.url))
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN', storageState: './test-results/walk/state.json' })
const page = await ctx.newPage()
await page.goto('http://127.0.0.1:8080/create')
const id = 'app_bw3v8k6f5fw5zn2u'
const r = await page.evaluate(async (id) => { const res = await fetch(`/api/apps/${id}/publish`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }, body: '{}' }); return { s: res.status, b: await res.json() } }, id)
console.log('publish', JSON.stringify(r))
const site = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'zh-CN' })
const sp = await site.newPage()
const errs = []
sp.on('console', (m) => m.type() === 'error' && errs.push(m.text()))
sp.on('response', (res) => console.log('RES', res.status(), res.url()))
sp.on('pageerror', (e) => errs.push(String(e)))
await sp.goto(r.b.siteUrl)
await sp.waitForTimeout(2500)
const html = await sp.content()
console.log('token in html:', html.includes('"token"'), 'site cfg:', html.includes('"site":true'))
console.log('rows visible:', await sp.getByText('SB-001').count())
await sp.screenshot({ path: OUT + '7-site.png' })
console.log('errors', errs)
await browser.close()
