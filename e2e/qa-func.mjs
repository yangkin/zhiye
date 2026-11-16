// QA 功能回归（不调模型）：独立的新工作空间，导入两个静态应用做 A/B。运行：cd zhiye/e2e && node qa-func.mjs
import { chromium } from '@playwright/test'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = 'http://127.0.0.1:8080'
const OUT = fileURLToPath(new URL('./test-results/qa-func/', import.meta.url))
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const results = []
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const page = await ctx.newPage()
const pageErrors = []
const nativeDialogs = []
page.on('pageerror', (e) => pageErrors.push(e.message))
page.on('dialog', (d) => { nativeDialogs.push(d.type() + ':' + d.message()); void d.dismiss() })
const req = page.request

async function check(id, name, fn) {
  const t0 = Date.now()
  try {
    const detail = await fn()
    results.push({ id, name, ok: true, detail: detail ?? '' })
    console.log('PASS', id, name, detail ?? '')
  } catch (e) {
    results.push({ id, name, ok: false, detail: String(e?.message || e).slice(0, 400) })
    console.log('FAIL', id, name, String(e?.message || e).slice(0, 400))
    await page.screenshot({ path: OUT + id + '.png' }).catch(() => {})
  }
  void t0
}
const assert = (c, msg) => { if (!c) throw new Error(msg) }
async function json(method, path, body) {
  const res = await req.fetch(BASE + path, { method, data: body, headers: { 'Idempotency-Key': crypto.randomUUID() } })
  const t = await res.text()
  if (!res.ok()) throw new Error(`${method} ${path} ${res.status()} ${t.slice(0, 200)}`)
  return t ? JSON.parse(t) : null
}
async function waitTask(id, ms = 240000) {
  const start = Date.now()
  for (;;) {
    const t = await json('GET', '/api/tasks/' + id)
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
    if (Date.now() - start > ms) throw new Error('task timeout ' + t.status)
    await sleep(1000)
  }
}
async function importApp(name, marker) {
  const html = readFileSync(new URL('./fixtures/garden.html', import.meta.url), 'utf8').replace('</body>', `<p id="qa-marker">${marker}</p></body>`)
  const up = await req.post(BASE + '/api/imports/upload', {
    multipart: { file: { name: 'index.html', mimeType: 'text/html', buffer: Buffer.from(html) } },
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  })
  if (!up.ok()) throw new Error('upload ' + up.status())
  const { app, task } = await json('POST', '/api/imports', { uploadId: (await up.json()).uploadId, name })
  const done = await waitTask(task.id)
  if (done.status !== 'SUCCEEDED') throw new Error('import ' + JSON.stringify(done.error))
  return app.id
}
const textarea = () => page.getByLabel('应用需求')
const draftKeys = () => page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('zhiye.draft.')))

await page.goto(BASE + '/create')
await page.waitForLoadState('networkidle')
const ws = await json('GET', '/api/workspace')
console.log('workspace', ws.id ?? JSON.stringify(ws).slice(0, 120))
const A = await importApp('QA甲应用', 'MARK-A')
const B = await importApp('QA乙应用', 'MARK-B')
console.log('apps', A, B)

// ---------------- 创造页 ----------------
await check('C01', '创造页：空需求不可发送', async () => {
  await page.goto(BASE + '/create')
  await textarea().fill('')
  assert(await page.getByRole('button', { name: '发送', exact: true }).isDisabled(), '空需求时发送可点')
})
await check('C02', '创造页：草稿和类型刷新、跳转后保留', async () => {
  await page.goto(BASE + '/create')
  await page.getByRole('tablist', { name: '应用类型（可不选）' }).getByRole('tab', { name: '开发网站' }).click()
  await textarea().fill('QA 草稿：设备点检台账')
  await page.reload()
  await page.waitForLoadState('networkidle')
  const v1 = await textarea().inputValue()
  const s1 = await page.getByRole('tab', { name: '开发网站' }).getAttribute('aria-selected')
  await page.goto(BASE + '/apps')
  await page.goBack()
  const v2 = await textarea().inputValue()
  const s2 = await page.getByRole('tab', { name: '开发网站' }).getAttribute('aria-selected')
  assert(v1 === 'QA 草稿：设备点检台账' && s1 === 'true', `刷新后 ${v1} ${s1}`)
  assert(v2 === 'QA 草稿：设备点检台账' && s2 === 'true', `跳转后 ${v2} ${s2}`)
  return `refresh=[${v1},${s1}] nav=[${v2},${s2}]`
})
await check('C03', '创造页：点推荐覆盖草稿可撤销', async () => {
  await page.locator('.prompt-chip').first().click()
  const after = await textarea().inputValue()
  assert(after !== 'QA 草稿：设备点检台账', '推荐没有填入')
  await page.getByRole('button', { name: '撤销' }).click()
  const back = await textarea().inputValue()
  assert(back === 'QA 草稿：设备点检台账', '撤销后是 ' + back)
  return after.slice(0, 16) + ' → ' + back
})
await check('C04', '创造页：再点一次类型取消选择，刷新后仍是未选', async () => {
  await page.getByRole('tab', { name: '开发网站' }).click()
  await page.reload()
  const s = await page.getByRole('tab', { name: '开发网站' }).getAttribute('aria-selected')
  assert(s === 'false', 'aria-selected=' + s)
  await textarea().fill('')
})

// ---------------- 会话页（接口打桩） ----------------
const conv = await json('POST', '/api/conversations', {})
const sse = (parts) => parts.map(([e, d]) => `event: ${e}\ndata: ${JSON.stringify(d)}\n\n`).join('')
let buildCalls = 0
page.on('request', (r) => { if (/\/api\/conversations\/[^/]+\/build/.test(r.url())) buildCalls++ })
await check('C05', '会话页：流没有 done 就断开 → 提示回答中断，不自动建应用', async () => {
  await page.route('**/api/conversations/*/ask', (r) => r.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([['delta', { text: '[[build:日常开发]] 好的，我来' }]]) }))
  await page.goto(BASE + '/chat/' + conv.id)
  const box = page.getByLabel('继续输入消息')
  await box.fill('做一个点检台账')
  await page.getByRole('button', { name: '发送消息' }).click()
  await page.getByText('回答中断了，没有收到完整内容，请重试。').first().waitFor({ timeout: 10000 })
  await sleep(1500)
  assert(buildCalls === 0, 'build 调用次数 ' + buildCalls)
  assert(await page.getByRole('button', { name: /重试/ }).count() > 0, '没有重试按钮')
  await page.unroute('**/api/conversations/*/ask')
  return 'build=' + buildCalls
})
await check('C06', '会话页：发送失败保留原文，重试原样重发', async () => {
  const bodies = []
  await page.route('**/api/conversations/*/ask', (r) => { bodies.push(r.request().postDataJSON()); return r.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'X', message: '模拟上游失败' }) }) })
  await page.goto(BASE + '/chat/' + conv.id)
  await page.getByLabel('继续输入消息').fill('QA 重试原文')
  await page.getByRole('button', { name: '发送消息' }).click()
  await page.getByText('模拟上游失败').first().waitFor({ timeout: 10000 })
  await page.unroute('**/api/conversations/*/ask')
  await page.route('**/api/conversations/*/ask', (r) => { bodies.push(r.request().postDataJSON()); return r.fulfill({ status: 200, contentType: 'text/event-stream', body: sse([['delta', { text: 'QA桩回答完成' }], ['done', {}]]) }) })
  await page.getByRole('button', { name: /重试/ }).last().click()
  await page.getByText('QA桩回答完成').first().waitFor({ timeout: 10000 })
  await page.unroute('**/api/conversations/*/ask')
  assert(bodies.length === 2 && bodies[1].content === 'QA 重试原文', JSON.stringify(bodies))
  return `requests=${bodies.length} content=${bodies[1].content}`
})
await check('C07', '会话页：等第一个字时可以停止回答', async () => {
  let release
  await page.route('**/api/conversations/*/ask', (r) => new Promise((res) => { release = () => { r.abort().catch(() => {}); res() } }))
  await page.goto(BASE + '/chat/' + conv.id)
  await page.getByLabel('继续输入消息').fill('QA 停止测试')
  await page.getByRole('button', { name: '发送消息' }).click()
  const stop = page.getByRole('button', { name: '停止回答' })
  await stop.waitFor({ timeout: 5000 })
  await stop.click()
  await page.getByText(/已停止回答/).first().waitFor({ timeout: 5000 })
  const enabled = await page.getByLabel('继续输入消息').isEnabled()
  release?.()
  await page.unroute('**/api/conversations/*/ask')
  assert(enabled, '停止后输入框不可用')
})
await check('C08', '历史页：会话摘要不露出 [[build:类型]]', async () => {
  await page.route('**/api/conversations', async (r) => {
    if (r.request().method() !== 'GET') return r.continue()
    const res = await r.fetch()
    const list = await res.json()
    list.unshift({ id: conv.id, title: 'QA 标记', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), lastMessage: '[[build:日常开发]]\n好的，开始做点检台账', messageCount: 2, fileCount: 0 })
    return r.fulfill({ response: res, json: list })
  })
  await page.goto(BASE + '/history')
  await page.getByRole('button', { name: '会话记录' }).click()
  await page.getByText('好的，开始做点检台账').first().waitFor({ timeout: 10000 })
  const body = await page.locator('main').innerText()
  await page.unroute('**/api/conversations')
  assert(!/\[\[build/.test(body), '页面上出现了 [[build')
})

// ---------------- 工作台 ----------------
const openApp = async (id) => { await page.goto(BASE + '/apps/' + id); await page.getByRole('tab', { name: '代码' }).waitFor({ timeout: 30000 }) }
await check('W01', '工作台：页签方向键 / Home / End', async () => {
  await openApp(A)
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByRole('tab', { name: '代码' }).focus()
  await page.keyboard.press('ArrowRight')
  const a = await page.evaluate(() => document.activeElement?.textContent?.trim())
  await page.keyboard.press('End')
  const b = await page.evaluate(() => document.activeElement?.textContent?.trim())
  await page.keyboard.press('Home')
  const c = await page.evaluate(() => document.activeElement?.textContent?.trim())
  const sel = await page.getByRole('tab', { selected: true }).first().textContent()
  assert(a === '预览' && /云服务/.test(b) && c === '代码', `${a}/${b}/${c}`)
  return `→${a} End→${b} Home→${c} selected=${sel?.trim()}`
})
await check('W02', '代码标题显示可编辑', async () => {
  await page.getByRole('tab', { name: '代码' }).click()
  const t = await page.getByText(/V\d+ · (可编辑|历史版本，只读|生成中，只读|修改中)/).first().textContent({ timeout: 15000 })
  assert(/可编辑/.test(t), t)
  return t
})
async function editIndex(text) {
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByText('index.html', { exact: true }).first().click()
  await page.getByRole('button', { name: '编辑文件' }).click()
  const cm = page.locator('.cm-content').first()
  await cm.waitFor({ timeout: 15000 })
  await cm.click()
  await page.keyboard.press('Meta+End')
  await page.keyboard.type(text)
}
await check('W03', '代码草稿：SPA 切到 B 再回 A、刷新后都还在；B 不受影响', async () => {
  await editIndex('\n<!-- QA-EDIT-A -->')
  await page.getByText('1 个文件有未保存的修改').waitFor({ timeout: 5000 })
  await page.getByRole('button', { name: '返回应用列表' }).click()
  await page.getByRole('button', { name: '打开 QA乙应用' }).first().click()
  await page.getByRole('tab', { name: '代码' }).click()
  const bBar = await page.getByText(/个文件有未保存的修改/).count()
  await page.getByRole('button', { name: '返回应用列表' }).click()
  await page.getByRole('button', { name: '打开 QA甲应用' }).first().click()
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByText('1 个文件有未保存的修改').waitFor({ timeout: 8000 })
  await page.reload()
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByText('1 个文件有未保存的修改').waitFor({ timeout: 8000 })
  assert(bBar === 0, 'B 也显示了未保存')
  return 'B 未保存条数=' + bBar
})
await check('W04', '聊天草稿按应用隔离，刷新保留', async () => {
  await openApp(A)
  await page.locator('#iteration-prompt').fill('甲的聊天草稿')
  await openApp(B)
  const b0 = await page.locator('#iteration-prompt').inputValue()
  await page.locator('#iteration-prompt').fill('乙的聊天草稿')
  await page.getByRole('button', { name: '返回应用列表' }).click()
  await page.getByRole('button', { name: '打开 QA甲应用' }).first().click()
  await page.locator('#iteration-prompt').waitFor()
  const a1 = await page.locator('#iteration-prompt').inputValue()
  await page.reload()
  await page.locator('#iteration-prompt').waitFor()
  const a2 = await page.locator('#iteration-prompt').inputValue()
  await page.locator('#iteration-prompt').fill('')
  await openApp(B)
  const b2 = await page.locator('#iteration-prompt').inputValue()
  await page.locator('#iteration-prompt').fill('')
  assert(b0 === '' && a1 === '甲的聊天草稿' && a2 === '甲的聊天草稿' && b2 === '乙的聊天草稿', `${b0}|${a1}|${a2}|${b2}`)
  return `B初始='${b0}' A回来='${a1}' A刷新='${a2}' B='${b2}'`
})
await check('W05', '代码草稿基于旧版本：显示「覆盖保存」，保存后内容正确、草稿清掉', async () => {
  const app = await json('GET', '/api/apps/' + A)
  const t = await json('POST', `/api/apps/${A}/code`, { baseVersionId: app.currentVersion.id, files: [{ path: 'notes.md', content: '# 外部更新\n' }] })
  const done = await waitTask(t.id)
  assert(done.status === 'SUCCEEDED', 'api 保存失败 ' + JSON.stringify(done.error))
  await openApp(A)
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByRole('button', { name: '保存代码修改' }).waitFor({ timeout: 10000 })
  const label = (await page.getByRole('button', { name: '保存代码修改' }).textContent()).trim()
  const warn = await page.getByText(/这些修改是在旧版本上做的/).count()
  assert(label === '覆盖保存' && warn > 0, `label=${label} warn=${warn}`)
  const before = (await json('GET', '/api/apps/' + A)).currentVersion.number
  await page.getByRole('button', { name: '保存代码修改' }).click()
  let now
  for (let i = 0; i < 120; i++) { now = await json('GET', '/api/apps/' + A); if (now.currentVersion.number > before && !now.activeTask) break; await sleep(1000) }
  await sleep(500)
  const file = await (await req.get(BASE + `/api/versions/${now.currentVersion.id}/file?path=index.html`)).text()
  const notes = await (await req.get(BASE + `/api/versions/${now.currentVersion.id}/file?path=notes.md`)).text()
  // 草稿在收到任务的 finished 事件后清掉，比版本号变化稍晚：最多等 5 秒
  let keys = await draftKeys()
  for (let i = 0; i < 25 && keys.includes('zhiye.draft.code.' + A); i++) { await sleep(200); keys = await draftKeys() }
  assert(file.includes('QA-EDIT-A'), '新版本里没有我的修改')
  assert(notes.includes('外部更新'), '覆盖保存把其他文件的外部更新弄丢了')
  assert(!keys.includes('zhiye.draft.code.' + A), '代码草稿没清掉')
  return `V${now.currentVersion.number} label=${label} 其他文件保留=${notes.includes('外部更新')}`
})
await check('W06', '历史版本：只读标题、编辑按钮禁用', async () => {
  await openApp(A)
  await page.getByRole('button', { name: /^版本历史 V\d+/ }).click()
  await page.getByRole('button', { name: /预览 QA甲应用 V1/ }).click()
  await sleep(800)
  await page.getByRole('tab', { name: '代码' }).click()
  const t = await page.getByText(/V\d+ · (可编辑|历史版本，只读|生成中，只读|修改中)/).first().textContent({ timeout: 10000 })
  await page.getByText('index.html', { exact: true }).first().click()
  const dis = await page.getByRole('button', { name: '编辑文件' }).isDisabled()
  assert(/V1 · 历史版本，只读/.test(t) && dis, `${t} disabled=${dis}`)
  return t
})
await check('W07', '预览刷新：打不开时保留画面并提示失败；恢复后提示成功', async () => {
  await openApp(A)
  await page.getByRole('tab', { name: '预览' }).click()
  const frame = page.frameLocator('iframe[title$="应用预览"]')
  await frame.locator('#qa-marker').waitFor({ timeout: 30000 })
  await page.route(/:8083\/p\//, (r) => r.abort())
  await page.getByRole('button', { name: '刷新预览' }).click()
  const fail = page.getByText(/预览暂时打不开/)
  await fail.first().waitFor({ timeout: 15000 })
  const okToast = await page.getByText('预览已刷新').count()
  const still = await frame.locator('#qa-marker').count()
  await page.unroute(/:8083\/p\//)
  await page.getByRole('button', { name: '刷新预览' }).click()
  await page.getByText(/预览已刷新/).first().waitFor({ timeout: 15000 })
  assert(okToast === 0 && still === 1, `失败时成功提示=${okToast} 画面保留=${still}`)
  return '失败→保留画面；恢复→已刷新'
})
await check('W08', '代码模块下载失败：局部报错可重试，左侧对话仍可用', async () => {
  const p2 = await ctx.newPage()
  p2.on('dialog', (d) => { nativeDialogs.push(d.type() + ':' + d.message()); void d.dismiss() })
  await p2.route(/\/assets\/CodePane-[^/]+\.js/, (r) => r.abort())
  await p2.goto(BASE + '/apps/' + A)
  await p2.getByRole('tab', { name: '代码' }).click()
  await p2.getByText(/没能打开|加载失败/).first().waitFor({ timeout: 15000 })
  const chatOk = await p2.locator('#iteration-prompt').isVisible()
  await p2.unroute(/\/assets\/CodePane-[^/]+\.js/)
  await p2.getByRole('button', { name: '重试' }).first().click()
  await p2.getByText('index.html', { exact: true }).first().waitFor({ timeout: 15000 })
  await p2.close()
  assert(chatOk, '对话区不可用')
})
async function focusTrap(openBtn, label) {
  await openBtn.click()
  const dlg = page.locator('[role="dialog"], [role="alertdialog"]').last()
  await dlg.waitFor({ timeout: 8000 })
  let outside = 0
  for (let i = 0; i < 25; i++) {
    await page.keyboard.press('Tab')
    if (!(await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"],[role="alertdialog"]')))) outside++
  }
  await page.keyboard.press('Escape')
  await dlg.waitFor({ state: 'detached', timeout: 5000 }).catch(() => {})
  const back = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') || document.activeElement?.textContent?.trim())
  return { outside, back, label }
}
await check('W09', '弹窗焦点：发布 / 版本历史 / 更多 / 下载 焦点不逃逸，Esc 后回到按钮', async () => {
  await openApp(A)
  const out = []
  out.push(await focusTrap(page.locator('.studio-publish'), '发布'))
  out.push(await focusTrap(page.getByRole('button', { name: /^版本历史 V\d+/ }), '版本历史'))
  out.push(await focusTrap(page.getByRole('button', { name: '更多工作台功能' }), '更多'))
  out.push(await focusTrap(page.getByRole('button', { name: '下载应用' }), '下载'))
  const bad = out.filter((o) => o.outside > 0 || !o.back)
  assert(!bad.length, JSON.stringify(out))
  return out.map((o) => `${o.label}:back=${o.back}`).join(' ')
})

// ---------------- 云服务 ----------------
let disableCalls = 0
page.on('request', (r) => { if (r.url().endsWith('/cloud/disable')) disableCalls++ })
await check('K01', '云服务：未启用时在面板启用', async () => {
  await openApp(A)
  await page.getByRole('tab', { name: '云服务' }).click()
  await page.getByText('未启用').first().waitFor({ timeout: 10000 })
  await page.locator('.studio-cloud').getByRole('button').filter({ hasText: /启用/ }).first().click()
  const card = page.locator('.composer-float .confirm-card')
  await card.waitFor({ timeout: 5000 })
  await card.getByRole('button', { name: '确认启用' }).click()
  await page.getByText('已启用 · 数据控制台').waitFor({ timeout: 20000 })
  const st = await json('GET', `/api/apps/${A}/cloud`)
  assert(st.enabled, 'status ' + JSON.stringify(st).slice(0, 100))
  const dot = await page.locator('.studio-tab-on').count()
  let app
  for (let i = 0; i < 90; i++) { app = await json('GET', '/api/apps/' + A); if (app.activeTask) break; await sleep(1000) }
  globalThis.cloudTask = app.activeTask?.id
  return `AI 设计表结构任务=${app.activeTask?.type}/${app.activeTask?.status} enabled=${st.enabled} tables=${st.tables?.map((t) => t.name)} 绿点=${dot}`
})
let table = 'notes'
await check('K02', '云服务：写入一条数据', async () => {
  const st = await json('GET', `/api/apps/${A}/cloud`)
  table = st.tables?.[0]?.name || 'notes'
  const cols = Object.keys(st.tables?.[0]?.columns || {}).filter((c) => !['id', 'created_at', 'updated_at', 'owner'].includes(c))
  const row = Object.fromEntries(cols.map((c) => [c, 'QA数据']))
  await json('POST', `/api/apps/${A}/cloud/console/${table}/rows`, row)
  const rows = await json('GET', `/api/apps/${A}/cloud/console/${table}/rows`)
  return `table=${table} cols=${cols} rows=${JSON.stringify(rows).slice(0, 80)}`
})
await check('K03', '关闭云服务（AI 正在设计表结构时）：取消不发请求；确认后显示未启用', async () => {
  await openApp(A)
  await page.getByRole('tab', { name: '云服务' }).click()
  await page.getByRole('button', { name: '关闭云服务' }).click()
  const dlg = page.getByRole('dialog', { name: /关闭云服务/ }).or(page.getByRole('alertdialog', { name: /关闭云服务/ }))
  await dlg.waitFor({ timeout: 5000 })
  await dlg.getByRole('button', { name: '取消' }).click()
  await sleep(800)
  const afterCancel = disableCalls
  await page.getByRole('button', { name: '关闭云服务' }).click()
  await dlg.getByRole('button', { name: '关闭云服务' }).click()
  await page.getByText('未启用').first().waitFor({ timeout: 15000 })
  const st = await json('GET', `/api/apps/${A}/cloud`)
  const dot = await page.locator('.studio-tab-on').count()
  assert(afterCancel === 0 && disableCalls === 1, `cancel后=${afterCancel} 总=${disableCalls}`)
  assert(!st.enabled, '服务端仍是启用')
  assert(dot === 0, '页签绿点还在')
  if (globalThis.cloudTask) {
    const t = await waitTask(globalThis.cloudTask, 600000)
    const after = await json('GET', `/api/apps/${A}/cloud`)
    assert(!after.enabled, `AI 任务（${t.status}）结束后云服务被重新打开了`)
    globalThis.cloudTaskStatus = t.status
  }
  return `AI任务结束=${globalThis.cloudTaskStatus} 仍关闭；cancel 请求=${afterCancel} confirm 请求=${disableCalls} 服务端=${st.status}`
})
await check('K04', '应用列表：关闭后不再显示云服务徽标', async () => {
  await page.goto(BASE + '/apps')
  const card = page.locator('.app-card, article').filter({ hasText: 'QA甲应用' }).first()
  await card.waitFor({ timeout: 10000 })
  const t = await card.innerText()
  assert(!/云服务/.test(t), '卡片仍显示云服务: ' + t.replace(/\n/g, ' '))
})
await check('K05', '重新启用：之前的数据还在', async () => {
  await json('POST', `/api/apps/${A}/cloud/enable`, {})
  const rows = await json('GET', `/api/apps/${A}/cloud/console/${table}/rows`)
  const s = JSON.stringify(rows)
  assert(s.includes('QA数据'), '数据丢了 ' + s.slice(0, 120))
})
await check('K06', '云控制台删除单条数据：页面内确认', async () => {
  await openApp(A)
  await page.getByRole('tab', { name: '云服务' }).click()
  const nav = page.getByRole('navigation', { name: '云服务' })
  await nav.getByRole('button', { name: /数据库|数据表|表/ }).first().click().catch(() => {})
  // 默认显示第一张表；AI 设计表结构后表可能变多、顺序变了，切到 K02 写入数据的那张表
  const pick = page.getByRole('combobox', { name: '选择数据表' })
  await pick.waitFor({ timeout: 10000 })
  if (!(await pick.innerText()).includes(table)) {
    await pick.click()
    await page.getByRole('option', { name: new RegExp('^' + table + '\\b') }).click()
  }
  await page.getByText('QA数据').first().waitFor({ timeout: 10000 })
  const n0 = nativeDialogs.length
  const del = page.locator('.cc-main').getByRole('button', { name: /删除/ }).first()
  await del.click()
  const dlg = page.locator('[role="dialog"],[role="alertdialog"]').last()
  await dlg.waitFor({ timeout: 5000 })
  const title = await dlg.innerText()
  await dlg.getByRole('button', { name: '取消' }).click()
  assert(nativeDialogs.length === n0, '弹了浏览器原生框')
  return title.split('\n')[0]
})
await check('K07', '任务进行中关闭云服务：任务结束后不会被重新打开', async () => {
  const app = await json('GET', '/api/apps/' + A)
  const t = await json('POST', `/api/apps/${A}/code`, { baseVersionId: app.currentVersion.id, files: [{ path: 'cloud/schema.sql', content: '-- QA 表\ncreate table qa_items (title text);\n' }] })
  await json('POST', `/api/apps/${A}/cloud/disable`, {})
  const done = await waitTask(t.id)
  const st = await json('GET', `/api/apps/${A}/cloud`)
  assert(!st.enabled, '任务结束后云服务被重新打开了')
  return `task=${done.status} cloud=${st.status}`
})

// ---------------- 应用 / 历史 / 社区 / 发布 / 恢复 ----------------
await check('A01', '发布：线上地址可访问且是最新内容', async () => {
  const r = await json('POST', `/api/apps/${A}/publish`, {})
  const html = await (await req.get(r.siteUrl)).text()
  assert(html.includes('MARK-A'), '线上内容不对')
  return r.siteUrl
})
let tplId
await check('A02', '社区：发布作品 → 列表 → 详情 → 返回保留排序 → 点赞', async () => {
  const tpl = await json('POST', '/api/templates', { appId: A, name: 'QA社区作品' + Date.now() % 10000, description: 'QA 测试' })
  tplId = tpl.id
  await page.goto(BASE + '/community?sort=new')
  await page.getByText(tpl.name).first().click()
  await page.waitForURL(/\/community\/|\/works?\//, { timeout: 10000 })
  const like = page.getByRole('button', { name: /点赞|赞/ }).first()
  await like.click()
  await sleep(800)
  const d = await json('GET', '/api/templates/' + tplId)
  await page.getByText('返回社区').first().click()
  await sleep(500)
  const url = page.url()
  assert(/sort=new/.test(url), '返回后 ' + url)
  return `likes=${d.likes ?? d.likeCount} back=${url.replace(BASE, '')}`
})
await check('A03', '社区：使用模板复刻出新应用', async () => {
  const r = await json('POST', `/api/templates/${tplId}/clone`, { name: 'QA复刻' })
  const appId = r.app.id
  const done = await waitTask(r.app.activeTask.id)
  const app = await json('GET', '/api/apps/' + appId)
  assert(done.status === 'SUCCEEDED' && app.phase === 'ready', `${done.status} ${app.phase}`)
  await json('DELETE', '/api/templates/' + tplId)
  return `clone=${done.status} phase=${app.phase}`
})
await check('A04', '删除应用：页面内确认，进回收站', async () => {
  await page.goto(BASE + '/apps')
  await page.getByRole('button', { name: 'QA复刻更多操作' }).first().click()
  await page.getByRole('menuitem', { name: '删除应用' }).or(page.getByText('删除应用')).first().click()
  const dlg = page.getByRole('dialog', { name: /删除/ })
  await dlg.waitFor({ timeout: 5000 })
  const copy = await dlg.innerText()
  await dlg.getByRole('button', { name: '删除', exact: true }).click()
  await dlg.waitFor({ state: 'detached', timeout: 10000 })
  const trash = await json('GET', '/api/apps/trash')
  await page.goto(BASE + '/history')
  await page.getByRole('button', { name: '最近删除' }).click()
  const row = page.locator('.version-row').filter({ hasText: 'QA复刻' })
  await row.first().waitFor({ timeout: 8000 })
  const restoreBtn = await row.first().getByRole('button').count()
  return `回收站条数=${trash.length} 行内按钮=${restoreBtn} 弹窗文案=${copy.replace(/\n/g, ' ').slice(0, 60)}`
})
await check('A05', '历史页删除会话：页面内确认', async () => {
  const c2 = await json('POST', '/api/conversations', {})
  void c2
  await page.goto(BASE + '/history')
  await page.getByRole('button', { name: '会话记录' }).click()
  const n0 = nativeDialogs.length
  const del = page.getByRole('button', { name: /删除会话/ }).first()
  if (!(await del.count())) return '没有可删除的会话（新会话无消息时不列出）'
  await del.click()
  const dlg = page.locator('[role="dialog"],[role="alertdialog"]').last()
  await dlg.waitFor({ timeout: 5000 })
  await dlg.getByRole('button', { name: '取消' }).click()
  assert(nativeDialogs.length === n0, '弹了原生框')
})
await check('A06', '恢复链接：新浏览器用恢复令牌找回同一工作空间', async () => {
  const w = await json('GET', '/api/workspace')
  const token = (w.recoveryUrl || '').split('#')[1]
  assert(token, '工作空间接口里没有恢复链接: ' + Object.keys(w))
  const c2 = await browser.newContext()
  const p = await c2.newPage()
  await p.goto(BASE + '/recover#' + token)
  await p.waitForURL((u) => !u.pathname.startsWith('/recover'), { timeout: 15000 })
  await p.waitForTimeout(1500)
  const w2 = await (await p.request.get(BASE + '/api/workspace')).json()
  const items = await (await p.request.get(BASE + '/api/apps')).json()
  const body = (await p.locator('body').innerText()).slice(0, 120).replace(/\n/g, ' ')
  await c2.close()
  assert(w2.id === w.id, `恢复后工作空间 ${w2.id} ≠ ${w.id}；url=${p.url()} 页面=${body}`)
  assert(JSON.stringify(items).includes('QA甲应用'), '恢复后看不到应用')
  return '恢复后应用数=' + items.length
})
await check('A08', '数据源：连不上的地址测试时给出可读错误', async () => {
  const src = await json('POST', '/api/sources', { key: 'qa_bad', name: 'QA坏数据源', description: 'qa', method: 'GET', url: 'http://127.0.0.1:9/x', params: [], sample: '', authType: 'none' }).catch((e) => ({ err: e.message }))
  if (src.err) return '创建被拒：' + src.err.slice(0, 160)
  const r = await req.fetch(BASE + `/api/sources/${src.id}/test`, { method: 'POST', data: { params: {} }, headers: { 'Idempotency-Key': crypto.randomUUID() } })
  const t = await r.text()
  await json('DELETE', '/api/sources/' + src.id)
  assert(r.status() !== 500, '500: ' + t.slice(0, 160))
  return `${r.status()} ${t.slice(0, 120)}`
})

// ---------------- 响应式 ----------------
const ALLOW = /mini-|thumbnail|avatar|step-dot|thumb/
for (const [w, h] of [[1440, 900], [1024, 720], [768, 1024], [390, 844]]) {
  await check('R' + w, `响应式 ${w}px：横向溢出 / 最小字号`, async () => {
    await page.setViewportSize({ width: w, height: h })
    const out = []
    for (const path of ['/create', '/apps', '/history', '/community', '/apps/' + B]) {
      await page.goto(BASE + path)
      await page.waitForLoadState('networkidle').catch(() => {})
      await sleep(600)
      const r = await page.evaluate((allow) => {
        const re = new RegExp(allow)
        const overflow = document.documentElement.scrollWidth - window.innerWidth
        let min = 99, where = ''
        for (const el of document.querySelectorAll('body *')) {
          if (!(el instanceof HTMLElement) || el.closest('iframe')) continue
          const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
          if (!own) continue
          const rect = el.getBoundingClientRect()
          if (!rect.width || !rect.height) continue
          const cs = getComputedStyle(el)
          if (cs.visibility === 'hidden' || cs.opacity === '0') continue
          let p = el, skip = false
          for (; p; p = p.parentElement) if (re.test(p.className?.toString?.() || '')) { skip = true; break }
          if (skip) continue
          const fs = parseFloat(cs.fontSize)
          // font-size:0 是窄屏只留图标的按钮（文字仍给读屏），不算可见文字
          if (fs === 0) continue
          if (fs < min) { min = fs; where = el.tagName.toLowerCase() + '.' + (el.className?.toString?.() || '').split(' ')[0] + ' "' + el.textContent.trim().slice(0, 12) + '"' }
        }
        return { overflow, min, where }
      }, ALLOW.source)
      out.push(`${path.replace(B, 'B')} 溢出${r.overflow} 最小${r.min}px ${r.min < 11 ? r.where : ''}`)
    }
    await page.screenshot({ path: OUT + `ws-${w}.png` })
    const bad = out.filter((s) => /溢出([1-9]\d*)/.test(s) || /最小([0-9](\.\d+)?)px/.test(s) && Number(s.match(/最小([\d.]+)px/)[1]) < 11)
    assert(!bad.length, bad.join(' | '))
    return out.join(' | ')
  })
}
await check('R390-ws', '手机工作台：对话/代码/预览直接切换', async () => {
  await page.setViewportSize({ width: 390, height: 844 })
  await openApp(B)
  await page.getByRole('tab', { name: '代码' }).click()
  await sleep(400)
  const code = await page.locator('.code-body, .studio-code, [aria-label="代码视图"]').first().boundingBox()
  const chatVisible = await page.locator('#iteration-prompt').isVisible()
  await page.getByRole('tab', { name: '对话' }).click()
  await sleep(400)
  const chatBack = await page.locator('#iteration-prompt').isVisible()
  assert(code && code.y < 844 && !chatVisible && chatBack, `code=${JSON.stringify(code)} chat=${chatVisible} back=${chatBack}`)
  return `代码区 y=${Math.round(code.y)} 对话隐藏=${!chatVisible} 切回=${chatBack}`
})
await check('R768-code', '平板：代码编辑区宽度', async () => {
  await page.setViewportSize({ width: 768, height: 1024 })
  await openApp(B)
  await page.getByRole('tab', { name: '代码' }).click()
  await page.getByText('index.html', { exact: true }).first().click()
  const b = await page.locator('.code-body').first().boundingBox()
  assert(b && b.width >= 360, 'width=' + b?.width)
  return 'code-body width=' + Math.round(b.width)
})

await check('Z01', '全程没有浏览器原生对话框', async () => { assert(!nativeDialogs.length, nativeDialogs.join(' | ')) })
await check('Z02', '全程没有未捕获的页面错误', async () => { assert(!pageErrors.length, pageErrors.slice(0, 5).join(' | ')) })

writeFileSync(OUT + 'results.json', JSON.stringify({ A, B, results }, null, 2))
console.log(`\n${results.filter((r) => r.ok).length}/${results.length} passed`)
await browser.close()
