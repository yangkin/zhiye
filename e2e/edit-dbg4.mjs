import { chromium } from 'playwright'
const b = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 } })
const p = await ctx.newPage()
p.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE', m.text().slice(0, 120)) })
await p.goto('http://127.0.0.1:8080/create')
const api = async (path, opt = {}) => (await (await ctx.request.fetch('http://127.0.0.1:8080' + path, { method: opt.method || 'GET', data: opt.data, headers: { 'content-type': 'application/json' } })).json())
const app = await api('/api/apps', { method: 'POST', data: { type: '日常开发', prompt: '做一个点击计数器：一个标题、显示次数、加一和重置两个按钮', mode: '快速', model: 'auto', assetIds: [] } })
let t = app.activeTask
for (let i = 0; i < 60; i++) { t = await api('/api/tasks/' + t.id); if (['SUCCEEDED','FAILED'].includes(t.status)) break; await new Promise(r => setTimeout(r, 3000)) }
await api('/api/apps/' + app.id + '/generate', { method: 'POST' })
let d = await api('/api/apps/' + app.id)
for (let i = 0; i < 100; i++) { d = await api('/api/apps/' + app.id); if (d.phase === 'ready' || d.phase === 'failed') break; await new Promise(r => setTimeout(r, 3000)) }
await p.goto('http://127.0.0.1:8080/apps/' + app.id)
await p.waitForTimeout(1500)
const info = () => p.evaluate(() => {
  const f = document.querySelector('iframe[data-preview]')
  return { frames: document.querySelectorAll('iframe').length, src: f ? f.getAttribute('src') : null }
})
console.log('before', await info())
await p.getByRole('button', { name: '收起工作区' }).click()
await p.waitForTimeout(500)
console.log('collapsed', await info())
await p.getByRole('button', { name: '展开工作区' }).click()
for (const ms of [300, 1200, 3000]) {
  await p.waitForTimeout(ms)
  console.log('after restore +' + ms, await info())
}
const txt = await p.frameLocator('iframe[data-preview]').locator('body').innerText().catch((e) => 'ERR ' + e.message)
console.log('body text:', JSON.stringify(txt.slice(0, 120)))
await p.screenshot({ path: '/tmp/after-restore.png' })
await b.close()
