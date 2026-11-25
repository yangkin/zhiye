// 接口级端到端：创造 → 生成 → 修改 → 版本 → 发布 闭环（真实 Kimi）
import { test, expect, request as pwRequest } from '@playwright/test'
import { Api, rgbOf, SITE_PORT, siteGet } from './helpers'

test.describe.configure({ mode: 'serial' })

let api: Api
const s: any = {} // 跨用例共享状态

test.beforeAll(async ({ playwright }) => {
  api = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await api.setFaults({}).catch(() => {})
})

test('免登录访问，元信息与工作空间可用', async () => {
  const meta = await api.get('/api/meta')
  expect(meta.models.map((m: any) => m.id)).toContain('auto')
  expect(meta.modes).toEqual(['快速', '适中', '深度'])
  const ws = await api.get('/api/workspace')
  expect(ws.id).toBeTruthy()
  expect(ws.recoveryUrl).toContain('/recover#')
  s.recoveryUrl = ws.recoveryUrl
})

test('空白需求不能创建', async () => {
  await api.expectError('POST', '/api/apps', { type: '开发网站', prompt: '   ', mode: '快速', model: 'auto', assetIds: [] }, 400)
})

test('创建点击计数应用：计划 → 生成 → 版本 → 预览可交互', async ({ page }) => {
  const app = await api.createApp('做一个点击计数器应用：标题“点击计数器”，副标题一句话说明，显示当前点击次数，有“加一”按钮和“重置”按钮，次数为 0 时重置按钮禁用。')
  s.counter = app
  // 编辑计划文档
  const edited = await api.patch(`/api/apps/${app.id}`, { planDoc: app.planDoc + '\n\n补充：按钮使用圆角。', style: 'lavender' })
  expect(edited.planDoc).toContain('补充：按钮使用圆角')

  const task = await api.post(`/api/apps/${app.id}/generate`)
  // 生成中再次提交写任务 → APP_BUSY
  await api.expectError('POST', `/api/apps/${app.id}/messages`, { content: '把标题改成红色' }, 409, 'APP_BUSY')
  const done = await api.waitTask(task.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  expect(done.resultVersionId).toBeTruthy()

  const versions = await api.get(`/api/apps/${app.id}/versions`)
  expect(versions).toHaveLength(1)
  expect(versions[0].number).toBe(1)
  expect(versions[0].isCurrent).toBe(true)
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.phase).toBe('ready')
  expect(detail.previewUrl).toMatch(new RegExp(`^http://127\\.0\\.0\\.1:${SITE_PORT}/p/`))
  s.counter = detail

  // 事件：真实阶段、工具调用、校验结果，seq 递增，最后是 finished
  const events = await api.events(task.id)
  expect(events.length).toBeGreaterThan(3)
  for (let i = 1; i < events.length; i++) expect(events[i].seq).toBeGreaterThan(events[i - 1].seq)
  expect(events.some((e) => e.type === 'tool')).toBeTruthy()
  expect(events.some((e) => e.type === 'verify' && e.payload.checks.every((c: any) => c.ok))).toBeTruthy()
  expect(events.at(-1).type).toBe('finished')
  // 断线续传：after=N 只返回之后的事件
  const mid = events[Math.floor(events.length / 2)].seq
  const tail = await api.events(task.id, mid)
  expect(tail.every((e) => e.seq > mid)).toBeTruthy()

  // 预览：加一与重置真实可用
  await page.goto(detail.previewUrl)
  const plus = page.getByRole('button', { name: /加一/ })
  const reset = page.getByRole('button', { name: /重置/ })
  await expect(plus).toBeVisible()
  await expect(reset).toBeDisabled()
  await plus.click(); await plus.click(); await plus.click()
  await expect(page.locator('body')).toContainText('3')
  await expect(reset).toBeEnabled()
  await reset.click()
  await expect(reset).toBeDisabled()
  // 预览版带源码位置标记
  expect(await page.locator('[data-loc]').count()).toBeGreaterThan(3)
})

test('刷新后应用、版本、对话仍在；其他工作空间看不到', async ({ playwright }) => {
  const again = await api.get(`/api/apps/${s.counter.id}`)
  expect(again.currentVersion.number).toBe(1)
  const msgs = await api.get(`/api/apps/${s.counter.id}/messages`)
  expect(msgs.some((m: any) => m.role === 'user')).toBeTruthy()
  expect(msgs.some((m: any) => m.role === 'assistant')).toBeTruthy()
  const list = await api.get('/api/apps?q=' + encodeURIComponent(again.name))
  expect(list.map((a: any) => a.id)).toContain(s.counter.id)

  const stranger = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await stranger.expectError('GET', `/api/apps/${s.counter.id}`, undefined, 404)
  expect(await stranger.get('/api/apps')).toEqual([])
})

test('选中元素 + 描述 → 对话修改：只改目标元素并生成新版本', async ({ page }) => {
  const app = s.counter
  await page.goto(app.previewUrl)
  // 找副标题：h1 之后的第一个 p
  const sub = page.locator('h1 ~ p, header p, main p').first()
  await expect(sub).toBeVisible()
  const ctx = await sub.evaluate((el) => ({ loc: el.getAttribute('data-loc'), tag: el.tagName, text: el.textContent, color: getComputedStyle(el).color }))
  const headingColor = await page.locator('h1').first().evaluate((el) => getComputedStyle(el).color)
  expect(ctx.loc).toBeTruthy()

  // 其他应用的上下文不能用于本应用
  await api.expectError('POST', `/api/apps/${app.id}/messages`, {
    content: '把字体颜色改为蓝色',
    elementContext: { appId: 'not-this-app', baseVersionId: app.currentVersion.id, loc: ctx.loc, tag: ctx.tag, text: ctx.text },
  }, 400)

  const { message, task } = await api.post(`/api/apps/${app.id}/messages`, {
    content: '把字体颜色改为蓝色',
    elementContext: { appId: app.id, baseVersionId: app.currentVersion.id, loc: ctx.loc, tag: ctx.tag, text: ctx.text },
  })
  expect(message.elementContext.loc).toBe(ctx.loc)
  const done = await api.waitTask(task.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.currentVersion.number).toBe(2)
  s.counter = detail

  await page.goto(detail.previewUrl)
  const sub2 = page.getByText(ctx.text!.trim().slice(0, 8)).first()
  const [r, g, b] = await rgbOf(sub2)
  expect(b, `副标题颜色 rgb(${r},${g},${b})`).toBeGreaterThan(r + 40)
  expect(await page.locator('h1').first().evaluate((el) => getComputedStyle(el).color)).toBe(headingColor)
})

test('可视化编辑草稿：未保存时发布/恢复被拦截，保存后生成版本并写回源码', async ({ page }) => {
  const app = s.counter
  await page.goto(app.previewUrl)
  const h1 = await page.locator('h1').first().evaluate((el) => ({ loc: el.getAttribute('data-loc') }))
  const draft = await api.put(`/api/apps/${app.id}/draft`, {
    baseVersionId: app.currentVersion.id,
    patches: [{ loc: h1.loc, text: '灵感在这里发生', styles: { fontWeight: '700', color: '#7953dc' } }],
  })
  expect(draft.patches).toHaveLength(1)
  expect((await api.get(`/api/apps/${app.id}`)).hasDraft).toBe(true)
  await api.expectError('POST', `/api/apps/${app.id}/publish`, {}, 409, 'DRAFT_PENDING')
  const versions = await api.get(`/api/apps/${app.id}/versions`)
  await api.expectError('POST', `/api/versions/${versions.at(-1).id}/restore`, {}, 409, 'DRAFT_PENDING')
  // 基线不是当前版本 → 409
  await api.expectError('PUT', `/api/apps/${app.id}/draft`, { baseVersionId: versions.at(-1).id, patches: [] }, 409)

  const task = await api.post(`/api/apps/${app.id}/draft/commit`, {})
  expect(task.type).toBe('visual_save')
  const done = await api.waitTask(task.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.currentVersion.number).toBe(3)
  expect(detail.hasDraft).toBe(false)
  s.counter = detail

  const file = h1.loc!.split(':')[0]
  const src = await (await api.req.get(`/api/versions/${detail.currentVersion.id}/file?path=${encodeURIComponent(file)}`)).text()
  expect(src).toContain('灵感在这里发生')
  await page.goto(detail.previewUrl)
  await expect(page.locator('h1').first()).toHaveText('灵感在这里发生')
  // 交互仍然正常
  await page.getByRole('button', { name: /加一/ }).click()
  await expect(page.locator('body')).toContainText('1')
})

test('发布后恢复旧版本：当前内容恢复并新增版本，线上版本不变', async ({ page }) => {
  const app = s.counter
  const pub = await api.post(`/api/apps/${app.id}/publish`, {})
  expect(pub.versionId).toBe(app.currentVersion.id)
  expect(pub.siteUrl).toMatch(new RegExp(`localhost:${SITE_PORT}`))
  s.siteUrl = pub.siteUrl
  await page.goto(pub.siteUrl)
  await expect(page.locator('h1').first()).toHaveText('灵感在这里发生')
  expect(await page.locator('[data-loc]').count()).toBe(0) // 发布版不带编辑标记

  const versions = await api.get(`/api/apps/${app.id}/versions`)
  const v1 = versions.find((v: any) => v.number === 1)
  await api.expectError('POST', `/api/versions/${app.currentVersion.id}/restore`, {}, 409, 'ALREADY_CURRENT')
  const restored = await api.post(`/api/versions/${v1.id}/restore`, {})
  expect(restored.number).toBe(4)
  expect(restored.source).toBe('restore')
  expect(restored.restoredFromNumber).toBe(1)
  const after = await api.get(`/api/apps/${app.id}/versions`)
  expect(after).toHaveLength(4)
  expect(after.find((v: any) => v.isCurrent).number).toBe(4)
  expect(after.find((v: any) => v.isPublished).number).toBe(3)

  await page.goto(restored.previewUrl)
  await expect(page.locator('h1').first()).not.toHaveText('灵感在这里发生')
  await page.goto(s.siteUrl)
  await expect(page.locator('h1').first()).toHaveText('灵感在这里发生')
})

test('历史：搜索、应用筛选、收藏、线上版本筛选；源码导出与页面下载', async () => {
  const app = s.counter
  const all = await api.get(`/api/history?appId=${app.id}`)
  expect(all).toHaveLength(4)
  for (let i = 1; i < all.length; i++) expect(all[i - 1].version.createdAt >= all[i].version.createdAt).toBeTruthy()
  const online = await api.get(`/api/history?filter=published`)
  expect(online.some((r: any) => r.app.id === app.id && r.version.number === 3)).toBeTruthy()
  const v2 = all.find((r: any) => r.version.number === 2).version
  await api.patch(`/api/versions/${v2.id}`, { favorite: true })
  const fav = await api.get(`/api/history?filter=favorite&appId=${app.id}`)
  expect(fav.map((r: any) => r.version.number)).toEqual([2])
  const byTitle = await api.get(`/api/history?q=${encodeURIComponent('恢复自')}`)
  expect(byTitle.some((r: any) => r.version.number === 4)).toBeTruthy()

  const zip = await api.req.get(`/api/versions/${app.currentVersion.id}/export`)
  expect(zip.ok()).toBeTruthy()
  expect((await zip.body()).subarray(0, 2).toString()).toBe('PK')
  const page = await api.req.get(`/api/versions/${app.currentVersion.id}/page`)
  const html = await page.text()
  expect(html).toContain('<html')
  expect(html).not.toMatch(/<script[^>]+src=/) // 脚本已内联
})

test('应用管理：重命名、收藏、筛选、排序', async () => {
  const app = s.counter
  await api.expectError('PATCH', `/api/apps/${app.id}`, { name: '' }, 400)
  const renamed = await api.patch(`/api/apps/${app.id}`, { name: '我的计数器' + Date.now() % 1000, favorite: true })
  expect(renamed.favorite).toBe(true)
  const favs = await api.get('/api/apps?favorite=true')
  expect(favs.map((a: any) => a.id)).toContain(app.id)
  const published = await api.get('/api/apps?published=published')
  expect(published.map((a: any) => a.id)).toContain(app.id)
  const unpublished = await api.get('/api/apps?published=unpublished')
  expect(unpublished.map((a: any) => a.id)).not.toContain(app.id)
  const byName = await api.get('/api/apps?sort=name')
  const names = byName.map((a: any) => a.name)
  expect([...names].sort((a, b) => a.localeCompare(b, 'zh-CN'))).toEqual(names)
  expect(await api.get('/api/apps?q=不存在的应用xyz')).toEqual([])
  expect(await api.get('/api/apps?type=技术文档&q=' + encodeURIComponent(renamed.name))).toEqual([])
  expect((await api.get('/api/apps?type=日常开发')).map((a: any) => a.id)).toContain(app.id)
  await api.expectError('POST', '/api/apps', { type: '小程序', prompt: '做个东西', mode: '快速', model: 'auto', assetIds: [] }, 400)
})

test('工作空间恢复链接：新设备用恢复令牌找回应用', async ({ playwright }) => {
  const token = s.recoveryUrl.split('#')[1]
  const other = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await other.post('/api/workspace/recover', { token })
  const list = await other.get('/api/apps')
  expect(list.map((a: any) => a.id)).toContain(s.counter.id)
})

test('数据统计：发布站点的访问计 PV，预览和资源不计；汇总数字；删除后站点 404、列表和历史中消失', async ({ playwright }) => {
  const app = s.counter
  // 先等之前用例产生的访问量落库（每秒批量写入），再取基线
  await new Promise((r) => setTimeout(r, 2500))
  const before = await api.get(`/api/apps/${app.id}/stats?range=7d`)
  const summary0 = await api.get('/api/apps/summary')
  expect(summary0.total).toBeGreaterThan(0)
  expect(summary0.published).toBeGreaterThan(0)
  const visitor = await playwright.request.newContext()
  for (let i = 0; i < 3; i++) expect((await siteGet(visitor, s.siteUrl)).ok()).toBeTruthy()
  await siteGet(visitor, app.previewUrl)
  await expect.poll(async () => (await api.get(`/api/apps/${app.id}/stats?range=7d`)).todayPv, { timeout: 15_000 }).toBe(before.todayPv + 3)
  // 预览不计：再等一个写入周期，数值不变
  await new Promise((r) => setTimeout(r, 2500))
  expect((await api.get(`/api/apps/${app.id}/stats?range=7d`)).todayPv).toBe(before.todayPv + 3)
  const st = await api.get(`/api/apps/${app.id}/stats?range=7d`)
  expect(st.daily).toHaveLength(7)
  expect(st.daily.at(-1).pv).toBe(st.todayPv)
  expect(st.totalPv).toBeGreaterThanOrEqual(st.todayPv)
  expect((await api.get(`/api/apps/${app.id}/stats?range=today`)).daily).toHaveLength(1)
  await expect.poll(async () => (await api.get('/api/apps/summary')).totalPv).toBeGreaterThanOrEqual(summary0.totalPv + 3)

  // 删除：先建一个独立的应用来删，不影响前面的用例
  const tmp = await api.post('/api/apps', { type: '日常开发', prompt: '做一个最简单的番茄钟', mode: '快速', model: 'auto', assetIds: [] })
  await api.waitTask(tmp.activeTask.id, { timeoutMs: 4 * 60_000 })
  const g = await api.generate(tmp.id)
  expect(g.status, JSON.stringify(g.error)).toBe('SUCCEEDED')
  const pub = await api.post(`/api/apps/${tmp.id}/publish`, {})
  expect((await siteGet(visitor, pub.siteUrl)).ok()).toBeTruthy()
  const total1 = (await api.get('/api/apps/summary')).total
  const del = await api.req.delete(`/api/apps/${tmp.id}`)
  expect(del.status()).toBe(204)
  expect((await siteGet(visitor, pub.siteUrl)).status()).toBe(404)
  expect((await api.get('/api/apps')).map((a: any) => a.id)).not.toContain(tmp.id)
  expect((await api.get(`/api/history?appId=${tmp.id}`))).toEqual([])
  expect((await api.get('/api/apps/summary')).total).toBe(total1 - 1)
  await api.expectError('GET', `/api/apps/${tmp.id}`, undefined, 404)
})
