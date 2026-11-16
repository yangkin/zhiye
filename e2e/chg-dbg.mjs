import { chromium } from 'playwright'
const b = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 } })
const p = await ctx.newPage()
p.on('response', async (r) => {
  if (r.url().includes('/changes')) {
    let body = ''
    try { body = (await r.text()).slice(0, 160) } catch {}
    console.log('NET', r.status(), r.url().replace('http://127.0.0.1:8080', ''), body)
  }
})
await p.goto('http://127.0.0.1:8080/create')
const api = async (path, opt = {}) => {
  const r = await ctx.request.fetch('http://127.0.0.1:8080' + path, { method: opt.method || 'GET', data: opt.data, headers: { 'content-type': 'application/json' } })
  const t = await r.text()
  try { return JSON.parse(t) } catch { return t }
}
const app = await api('/api/apps', { method: 'POST', data: { type: '日常开发', prompt: '做一个待办清单：可添加、勾选完成、删除，顶部显示完成数。', mode: '快速', model: 'auto', assetIds: [] } })
let t = app.activeTask
for (let i = 0; i < 100; i++) { t = await api('/api/tasks/' + t.id); if (['SUCCEEDED','FAILED'].includes(t.status)) break; await new Promise(r => setTimeout(r, 3000)) }
await api('/api/apps/' + app.id + '/generate', { method: 'POST' })
let d = await api('/api/apps/' + app.id)
for (let i = 0; i < 150; i++) { d = await api('/api/apps/' + app.id); if (d.phase === 'ready' || d.phase === 'failed') break; await new Promise(r => setTimeout(r, 3000)) }
const m = await api('/api/apps/' + app.id + '/messages', { method: 'POST', data: { content: '把标题改成「今天的待办」' } })
for (let i = 0; i < 150; i++) { const s = await api('/api/tasks/' + m.task.id); if (['SUCCEEDED','FAILED'].includes(s.status)) { console.log('modify', s.status); break } await new Promise(r => setTimeout(r, 3000)) }
d = await api('/api/apps/' + app.id)
console.log('app', app.id, 'V', d.currentVersion?.number)
await p.goto('http://127.0.0.1:8080/apps/' + app.id)
await p.getByRole('tab', { name: '代码' }).click()
await p.locator('.code-files-switch').getByRole('button', { name: '改动' }).click()
await p.waitForTimeout(2500)
console.log('rows', await p.locator('.change-row').count())
console.log('left', (await p.locator('.code-changes').innerText().catch(() => '')).slice(0, 90).replace(/\n/g, ' | '))
await b.close()
