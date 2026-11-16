// 「要继续吗？」检查点（界面）：做到步数检查点弹确认卡 → 在输入框里说「继续，顺便…」→ 同一个任务接着做完，补充要求进了对话。
// 需要用假 runner 的实例（假 runner 里需求带 [turnlimit] 会撞一次检查点），例如模拟内网实例：
//   ZHIYE_BASE_URL=http://127.0.0.1:8090 node e2e/qa-continue.mjs
import { chromium } from '@playwright/test'
const BASE = process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8090'
const b = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' })
const page = await (await b.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 900 } })).newPage()
const req = page.request
const json = async (m, u, d) => { const r = await req.fetch(BASE + u, { method: m, data: d, headers: { 'Idempotency-Key': crypto.randomUUID() } }); const t = await r.text(); if (!r.ok()) throw new Error(`${m} ${u} ${r.status()} ${t}`); return t ? JSON.parse(t) : null }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let fail = 0
const check = (ok, msg) => { console.log((ok ? 'PASS ' : 'FAIL ') + msg); if (!ok) fail++ }

await page.goto(BASE + '/create')
await page.waitForLoadState('networkidle')
if (page.url().includes('/login')) {
  await page.getByRole('link', { name: /统一账号登录|登录/ }).first().click()
  await page.locator('#login').fill('qa' + (Date.now() % 100000))
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL(/\/create/, { timeout: 20000 })
}
const app = await json('POST', '/api/apps', { type: '日常开发', prompt: '做一个巡检记录 [turnlimit]', mode: '快速', model: 'auto', assetIds: [] })
for (let i = 0; i < 120; i++) { const t = await json('GET', '/api/tasks/' + app.activeTask.id); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) { check(t.status === 'SUCCEEDED', '生成计划 ' + t.status); break } await sleep(1000) }
const task = await json('POST', `/api/apps/${app.id}/generate`)
await page.goto(BASE + '/apps/' + app.id)
const card = page.locator('.confirm-card, [data-confirm-kind="continue"]').filter({ hasText: '要继续吗' }).first()
await card.waitFor({ timeout: 60000 })
check(true, '弹出「要继续吗？」：' + (await card.innerText()).split('\n')[0])
check(await card.getByRole('button', { name: '继续' }).isVisible() && await card.getByRole('button', { name: '先到这里' }).isVisible(), '按钮是「继续」和「先到这里」')
const st = await json('GET', '/api/tasks/' + task.id)
check(st.status === 'RUNNING', '等待时任务仍在进行：' + st.status)
const box = page.getByRole('textbox').last()
await box.fill('继续，顺便把标题改成巡检台账')
await box.press('Enter')
let done
for (let i = 0; i < 90; i++) { done = await json('GET', '/api/tasks/' + task.id); if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(done.status)) break; await sleep(1000) }
check(done.status === 'SUCCEEDED', '说「继续」后同一个任务做完：' + done.status + (done.error ? ' ' + JSON.stringify(done.error) : ''))
const msgs = await json('GET', `/api/apps/${app.id}/messages`)
check(JSON.stringify(msgs).includes('把标题改成巡检台账'), '补充要求记进了对话')
check(!(await page.getByText('要继续吗').first().isVisible().catch(() => false)) || (await card.innerText()).includes('继续'), '确认卡不再等待')
await page.screenshot({ path: '/tmp/zhiye-continue.png' })
await b.close()
process.exitCode = fail ? 1 : 0
