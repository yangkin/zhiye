// 会话化创造：提问只回答、不建应用；模型判断需求明确后才进入应用生成
import { test, expect, type Page } from '@playwright/test'
import { Api } from './helpers'

test.describe.configure({ mode: 'serial' })

let page: Page

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext()).newPage()
})

test('提问先回答、不建应用；需求明确后由模型决定开始创建', async () => {
  test.setTimeout(8 * 60_000)
  const api = new Api(page.context().request)
  const before = await api.get('/api/apps')

  await page.goto('/create')
  await page.getByLabel('应用需求').fill('织页支持哪些应用类型？')
  await page.getByRole('button', { name: '发送', exact: true }).click()

  // 首页只负责建立会话，回答发生在聊天页，不创建应用。
  await expect(page).toHaveURL(/\/chat\/conv_[^/]+$/, { timeout: 30_000 })
  const chatUrl = page.url()
  const answers = page.locator('.chat-msg.assistant')
  await expect(answers).toHaveCount(1, { timeout: 90_000 })
  await expect(answers.last()).toContainText(/(日常开发|开发网站|技术文档|skill)/i, { timeout: 90_000 })
  expect((await api.get('/api/apps')).length).toBe(before.length)

  // 接着聊：多轮消息仍留在同一个会话里。
  await page.getByLabel('继续输入消息').fill('那做个工单报修的页面呢，要哪些页面？')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(answers).toHaveCount(2, { timeout: 90_000 })
  await expect(answers.last()).toContainText(/(工单|报修|页面)/, { timeout: 90_000 })
  await expect(page).toHaveURL(chatUrl)
  expect((await api.get('/api/apps')).length).toBe(before.length)

  // 没有「开始做」按钮：模型识别到明确、完整的构建意图后自动创建。
  await expect(page.getByRole('button', { name: /开始做|开始生成/ })).toHaveCount(0)
  await page.getByLabel('继续输入消息').fill('请现在开始做：做一个工单报修页面，包含报修列表、提交报修表单和处理状态。')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page).toHaveURL(/\/apps\/[^/]+$/, { timeout: 120_000 })
  const apps = await api.get('/api/apps')
  expect(apps.length).toBe(before.length + 1)
  const appId = page.url().split('/').pop()!
  const conversation = await api.get(`/api/apps/${appId}/conversation`)
  expect(conversation.messages.some((m: any) => m.role === 'user' && m.content.includes('工单报修'))).toBe(true)
})
