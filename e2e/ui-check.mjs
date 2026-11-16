// 临时 UI 自检脚本：截图到 e2e/test-results/ui-check/。运行：cd zhiye/e2e && node ui-check.mjs
import { chromium } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080'
const OUT = fileURLToPath(new URL('./test-results/ui-check/', import.meta.url))
mkdirSync(OUT, { recursive: true })
const shot = async (page, name) => {
  const p = OUT + name + '.png'
  await page.screenshot({ path: p })
  console.log('screenshot', p)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE })
const page = await ctx.newPage()
page.on('pageerror', (e) => console.log('PAGEERROR', e.message))
page.on('console', (m) => m.type() === 'error' && console.log('CONSOLE', m.text()))
const req = page.request
const created = []

async function json(method, path, body) {
  const res = await req.fetch(BASE + path, { method, data: body, headers: { 'Idempotency-Key': crypto.randomUUID() } })
  const t = await res.text()
  if (!res.ok()) throw new Error(`${method} ${path} ${res.status()} ${t}`)
  return t ? JSON.parse(t) : null
}
async function waitTask(id, ms = 180000) {
  const start = Date.now()
  for (;;) {
    const t = await json('GET', '/api/tasks/' + id)
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
    if (Date.now() - start > ms) throw new Error('task timeout ' + t.status)
    await sleep(1000)
  }
}

try {
  await page.goto(BASE + '/apps')
  await page.waitForLoadState('networkidle')
  const step = process.argv[2] || 'all'

  // ---- 导入一个现成应用 ----
  let appId = process.env.APP_ID
  if (!appId) {
    const up = await req.post(BASE + '/api/imports/upload', {
      multipart: { file: { name: 'garden.html', mimeType: 'text/html', buffer: Buffer.from(readFileSync(new URL('./fixtures/garden.html', import.meta.url), 'utf8').replace('</body>', `<!-- ${Date.now()} --></body>`)) } },
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    if (!up.ok()) throw new Error('upload ' + up.status() + (await up.text()))
    const manifest = await up.json()
    const { app, task } = await json('POST', '/api/imports', { uploadId: manifest.uploadId, name: 'UI自检花园' })
    created.push(app.id)
    appId = app.id
    const done = await waitTask(task.id)
    console.log('import', done.status)
  }

  // ---- 应用页：下拉 ----
  await page.goto(BASE + '/apps')
  await page.getByRole('combobox', { name: '发布状态' }).waitFor()
  await shot(page, '01-apps')
  await page.getByRole('combobox', { name: '发布状态' }).click()
  await page.getByRole('listbox').waitFor()
  await sleep(250)
  await shot(page, '02-apps-select-open')
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  console.log('status now', await page.getByRole('combobox', { name: '发布状态' }).textContent())
  await page.getByRole('combobox', { name: '发布状态' }).click()
  await page.getByRole('option', { name: '全部' }).click()
  await page.getByRole('combobox', { name: '排序方式' }).click()
  await sleep(250)
  await shot(page, '03-apps-sort-open')
  await page.keyboard.press('Escape')
  await page.locator('.app-card').first().hover()
  await shot(page, '04-apps-card-more')
  await page.getByRole('button', { name: /更多操作$/ }).first().click()
  await sleep(200)
  await shot(page, '05-apps-card-menu')
  await page.keyboard.press('Escape')

  // ---- 创造页 ＋ 菜单 ----
  await page.goto(BASE + '/create')
  await page.getByRole('button', { name: '添加素材' }).first().click()
  await page.getByRole('menuitem', { name: '技能' }).hover()
  await sleep(500)
  await shot(page, '06-create-add-menu-skill')
  console.log('create menu items', await page.getByRole('menu', { name: '添加素材' }).locator(':scope > [role=menuitem]').allTextContents())
  await page.keyboard.press('Escape')

  // ---- 工作台 ----
  await page.goto(BASE + '/apps/' + appId)
  await page.locator('iframe[data-preview]').waitFor()
  await sleep(1500)
  await shot(page, '10-workbench')
  // 悬停用户消息
  const bubble = page.locator('.user-message .msg-bubble').first()
  await bubble.hover()
  await sleep(300)
  await shot(page, '11-user-msg-hover')
  await page.getByRole('button', { name: '添加素材' }).click()
  await sleep(200)
  console.log('workbench menu items', await page.getByRole('menu', { name: '添加素材' }).locator(':scope > [role=menuitem]').allTextContents())
  await page.getByRole('menuitem', { name: '技能' }).hover()
  await sleep(500)
  await shot(page, '12-workbench-add-menu')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '复制消息' }).first().click()
  await page.getByText('已复制').first().waitFor({ timeout: 3000 }).then(() => console.log('copy toast ok')).catch(() => console.log('copy toast MISSING'))

  await page.getByRole('button', { name: '对话修改' }).click()
  await sleep(700)
  await page.frameLocator('iframe[data-preview]').locator('p').click()
  await page.getByLabel('对元素的修改描述').waitFor({ timeout: 8000 })
  await shot(page, '13-chat-edit-bar')
  await page.getByRole('button', { name: '对话修改' }).click()
  await sleep(300)

  // ---- 手动微调：改文字 → 外边距 → 撤销 → 重做 ----
  await page.getByRole('button', { name: '手动微调' }).click()
  const frame = page.frameLocator('iframe[data-preview]')
  await sleep(800)
  await frame.locator('h1').click()
  const panel = page.getByRole('region', { name: '编辑元素' })
  await panel.waitFor({ timeout: 10000 })
  await shot(page, '20-manual-panel')
  const text = panel.getByLabel('元素文字')
  await text.fill('让灵感，慢慢生长。')
  await text.blur()
  await sleep(400)
  const mt = panel.getByLabel('外边距-上')
  await mt.fill('24')
  await mt.press('Enter')
  await sleep(600)
  const marginOf = () => frame.locator('h1').evaluate((el) => getComputedStyle(el).marginTop)
  console.log('after edit margin', await marginOf(), 'field', await mt.inputValue())
  await panel.getByRole('button', { name: '撤销修改' }).click()
  await sleep(600)
  console.log('after undo margin', await marginOf(), 'field', await panel.getByLabel('外边距-上').inputValue())
  const redo = panel.getByRole('button', { name: '重做修改' })
  console.log('redo enabled', await redo.isEnabled())
  await shot(page, '21-after-undo')
  await redo.click({ timeout: 5000 })
  await sleep(600)
  console.log('after redo margin', await marginOf(), 'field', await panel.getByLabel('外边距-上').inputValue())
  await panel.getByRole('combobox', { name: '字重' }).click()
  await sleep(200)
  await shot(page, '22-weight-select')
  await page.keyboard.press('Escape')
  // 应用 → visual_save → 「修改已保存」卡片
  await page.getByRole('button', { name: '应用', exact: true }).click()
  await page.getByText('修改已保存').waitFor({ timeout: 120000 }).then(() => console.log('saved card ok')).catch(() => console.log('saved card MISSING'))
  await sleep(300)
  await shot(page, '23-saved-card')
  console.log('h1 margin after save', await marginOf(), 'text', await frame.locator('h1').textContent())
  await page.getByRole('button', { name: '手动微调' }).click()

  // ---- 任务卡停止按钮：发一条修改，马上停止 ----
  if (step === 'all') {
    await page.getByRole('textbox', { name: '修改需求' }).fill('把标题颜色改成深绿色')
    await page.getByRole('button', { name: '提交修改需求' }).click()
    const stop = page.getByRole('button', { name: '停止', exact: true })
    await stop.waitFor({ timeout: 20000 })
    await sleep(500)
    await shot(page, '30-task-stop-pill')
    await stop.hover()
    await shot(page, '31-task-stop-hover')
    await page.getByRole('button', { name: '停止当前任务' }).waitFor({ timeout: 5000 }).then(() => console.log('composer stop ok')).catch(() => console.log('composer stop MISSING'))
    await page.getByRole('textbox', { name: '修改需求' }).fill('草稿还在')
    await page.getByRole('textbox', { name: '修改需求' }).press('Enter')
    console.log('draft kept', await page.getByRole('textbox', { name: '修改需求' }).inputValue())
    await shot(page, '34-composer-stop')
    await stop.click()
    await page.getByRole('button', { name: /继续|重试/ }).first().waitFor({ timeout: 60000 }).catch(() => console.log('no resume/retry pill'))
    await sleep(800)
    await shot(page, '32-task-after-stop')
    const userMsgs = page.locator('.user-message .msg-bubble')
    await userMsgs.last().hover()
    await sleep(300)
    await shot(page, '33-msg-hover-edit')
    await page.getByRole('button', { name: '编辑消息' }).last().click()
    console.log('draft after edit', await page.getByRole('textbox', { name: '修改需求' }).inputValue())
  }
} catch (e) {
  console.log('ERROR', e.message)
  await shot(page, '99-error').catch(() => {})
} finally {
  for (const id of created) {
    for (let i = 0; i < 20; i++) {
      const r = await req.fetch(BASE + '/api/apps/' + id, { method: 'DELETE' })
      if (r.ok() || r.status() === 404) { console.log('deleted', id); break }
      await sleep(3000)
    }
  }
  await browser.close()
}
