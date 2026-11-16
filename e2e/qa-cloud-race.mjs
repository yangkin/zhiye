// 真实模型：点「共享云服务」确认 → AI 开始设计表结构 → 任务进行中在页面上关闭云服务 → 任务结束后仍是关闭
import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'
const BASE = 'http://127.0.0.1:8080'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const b = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' })
const page = await (await b.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
const dialogs = []; page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss() })
const req = page.request
const H = () => ({ 'Idempotency-Key': crypto.randomUUID() })
const json = async (m, p, d) => { const r = await req.fetch(BASE + p, { method: m, data: d, headers: H() }); const t = await r.text(); if (!r.ok()) throw new Error(m + ' ' + p + ' ' + r.status() + t); return t ? JSON.parse(t) : null }
const waitTask = async (id) => { for (;;) { const t = await json('GET', '/api/tasks/' + id); if (['SUCCEEDED','FAILED','CANCELLED'].includes(t.status)) return t; await sleep(1500) } }
await page.goto(BASE + '/apps'); await page.waitForLoadState('networkidle')
const up = await req.post(BASE + '/api/imports/upload', { multipart: { file: { name: 'index.html', mimeType: 'text/html', buffer: readFileSync(new URL('./fixtures/garden.html', import.meta.url)) } }, headers: H() })
const { app, task } = await json('POST', '/api/imports', { uploadId: (await up.json()).uploadId, name: 'QA报修登记' })
await waitTask(task.id)
await page.goto(BASE + '/apps/' + app.id)
await page.getByRole('button', { name: '共享云服务' }).click()
await page.locator('.composer-float .confirm-card').getByRole('button', { name: '确认启用' }).click()
let t
for (let i = 0; i < 30 && !t; i++) { t = (await json('GET', '/api/apps/' + app.id)).activeTask; await sleep(500) }
console.log('AI 任务', t?.type, t?.status, 'cloud', (await json('GET', `/api/apps/${app.id}/cloud`)).status)
await sleep(15000)
await page.getByRole('tab', { name: '云服务' }).click()
await page.getByRole('button', { name: '关闭云服务' }).click()
await page.getByRole('dialog', { name: /关闭云服务/ }).getByRole('button', { name: '关闭云服务' }).click()
await page.getByText('未启用').first().waitFor({ timeout: 15000 })
console.log('关闭时任务状态', (await json('GET', '/api/tasks/' + t.id)).status, 'cloud', (await json('GET', `/api/apps/${app.id}/cloud`)).status)
const done = await waitTask(t.id)
const st = await json('GET', `/api/apps/${app.id}/cloud`)
const files = await json('GET', `/api/versions/${(await json('GET', '/api/apps/' + app.id)).currentVersion.id}/files`)
await page.reload(); await page.getByRole('tab', { name: '云服务' }).click(); await sleep(1500)
const pane = await page.locator('.studio-pane-title').first().innerText()
console.log('任务结束', done.status, done.error?.code ?? '', '| 写了 schema.sql:', JSON.stringify(files).includes('cloud/schema.sql'), '| cloud:', st.status, '| 页面:', pane.replace(/\n/g, ' '), '| 原生弹窗:', dialogs.length)
const msgs = await json('GET', `/api/apps/${app.id}/messages`)
console.log('最后一条回复：', (msgs.filter((m) => m.role === 'assistant').pop()?.content || '').slice(0, 400))
await b.close()
