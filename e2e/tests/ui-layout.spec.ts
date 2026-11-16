// 工作台布局：预览可以收起（对话占满）、预览有自己的全屏、头部有「对话全屏」
import { test, expect, type Page } from '@playwright/test'
import { Api } from './helpers'

test.describe.configure({ mode: 'serial' })

let page: Page
let url = ''

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage()
  const api = new Api(page.context().request)
  const app = await api.createApp('做一个点击计数器：标题、显示次数、加一和重置两个按钮。', '日常开发')
  await api.generate(app.id)
  url = '/apps/' + app.id
})

test('预览收起 / 展开，预览全屏，对话全屏', async () => {
  test.setTimeout(6 * 60_000)
  await page.goto(url)
  await expect(page.getByRole('tab', { name: '预览' })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('tab', { name: '预览' }).click()

  // 头部方框：收起预览 → 只剩一条窄条，聊天居中一列
  await page.getByRole('button', { name: '收起工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toBeVisible()
  const box = (await page.locator('.chat-scroll').boundingBox())!
  expect(box.width).toBeLessThanOrEqual(920)
  expect(box.x).toBeGreaterThan(60)
  // 聊天全屏时中间那排「代码/预览/文件/云服务」不显示
  await expect(page.locator('.studio-tabs.is-hidden')).toHaveCount(1)
  // 左侧导航栏还在
  await expect(page.locator('.sidebar')).toBeVisible()
  // 聊天全屏时右侧连窄条都没有；用头部按钮恢复
  await expect(page.locator('.preview-panel')).toHaveCount(0)
  await page.getByRole('button', { name: '展开工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toHaveCount(0)

  // 预览自己的全屏：顶栏整条隐藏，预览铺满
  await page.getByRole('button', { name: '全屏预览' }).click()
  await expect(page.locator('.preview-maximized')).toBeVisible()
  await expect(page.locator('.studio-header.is-fullscreen')).toHaveCount(1)
  await expect(page.getByRole('tab', { name: '代码' })).toHaveCount(0)
  await page.getByRole('button', { name: '退出全屏预览' }).click()
  await expect(page.locator('.preview-maximized')).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '代码' })).toBeVisible()

  // 头部按钮也能恢复：收起后用头部那个换回大 / 小
  await page.getByRole('button', { name: '收起工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toBeVisible()
  await page.getByRole('button', { name: '展开工作区' }).click()
  await expect(page.locator('.studio-body.preview-collapsed')).toHaveCount(0)

  // 预览全屏时点「手动微调」：编辑面板浮在预览左边，改完能保存
  await page.getByRole('button', { name: '全屏预览' }).click()
  await expect(page.locator('.preview-maximized')).toBeVisible()
  await page.getByRole('button', { name: '手动微调' }).click()
  const frame = page.frameLocator('iframe[title$="应用预览"]')
  const panel = page.locator('.ze-edit-panel')
  // 预览 iframe 里的编辑桥可能还没 ready，点一次没选中就再点一次
  for (let i = 0; i < 4 && (await panel.count()) === 0; i++) {
    await frame.locator('h1').first().click()
    await page.waitForTimeout(1200)
  }
  await expect(panel).toBeVisible({ timeout: 20_000 })
  const pb = (await panel.boundingBox())!
  expect(pb.x).toBeLessThan(60 + 340) // 浮在左边，不挤占预览
  const textArea = panel.getByLabel('元素文字')
  await textArea.fill('全屏里改的标题')
  await textArea.press('Meta+Enter') // 文案在失焦或 Cmd+Enter 时提交
  await page.getByRole('button', { name: '应用', exact: true }).click()
  // 保存成功会弹「修改已保存」；全屏时顶栏是隐藏的，先退出全屏再看版本号
  await expect(page.getByText(/修改已保存/)).toBeVisible({ timeout: 3 * 60_000 })
  await page.getByRole('button', { name: '退出全屏预览' }).click()
  await expect(page.locator('.studio-header')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: /版本历史 V\d+/ })).toHaveAttribute('aria-label', /V2\b/, { timeout: 60_000 })

  // 只保留两个按钮：头部一个、预览工具栏一个
  await expect(page.getByRole('button', { name: '收起对话面板' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '收起预览' })).toHaveCount(0)
})
test('窄屏输入区不溢出；消息的时间和操作常显（不用悬停）', async () => {
  for (const width of [1100, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(url)
    const send = page.locator('.chat-composer .send-button').first()
    await expect(send).toBeVisible({ timeout: 60_000 })
    const composer = (await page.locator('.chat-composer').boundingBox())!
    const btn = (await send.boundingBox())!
    expect(btn.x + btn.width, `宽 ${width} 时发送按钮超出输入框`).toBeLessThanOrEqual(composer.x + composer.width)
    await expect(page.getByRole('button', { name: /共享云服务|查看云服务/ })).toBeVisible()
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.mouse.move(1300, 500)
  const actions = page.locator('.assistant-message .msg-actions').last()
  await actions.scrollIntoViewIfNeeded()
  await expect(actions.locator('time')).toBeVisible()
  await expect(actions.getByRole('button', { name: '复制消息' })).toBeVisible()
  const opacity = await actions.getByRole('button', { name: '复制消息' }).evaluate((el) => Number(getComputedStyle(el).opacity))
  expect(opacity).toBeGreaterThan(0.5)
})

test('点「共享云服务」在输入框上方弹确认卡，不往输入框里填字；暂不启用就收起', async () => {
  await page.goto(url)
  const chip = page.getByRole('button', { name: '共享云服务' })
  await expect(chip).toBeVisible({ timeout: 60_000 })
  const before = (await page.locator('.studio-main .chat-composer').boundingBox())!.height
  await chip.click()
  const card = page.locator('.composer-float .confirm-card')
  await expect(card).toContainText('是否要启用织页云服务？')
  await expect(page.locator('#iteration-prompt')).toHaveValue('')
  // 卡片悬浮在输入框上方，输入框高度不变
  expect((await page.locator('.studio-main .chat-composer').boundingBox())!.height).toBe(before)
  const c = (await card.boundingBox())!
  const box = (await page.locator('.studio-main .chat-composer').boundingBox())!
  expect(c.y + c.height).toBeLessThanOrEqual(box.y)
  await card.getByRole('button', { name: '暂不启用' }).click()
  await expect(card).toHaveCount(0)
})
