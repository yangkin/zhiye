import { chromium } from '@playwright/test'
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const ctx = await browser.newContext({ storageState: './test-results/walk/state.json' })
const page = await ctx.newPage()
await page.goto('http://127.0.0.1:8080/create')
const id = 'app_bw3v8k6f5fw5zn2u'
const t = await page.evaluate(async (id) => {
  const r = await fetch(`/api/apps/${id}/messages`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ content: '把左侧导航的「设备台账」改回「设备列表」，其余不变' }) })
  return r.json()
}, id)
const taskId = t.task?.id ?? t.id
console.log('task', taskId)
let status = ''
for (let i = 0; i < 120 && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status); i++) {
  await page.waitForTimeout(5000)
  status = (await page.evaluate(async (tid) => (await (await fetch(`/api/tasks/${tid}`)).json()).status, taskId))
}
const ev = await page.evaluate(async (tid) => (await (await fetch(`/api/tasks/${tid}/events?after=0`)).text()), taskId)
console.log('status', status)
for (const line of ev.split('\n')) if (line.includes('screenshot') || line.includes('截图')) console.log(line.slice(0, 300))
await browser.close()
