// 调试：手动微调选中元素
import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'
const BASE = 'http://127.0.0.1:8080'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
page.on('console', (m) => console.log('CONSOLE', m.type(), m.text()))
const req = page.request
let appId
try {
  await page.goto(BASE + '/apps')
  const up = await req.post(BASE + '/api/imports/upload', { multipart: { file: { name: 'garden.html', mimeType: 'text/html', buffer: Buffer.from(readFileSync(new URL('./fixtures/garden.html', import.meta.url), 'utf8').replace('</body>', `<!-- ${Date.now()} --></body>`)) } } })
  const m = await up.json()
  const r = await (await req.post(BASE + '/api/imports', { data: { uploadId: m.uploadId, name: '调试花园' }, headers: { 'Idempotency-Key': crypto.randomUUID() } })).json()
  appId = r.app.id
  for (;;) { const t = await (await req.get(BASE + '/api/tasks/' + r.task.id)).json(); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) { console.log('task', t.status); break } await sleep(1000) }
  const app = await (await req.get(BASE + '/api/apps/' + appId)).json()
  console.log('previewUrl', app.previewUrl, 'visualEditable', app.visualEditable)
  await page.evaluate(() => window.addEventListener('message', (e) => console.log('MSG', JSON.stringify(e.data).slice(0, 200))))
  await page.goto(BASE + '/apps/' + appId)
  await page.evaluate(() => window.addEventListener('message', (e) => console.log('MSG', JSON.stringify(e.data).slice(0, 160))))
  await page.locator('iframe[data-preview]').waitFor()
  await sleep(2500)
  const f = page.frames().find((x) => x.url().includes('/p/') || x !== page.mainFrame())
  console.log('frame url', f?.url())
  console.log('data-loc count', await f?.evaluate(() => document.querySelectorAll('[data-loc]').length).catch((e) => 'ERR ' + e.message))
  console.log('bridge', await f?.evaluate(() => !!window.__zhiyeBridge).catch((e) => 'ERR ' + e.message))
  await page.getByRole('button', { name: '手动微调' }).click()
  await sleep(800)
  console.log('editing attr', await f?.evaluate(() => document.documentElement.getAttribute('data-zhiye-editing')).catch((e) => 'ERR ' + e.message))
  await page.frameLocator('iframe[data-preview]').locator('h1').click()
  await sleep(1000)
  console.log('panel', await page.getByLabel('编辑元素').count())
} finally {
  if (appId) await req.fetch(BASE + '/api/apps/' + appId, { method: 'DELETE' })
  await browser.close()
}
