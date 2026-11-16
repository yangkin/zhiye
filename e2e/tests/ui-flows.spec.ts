// 界面级端到端：在真实浏览器里走完主要用户操作（真实后端 + 真实 Kimi）
import { test, expect, type Page, type FrameLocator } from '@playwright/test'
import path from 'node:path'
import { Api, rgbOf, SITE_PORT } from './helpers'

test.describe.configure({ mode: 'serial' })
const FIX = path.join(__dirname, '..', 'fixtures')

let page: Page
let appUrl = ''
let appId = ''
const preview = (p: Page): FrameLocator => p.frameLocator('iframe[title$="应用预览"]')
const versionButton = (p: Page) => p.getByRole('button', { name: /^版本历史 V\d+/ })
async function versionNumber(p: Page) { return Number((await versionButton(p).getAttribute('aria-label'))!.match(/V(\d+)/)![1]) }
async function waitVersion(p: Page, n: number, timeout = 12 * 60_000) {
  await expect(versionButton(p)).toHaveAttribute('aria-label', new RegExp(`V${n}\\b`), { timeout })
}

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext()).newPage()
})

test('免登录创造：类型切换、空白需求不可提交、创建并执行计划、预览可交互', async () => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/create$/)
  await expect(page.getByRole('button', { name: /^(登录|注册)|积分|余额/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /^(登录|注册)|积分|余额/ })).toHaveCount(0)
  await expect(page.getByText('免登录')).toBeVisible()
  // 类型是可不选的标签页：点一下选中，再点一次取消，一次只选一个
  const types = page.getByRole('tablist', { name: '应用类型（可不选）' })
  await expect(types.getByRole('tab')).toHaveText(['日常开发', '开发网站', '开发skill', '技术文档'])
  await expect(types.getByRole('tab', { selected: true })).toHaveCount(0)
  await types.getByRole('tab', { name: '技术文档' }).click()
  await expect(types.getByRole('tab', { name: '技术文档' })).toHaveAttribute('aria-selected', 'true')
  await types.getByRole('tab', { name: '技术文档' }).click()
  await expect(types.getByRole('tab', { selected: true })).toHaveCount(0)
  await types.getByRole('tab', { name: '日常开发' }).click()
  await expect(types.getByRole('tab', { selected: true })).toHaveText(['日常开发'])
  await expect(page.getByText(/小程序|APP/)).toHaveCount(0)
  const send = page.getByRole('button', { name: '发送', exact: true })
  await expect(send).toBeDisabled()
  await page.getByLabel('应用需求').fill('请现在开始做：一个点击计数器，标题“点击计数器”，一句副标题，显示当前次数，有“加一”和“重置”两个按钮，次数为 0 时重置禁用。')
  await expect(send).toBeEnabled()
  await send.click()
  // 先进入会话；模型确认这是明确的构建需求后自动建应用。
  await expect(page).toHaveURL(/\/apps\/[^/]+$/, { timeout: 120_000 })
  appUrl = page.url()
  appId = appUrl.split('/').pop()!

  // 创造计划：生成后可编辑，再立即执行
  const doc = page.getByLabel('需求文档')
  await expect(doc).toBeVisible({ timeout: 4 * 60_000 })
  await expect(doc).toHaveText(/\S/, { timeout: 4 * 60_000 })
  await page.getByRole('button', { name: /立即执行/ }).click()
  // 进度来自真实任务事件
  await expect(page.getByRole('button', { name: /停止/ }).first()).toBeVisible({ timeout: 60_000 })
  await waitVersion(page, 1)
  const frame = preview(page)
  await frame.getByRole('button', { name: /加一/ }).click()
  await frame.getByRole('button', { name: /加一/ }).click()
  await expect(frame.locator('body')).toContainText('2')
  await frame.getByRole('button', { name: /重置/ }).click()
  // 若生成的应用带二次确认，再点一次
  const confirm = frame.getByRole('button', { name: /确认/ })
  if (await confirm.count()) await confirm.first().click()
  await expect(frame.getByRole('button', { name: /重置/ })).toBeDisabled()
})

test('创造页：悬停切换标题、每种类型 10 个华星模板、领域筛选、小织解答使用问题', async () => {
  await page.goto('/create')
  // 标题：只有鼠标进入「织页」左侧的文字时才换一句；悬停品牌名或输入区不变
  const title = page.locator('.cp-title-text')
  const first = (await title.textContent())!
  await page.locator('.cp-brand').hover()
  await page.getByLabel('应用需求').hover()
  await expect(title).toHaveText(first)
  await title.hover()
  await expect(title).not.toHaveText(first)
  const nextLine = (await title.textContent())!
  await page.mouse.move(5, 600)
  await title.hover()
  await expect(title).not.toHaveText(nextLine)
  // 小机器人
  await expect(page.getByRole('button', { name: /小织/ })).toBeVisible()
  for (const t of ['日常开发', '开发网站', '开发skill', '技术文档']) {
    await page.getByRole('combobox', { name: '应用类型（可选）' }).click()
    await page.getByRole('option', { name: t, exact: true }).click()
    await expect(page.locator('.inspiration-card')).toHaveCount(10)
    await expect(page.locator('.inspiration-card svg.icon').first()).toBeVisible()
  }
  const domains = page.getByRole('tablist', { name: '模板领域' })
  const second = domains.getByRole('tab').nth(1)
  const domain = (await second.textContent())!.trim()
  await second.click()
  const n = await page.locator('.inspiration-card').count()
  expect(n).toBeGreaterThan(0)
  expect(n).toBeLessThan(10)
  await expect(page.locator('.scene-tag').first()).toContainText(domain)
  // 点模板把完整需求填进输入框
  await page.locator('.inspiration-card').first().click()
  await expect(page.getByLabel('应用需求')).toHaveValue(/虚构的示例数据/)
  await page.getByLabel('应用需求').fill('')
  // 小织
  await page.getByRole('button', { name: /小织/ }).first().click()
  const help = page.getByRole('dialog', { name: /小织/ })
  await expect(help).toBeVisible()
  await help.getByLabel('向小织提问').fill('怎么恢复旧版本？')
  await help.getByRole('button', { name: '发送问题' }).click()
  // 真实模型首字可能较慢（尤其思考型模型），给足等待时间
  await expect(help.locator('.help-msg.pet').last()).toContainText('恢复', { timeout: 90_000 })
  await help.getByRole('button', { name: '关闭帮助' }).click()
  await expect(help).toBeHidden()
  await page.goto(appUrl)
})

test('刷新后仍能进入同一个应用和版本', async () => {
  await page.reload()
  await waitVersion(page, 1, 30_000)
  await expect(preview(page).getByRole('button', { name: /加一/ })).toBeVisible()
})

test('预览：没有手机预览，刷新、新窗口、全屏、收起预览', async () => {
  const iframe = page.locator('iframe[title$="应用预览"]')
  await expect(page.getByRole('button', { name: /手机预览|桌面预览/ })).toHaveCount(0)
  await expect.poll(async () => (await iframe.boundingBox())!.width).toBeGreaterThan(600)
  await page.getByRole('button', { name: '刷新预览' }).click()
  await expect(preview(page).getByRole('button', { name: /加一/ })).toBeVisible()
  const [popup] = await Promise.all([page.waitForEvent('popup'), page.getByRole('button', { name: '在新窗口打开预览' }).click()])
  await expect(popup).toHaveURL(new RegExp(`127\\.0\\.0\\.1:${SITE_PORT}/p/`))
  await popup.close()
  const chatInput = page.getByLabel('修改需求', { exact: true })
  // 头部的方框按钮：收起预览（聊天占满并居中）↔ 显示预览
  await page.getByRole('button', { name: '收起工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toBeVisible()
  await expect(chatInput).toBeVisible()
  await page.getByRole('button', { name: '展开工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toHaveCount(0)
  // 预览自己的全屏
  await page.getByRole('button', { name: '全屏预览' }).click()
  await expect(page.locator('.preview-maximized')).toBeVisible()
  await page.getByRole('button', { name: '退出全屏预览' }).click()
  await expect(page.locator('.preview-maximized')).toHaveCount(0)
})

test('对话修改：选中副标题，在元素旁输入描述直接发送；对话里出现元素上下文，只改目标元素', async () => {
  const frame = preview(page)
  const subtitle = frame.locator('h1 ~ p, main p').first()
  const headingBefore = await frame.locator('h1').first().evaluate((el) => getComputedStyle(el).color)
  await page.getByRole('button', { name: '对话修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '对话修改', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await subtitle.click()
  const bar = page.getByRole('group', { name: '对元素对话修改' })
  await expect(bar).toBeVisible()
  await bar.getByLabel('对元素的修改描述').fill('把字体颜色改为蓝色')
  await bar.getByRole('button', { name: '发送修改' }).click()
  await expect(bar).toBeHidden()
  // 消息直接进入左侧对话并带元素上下文
  await expect(page.locator('.chat-panel, aside, section').getByText('把字体颜色改为蓝色').first()).toBeVisible()
  await waitVersion(page, 2)
  await expect.poll(async () => {
    const [r, , b] = await rgbOf(preview(page).locator('h1 ~ p, main p').first())
    return b - r
  }, { timeout: 30_000 }).toBeGreaterThan(40)
  expect(await preview(page).locator('h1').first().evaluate((el) => getComputedStyle(el).color)).toBe(headingBefore)
  await page.getByRole('button', { name: '对话修改', exact: true }).click()
  await page.reload()
  await waitVersion(page, 2, 30_000)
})

test('手动微调：左侧编辑元素面板改文字和外边距、撤销/重做、底部「应用」生成版本，退出后按钮恢复交互', async () => {
  const frame = preview(page)
  await page.getByRole('button', { name: '手动微调', exact: true }).click()
  await frame.locator('h1').first().click()
  const panel = page.getByLabel('编辑元素', { exact: true })
  await expect(panel).toBeVisible()
  const text = panel.getByLabel('元素文字')
  await text.fill('灵感在这里发生')
  await text.press('Tab')
  await expect(frame.locator('h1').first()).toHaveText('灵感在这里发生')
  const mt = panel.getByLabel('外边距-上')
  await mt.fill('24')
  await mt.press('Enter')
  await expect.poll(() => frame.locator('h1').first().evaluate((el) => getComputedStyle(el).marginTop)).toBe('24px')
  await panel.getByRole('button', { name: '撤销修改' }).click()
  await expect.poll(() => frame.locator('h1').first().evaluate((el) => getComputedStyle(el).marginTop)).not.toBe('24px')
  await panel.getByRole('button', { name: '重做修改' }).click()
  await expect.poll(() => frame.locator('h1').first().evaluate((el) => getComputedStyle(el).marginTop)).toBe('24px')
  await expect(page.getByText('未保存').first()).toBeVisible()
  const actions = page.getByRole('group', { name: '手动微调操作' })
  await actions.getByRole('button', { name: '应用', exact: true }).click()
  await waitVersion(page, 3)
  await expect(preview(page).locator('h1').first()).toHaveText('灵感在这里发生')
  await page.getByRole('button', { name: '手动微调', exact: true }).click()
  await preview(page).getByRole('button', { name: /加一/ }).click()
  await expect(preview(page).locator('body')).toContainText('1')
})

test('切换应用后不残留上一个应用的选中元素和修改条', async () => {
  const api = new Api(page.context().request)
  const other = await api.post('/api/apps', { type: '开发网站', prompt: '做一个简单的个人主页', mode: '快速', model: 'auto', assetIds: [] })
  await api.waitTask(other.activeTask.id, { timeoutMs: 4 * 60_000 }) // 每人同时只能运行 1 个任务
  // 前端每 5s 轮询一次进行中的任务：等「正在进行修改」提示消失，否则元素条会短暂被占用
  await expect(page.getByText(/正在进行修改/)).toHaveCount(0, { timeout: 30_000 })
  await page.getByRole('button', { name: '对话修改', exact: true }).click()
  await preview(page).locator('h1').first().click()
  await expect(page.getByRole('group', { name: '对元素对话修改' })).toBeVisible()
  await page.goto('/apps/' + other.id)
  await expect(page.getByRole('group', { name: '对元素对话修改' })).toHaveCount(0)
  await page.goto(appUrl)
  await expect(page.getByRole('group', { name: '对元素对话修改' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '对话修改', exact: true })).toHaveAttribute('aria-pressed', 'false')
})

test('应用页：概览统计、搜索空状态、卡片菜单（修改标题、数据统计）', async () => {
  await page.goto('/apps')
  const overview = page.getByLabel('应用概览')
  await expect(overview).toContainText('/ 10')
  await expect(overview).toContainText('已发布应用')
  const search = page.getByLabel('搜索应用')
  await search.fill('不存在的应用名xyz')
  await expect(page.getByText('还没有找到这个应用')).toBeVisible()
  await search.fill('')
  const api = new Api(page.context().request)
  const app = await api.get('/api/apps/' + appId)
  await page.getByRole('button', { name: `${app.name}更多操作` }).click()
  await page.getByRole('menuitem', { name: '数据统计' }).click()
  const dlg = page.getByRole('dialog', { name: /数据分析/ })
  await expect(dlg).toBeVisible()
  await dlg.getByRole('radiogroup', { name: '统计时间范围' }).getByRole('radio', { name: '最近3天' }).click()
  await dlg.getByRole('radiogroup', { name: '图表类型' }).getByRole('radio', { name: '柱状图' }).click()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: `${app.name}更多操作` }).click()
  await page.getByRole('menuitem', { name: '修改标题' }).click()
  const box = page.getByRole('dialog').getByRole('textbox').first()
  await box.fill('界面改名计数器')
  await box.press('Enter')
  await expect(page.getByText('界面改名计数器').first()).toBeVisible()
})

test('历史页：线上版本筛选、恢复旧版本生成新版本，线上版本不变', async () => {
  const api = new Api(page.context().request)
  await api.post(`/api/apps/${appId}/publish`, {})
  await page.goto('/history?appId=' + appId)
  await expect(page.getByText(/V3/).first()).toBeVisible()
  await page.getByText('线上版本').first().click()
  await page.goto('/history?appId=' + appId)
  await page.getByRole('button', { name: /^恢复 .* V1$/ }).first().click()
  await page.getByRole('button', { name: '确认恢复' }).click()
  await expect.poll(async () => (await api.get(`/api/apps/${appId}/versions`)).length).toBe(4)
  const vs = await api.get(`/api/apps/${appId}/versions`)
  expect(vs.find((v: any) => v.isPublished).number).toBe(3)
  expect(vs.find((v: any) => v.isCurrent).number).toBe(4)
})

test('导入代码并部署：选择 HTML → 确认 → 进入新应用工作台，页面按钮可用', async () => {
  await page.goto('/create')
  await page.getByRole('button', { name: '添加素材' }).click()
  await expect(page.getByRole('menu', { name: '添加素材' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '数据源' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: /素材中心|使用技能/ })).toHaveCount(0)
  await page.getByRole('menuitem', { name: '导入代码并部署' }).click()
  const dialog = page.getByLabel('代码导入区域')
  await expect(dialog).toBeVisible()
  await page.locator('input[type=file][accept=".zip,.html,.htm"]').setInputFiles(path.join(FIX, 'garden.html'))
  await expect(page.getByLabel('导入应用名称')).toHaveValue('garden', { timeout: 30_000 }).catch(() => {})
  await page.getByLabel('导入应用名称').fill('界面导入花园')
  await page.getByRole('button', { name: /导入并部署/ }).last().click()
  await expect(page).toHaveURL(/\/apps\/[^/]+$/, { timeout: 5 * 60_000 })
  await preview(page).getByRole('button', { name: '种下一份灵感' }).click()
  await expect(preview(page).getByRole('button', { name: '已记录一份灵感' })).toBeVisible()
})
