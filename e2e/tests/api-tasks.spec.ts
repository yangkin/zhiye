// 接口级端到端：停止/继续、失败/重试、幂等、动态列表编辑绑定（真实 Kimi）
import { test, expect } from '@playwright/test'
import { Api } from './helpers'

test.describe.configure({ mode: 'serial' })

let api: Api
test.beforeAll(async ({ playwright }) => {
  api = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await api.setFaults({})
})
test.afterAll(async () => { await api.setFaults({}).catch(() => {}) })

test('生成中停止 → 只留中断快照不产生正式版本 → 继续 → 只产生一个正式版本', async () => {
  const app = await api.createApp('做一个极简待办清单：可以输入新任务并添加，标记完成，删除任务，顶部显示完成进度。', '日常开发')
  const key = crypto.randomUUID()
  const task = await api.post(`/api/apps/${app.id}/generate`, {}, { 'Idempotency-Key': key })
  // 同一个幂等键重复提交 → 同一个任务
  const dup = await api.post(`/api/apps/${app.id}/generate`, {}, { 'Idempotency-Key': key })
  expect(dup.id).toBe(task.id)

  // 等 agent 真正开始写代码再停止
  await api.waitTask(task.id, { timeoutMs: 5 * 60_000, until: (t) => t.status === 'RUNNING' || t.status === 'VERIFYING' || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status) })
  await new Promise((r) => setTimeout(r, 15_000))
  const c = await api.post(`/api/tasks/${task.id}/cancel`)
  expect(['CANCELLING', 'CANCELLED']).toContain(c.status)
  const stopped = await api.waitTask(task.id, { timeoutMs: 60_000 })
  expect(stopped.status).toBe('CANCELLED')
  expect(stopped.resumable).toBe(true)
  // 停止时只保留一份「任务中断前自动保存」快照，不算正式可用版本（isCurrent=false）
  const afterStop = await api.get(`/api/apps/${app.id}/versions`)
  expect(afterStop.filter((v: any) => v.source !== 'interrupted')).toHaveLength(0)
  for (const v of afterStop) expect(v.isCurrent).toBe(false)
  const mid = await api.get(`/api/apps/${app.id}`)
  expect(mid.phase).not.toBe('ready')
  expect(mid.activeTask).toBeNull()

  const resumed = await api.post(`/api/tasks/${task.id}/resume`)
  expect(resumed.id).not.toBe(task.id)
  const done = await api.waitTask(resumed.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  // 继续之后只多出一个正式版本；中断快照保留但不重复计数
  const versions = await api.get(`/api/apps/${app.id}/versions`)
  expect(versions.filter((v: any) => v.source !== 'interrupted')).toHaveLength(1)
  const current = versions.find((v: any) => v.isCurrent)
  expect(current, '继续之后应有一个当前版本').toBeTruthy()
  expect(current.source).not.toBe('interrupted')
  ;(globalThis as any).__todo = await api.get(`/api/apps/${app.id}`)
})

test('模型服务故障 → 任务失败、保留输入、无版本 → 重试成功且不重复', async () => {
  const app = await api.createApp('做一个预约表单页面：选择服务项目、日期和时间，填写手机号，提交后显示预约成功提示，手机号为空时提示。', '开发网站')
  await api.setFaults({ llm: 'error' })
  const task = await api.post(`/api/apps/${app.id}/generate`)
  const failed = await api.waitTask(task.id, { timeoutMs: 5 * 60_000 })
  await api.setFaults({ llm: 'none' })
  expect(failed.status).toBe('FAILED')
  expect(failed.retryable).toBe(true)
  expect(failed.error?.message).toBeTruthy()
  expect(await api.get(`/api/apps/${app.id}/versions`)).toHaveLength(0)
  expect((await api.get(`/api/apps/${app.id}`)).phase).toBe('failed')

  // 失败时对话里有一条「生成失败」的系统提示
  const failedMsgs = await api.get(`/api/apps/${app.id}/messages`)
  expect(failedMsgs.some((m: any) => m.role === 'system' && /生成失败/.test(m.content))).toBe(true)

  const retry = await api.post(`/api/tasks/${task.id}/retry`)
  const done = await api.waitTask(retry.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  expect((await api.get(`/api/apps/${app.id}/versions`)).filter((v: any) => v.source !== 'interrupted')).toHaveLength(1)
  // 重试成功之后不再留着那条失败提示
  const okMsgs = await api.get(`/api/apps/${app.id}/messages`)
  expect(okMsgs.some((m: any) => m.role === 'system' && /生成失败/.test(m.content))).toBe(false)
})

test('对话修改失败时保留用户消息，不生成假版本', async () => {
  const app = (globalThis as any).__todo
  await api.setFaults({ llm: 'error' })
  const { message, task } = await api.post(`/api/apps/${app.id}/messages`, { content: '把标题改成“今日清单”' })
  const failed = await api.waitTask(task.id, { timeoutMs: 5 * 60_000 })
  await api.setFaults({ llm: 'none' })
  expect(failed.status).toBe('FAILED')
  const msgs = await api.get(`/api/apps/${app.id}/messages`)
  const mine = msgs.find((m: any) => m.id === message.id)
  expect(mine.content).toBe('把标题改成“今日清单”')
  expect(mine.status).toBe('failed')
  expect((await api.get(`/api/apps/${app.id}/versions`)).filter((v: any) => v.source !== 'interrupted')).toHaveLength(1)
})

test('构建失败 → 交回修复超过轮数后任务失败，不产生版本', async () => {
  const app = (globalThis as any).__todo
  await api.setFaults({ build: 'error' })
  const { task } = await api.post(`/api/apps/${app.id}/messages`, { content: '把“添加”按钮改成圆角 16px' })
  const failed = await api.waitTask(task.id)
  await api.setFaults({ build: 'none' })
  expect(failed.status).toBe('FAILED')
  const events = await api.events(task.id)
  expect(events.filter((e) => e.type === 'verify' && e.payload.checks.some((c: any) => !c.ok)).length).toBeGreaterThan(0)
  expect((await api.get(`/api/apps/${app.id}/versions`)).filter((v: any) => v.source !== 'interrupted')).toHaveLength(1)
})

test('动态列表重绘、删除相邻元素后，可视化修改仍绑定正确元素', async ({ page }) => {
  const app = (globalThis as any).__todo
  await page.goto(app.previewUrl)
  const input = page.locator('input[type="text"], input:not([type])').first()
  const add = page.getByRole('button', { name: /添加|新增/ }).first()
  for (const t of ['买牛奶', '写周报', '跑步']) { await input.fill(t); await add.click() }
  await expect(page.getByText('写周报')).toBeVisible()
  const h1 = page.locator('h1').first()
  const loc = await h1.getAttribute('data-loc')
  // 编辑桥：在顶层页面中 window.parent === window，可以直接发消息
  await page.evaluate((l) => window.postMessage({ channel: 'zhiye-editor', type: 'apply-patches', patches: [{ loc: l, text: '我的清单', styles: { color: 'rgb(79, 120, 235)' } }] }, '*'), loc)
  await expect(h1).toHaveText('我的清单')
  // 增加任务、删除相邻任务触发重绘
  await input.fill('读书'); await add.click()
  // 删除与「读书」相邻的「写周报」（不依赖新任务加在列表头还是尾）
  await page.locator('li', { hasText: '写周报' }).getByRole('button', { name: /删除/ }).click()
  await expect(page.getByText('写周报')).toHaveCount(0)
  await expect(h1).toHaveText('我的清单')
  expect(await h1.evaluate((el) => getComputedStyle(el).color)).toBe('rgb(79, 120, 235)')
  // 列表项文字没有被误改
  await expect(page.getByText('读书')).toBeVisible()
  const items = await page.locator('li').allTextContents()
  expect(items.join('|')).not.toContain('我的清单')
  // 撤销：发送空补丁列表恢复原样
  await page.evaluate(() => window.postMessage({ channel: 'zhiye-editor', type: 'apply-patches', patches: [] }, '*'))
  await expect(h1).not.toHaveText('我的清单')
})

test('列表中只改一项：编辑桥按实例索引只改选中的那一项，其他同类项不变', async ({ page }) => {
  const app = (globalThis as any).__todo
  await page.goto(app.previewUrl)
  const input = page.locator('input[type="text"], input:not([type])').first()
  const add = page.getByRole('button', { name: /添加|新增/ }).first()
  for (const t of ['甲任务', '乙任务', '丙任务']) { await input.fill(t); await add.click() }
  const item = page.locator('li', { hasText: '乙任务' })
  const loc = await item.getAttribute('data-loc')
  const all = page.locator(`li[data-loc="${loc}"]`)
  const index = await item.evaluate((el, l) => [...document.querySelectorAll(`[data-loc="${l}"]`)].indexOf(el), loc)
  await page.evaluate(([l, i]) => window.postMessage({ channel: 'zhiye-editor', type: 'apply-patches', patches: [{ loc: l, index: i, match: '乙任务', styles: { color: 'rgb(214, 36, 36)' } }] }, '*'), [loc, index] as const)
  await expect.poll(() => item.evaluate((el) => getComputedStyle(el).color)).toBe('rgb(214, 36, 36)')
  const colors = await all.evaluateAll((els) => els.map((e) => getComputedStyle(e).color))
  expect(colors.filter((c) => c === 'rgb(214, 36, 36)')).toHaveLength(1)
  // 删除前面的项触发重绘后，仍然绑定在「乙任务」上
  await page.locator('li', { hasText: '甲任务' }).getByRole('button', { name: /删除/ }).click()
  await expect.poll(() => page.locator('li', { hasText: '乙任务' }).evaluate((el) => getComputedStyle(el).color)).toBe('rgb(214, 36, 36)')
  const after = await page.locator(`li[data-loc="${loc}"]`).evaluateAll((els) => els.map((e) => getComputedStyle(e).color))
  expect(after.filter((c) => c === 'rgb(214, 36, 36)')).toHaveLength(1)
})

test('AI 修改与手动修改不冲突：先把手动草稿存为一个版本，再做 AI 修改；运行中不能改草稿', async ({ page }) => {
  const app = await api.get(`/api/apps/${(globalThis as any).__todo.id}`)
  await page.goto(app.previewUrl)
  const h1 = await page.locator('h1').first().getAttribute('data-loc')
  const startN = app.currentVersion.number
  await api.put(`/api/apps/${app.id}/draft`, { baseVersionId: app.currentVersion.id, patches: [{ loc: h1, text: '手动改的标题' }] })
  const { task } = await api.post(`/api/apps/${app.id}/messages`, { content: '在页面底部加一行小字“由织页生成”' })
  // 运行中：草稿、代码保存都被拒绝
  await api.expectError('PUT', `/api/apps/${app.id}/draft`, { baseVersionId: app.currentVersion.id, patches: [{ loc: h1, text: '中途再改' }] }, 409, 'APP_BUSY')
  const done = await api.waitTask(task.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const versions = await api.get(`/api/apps/${app.id}/versions`)
  const added = versions.filter((v: any) => v.number > startN).sort((a: any, b: any) => a.number - b.number)
  expect(added.map((v: any) => v.source)).toEqual(['visual', 'modify'])
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.hasDraft).toBe(false)
  await page.goto(detail.previewUrl)
  await expect(page.locator('h1').first()).toHaveText('手动改的标题')
  await expect(page.locator('body')).toContainText('由织页生成')
})
