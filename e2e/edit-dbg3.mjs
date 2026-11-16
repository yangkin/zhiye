import { chromium } from 'playwright'
const b = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 } })
const p = await ctx.newPage()
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
// 1) 收起 → 展开（和用例一致）
await p.getByRole('button', { name: '收起工作区' }).click()
await p.waitForTimeout(400)
console.log('panel after collapse', await p.locator('.preview-panel').count())
await p.getByRole('button', { name: '展开工作区' }).click()
await p.waitForTimeout(800)
console.log('iframe after restore', await p.locator('iframe[data-preview]').count())
// 2) 全屏
await p.getByRole('button', { name: '全屏预览' }).click()
await p.waitForTimeout(800)
await p.getByRole('button', { name: '手动微调' }).click()
await p.waitForTimeout(600)
const frame = p.frameLocator('iframe[title$="应用预览"]')
await frame.locator('h1').first().waitFor({ timeout: 20000 })
for (let i = 0; i < 3; i++) {
  await frame.locator('h1').first().click()
  await p.waitForTimeout(1200)
  console.log('try', i, 'panel', await p.locator('.ze-edit-panel').count(), 'pressed', await p.getByRole('button', { name: '手动微调' }).getAttribute('aria-pressed'))
  if (await p.locator('.ze-edit-panel').count()) break
}
console.log('busy hint', await p.getByText(/正在修改中/).count())
await b.close()
