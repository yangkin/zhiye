// 数据源：只能在 AGW 侧登记；织页只提供「选择可调用 API」入口，手工登记接口返回 403 SOURCE_MANUAL_DISABLED
import { test, expect } from '@playwright/test'

test('技能随消息清空，数据源作为会话级连接持久化', async ({ page }) => {
  const source = { id: 'src-ui-1', key: 'feishu_message', name: '飞书_发送消息', description: '发送飞书消息', platform: false }
  const skill = { id: 'skill-ui-1', name: '周报助手', description: '整理周报', currentVersion: { id: 'v1' } }
  let conversationSources = [source]
  let messages: any[] = []
  let createBody: any = null
  let askBody: any = null
  let lastSourceUpdate: any = null

  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/meta') return route.fulfill({ json: {
      models: [{ id: 'auto', label: 'Auto' }], modes: ['快速', '适中', '深度'], styles: [],
      limits: { uploadMaxBytes: 10485760, uploadMaxFiles: 5, assetMaxBytes: 10485760 },
      auth: { mode: 'sso', user: { id: 'u1', loginName: 'tester', realName: '测试员' }, admin: false },
      builtinSkills: [], hubSkills: [], fivestar: false,
    } })
    if (path === '/api/tasks/active') return route.fulfill({ json: null })
    if (path === '/api/workspace') return route.fulfill({ json: { id: 'ws-ui', createdAt: new Date().toISOString(), recoveryUrl: null } })
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/sources', (route) => route.fulfill({ json: [source] }))
  await page.route(/\/api\/apps\?.*type=%E5%BC%80%E5%8F%91skill/, (route) => route.fulfill({ json: [skill] }))
  await page.route('**/api/conversations', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    createBody = route.request().postDataJSON()
    await route.fulfill({ json: { id: 'conv-ui-1', title: '新对话', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } })
  })
  await page.route('**/api/conversations/conv-ui-1', (route) => route.fulfill({
    json: { id: 'conv-ui-1', title: '新对话', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), messages, sources: conversationSources },
  }))
  await page.route('**/api/conversations/conv-ui-1/sources', async (route) => {
    lastSourceUpdate = route.request().postDataJSON()
    conversationSources = lastSourceUpdate.sourceIds.map((id: string) => id === source.id ? source : null).filter(Boolean)
    await route.fulfill({ json: { sources: conversationSources } })
  })
  await page.route('**/api/conversations/conv-ui-1/ask', async (route) => {
    askBody = route.request().postDataJSON()
    messages = [
      { id: 'u1', role: 'user', content: askBody.content, createdAt: new Date().toISOString(), skillAppIds: askBody.skillAppIds },
      { id: 'a1', role: 'assistant', content: '这是查询接口的使用说明。', createdAt: new Date().toISOString() },
    ]
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'text/event-stream' },
      body: 'event: delta\ndata: {"text":"这是查询接口的使用说明。"}\n\nevent: done\ndata: {}\n\n',
    })
  })

  await page.goto('/create')
  await page.getByRole('button', { name: '添加素材' }).click()
  await page.getByRole('menuitem', { name: '数据源' }).hover()
  await page.getByRole('menuitem', { name: /飞书_发送消息/ }).click()
  await page.getByRole('button', { name: '选择技能' }).click()
  await page.getByRole('option', { name: /周报助手/ }).click()
  await page.getByRole('textbox', { name: '应用需求' }).fill('这是什么')
  await page.getByRole('button', { name: '发送', exact: true }).click()

  await expect(page).toHaveURL(/\/chat\/conv-ui-1$/)
  await expect(page.getByText('这是查询接口的使用说明。')).toBeVisible()
  expect(createBody.sourceIds).toEqual([source.id])
  expect(askBody.skillAppIds).toEqual([skill.id])
  await expect(page.getByRole('button', { name: '移除技能 周报助手' })).toHaveCount(0)
  await expect(page.getByLabel('本会话的应用数据源')).toContainText('创建后持续可用')
  await expect(page.getByRole('button', { name: '移除数据源 飞书_发送消息' })).toBeVisible()

  await page.reload()
  await expect(page.getByRole('button', { name: '移除数据源 飞书_发送消息' })).toBeVisible()
  await page.getByRole('button', { name: '移除数据源 飞书_发送消息' }).click()
  await expect(page.getByLabel('本会话的应用数据源')).toHaveCount(0)
  expect(lastSourceUpdate.sourceIds).toEqual([])
})

test('完整 JSON 请求体用文本框试调用，不显示 root 参数', async ({ page }) => {
  const source = {
    id: 'src-json', key: 'send_message', name: '发送消息', description: '', method: 'POST', url: '', platform: false, editable: false,
    params: [{ name: '_body', type: 'json', required: false, description: '完整 JSON 请求体（填写后覆盖其他参数）', example: '', position: 'body' }],
  }
  let testBody: any = null
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/meta') return route.fulfill({ json: {
      models: [{ id: 'auto', label: 'Auto' }], modes: ['快速', '适中', '深度'], styles: [],
      limits: { uploadMaxBytes: 10485760, uploadMaxFiles: 5, assetMaxBytes: 10485760 },
      auth: { mode: 'sso', user: { id: 'u1', loginName: 'tester', realName: '测试员' }, admin: false },
      builtinSkills: [], hubSkills: [], fivestar: false,
    } })
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/sources', (route) => route.fulfill({ json: [source] }))
  await page.route('**/api/sources/src-json/test', async (route) => {
    testBody = route.request().postDataJSON()
    await route.fulfill({ json: { data: { success: true }, status: 200 } })
  })

  await page.goto('/create')
  await page.getByRole('button', { name: '添加素材' }).click()
  await page.getByRole('menuitem', { name: '数据源' }).hover()
  await page.getByRole('menuitem', { name: '管理数据源' }).click()
  await page.getByRole('button', { name: '试调用', exact: true }).click()
  const input = page.getByRole('textbox', { name: '完整 JSON 请求体' })
  await expect(input).toBeVisible()
  await expect(page.getByText('root', { exact: true })).toHaveCount(0)
  await input.fill('{"appId":"app-1","text":"你好"}')
  await page.getByRole('button', { name: '试调用', exact: true }).click()
  await expect(page.locator('.source-result.ok')).toContainText('调用成功')
  expect(JSON.parse(testBody.params._body)).toEqual({ appId: 'app-1', text: '你好' })
})

test('数据源只走 AGW：无手工登记入口，POST /api/sources 返回 403', async ({ page }) => {
  await page.goto('/create')
  await page.getByRole('button', { name: '添加素材' }).click()
  await page.getByRole('menuitem', { name: '数据源' }).hover()
  await expect(page.getByRole('menu', { name: '数据源' })).toBeVisible()
  await page.getByRole('menuitem', { name: '管理数据源' }).click()

  const dialog = page.getByRole('dialog', { name: '数据源' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('button', { name: '选择可调用 API' })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '登记数据源' })).toHaveCount(0)

  const res = await page.request.post('/api/sources', {
    data: { key: 'ui_manual', name: '手工登记', method: 'GET', url: 'http://127.0.0.1:9/x', params: [], authType: 'none' },
  })
  expect(res.status()).toBe(403)
  expect(await res.text()).toContain('SOURCE_MANUAL_DISABLED')
})
