import { expect, test } from '@playwright/test'

test('模型声称已开始但漏掉 build 标记时，前端自动创建应用', async ({ page }) => {
  let buildCalled = false
  await page.route('**/api/tasks/active', (route) => route.fulfill({ contentType: 'application/json', body: 'null' }))
  await page.route(/\/api\/conversations\/[^/]+\/ask$/, (route) => route.fulfill({
    contentType: 'text/event-stream; charset=utf-8',
    body: 'event: delta\ndata: {"text":"系统已经开始构建了，正在编写代码和项目文件。"}\n\nevent: done\ndata: {}\n\n',
  }))
  await page.route(/\/api\/conversations\/[^/]+\/build$/, (route) => {
    buildCalled = true
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ id: 'app_auto_build', name: '自动创建的应用' }) })
  })

  await page.goto('/access_token?access_token=tok-yangkai37&expires_in=7200')
  await page.waitForURL(/\/create$/)
  await page.getByLabel('应用需求').fill('开始做一个工单报修页面，包含报修列表、提交表单和处理状态。')
  await page.getByRole('button', { name: '发送', exact: true }).click()

  await expect(page).toHaveURL(/\/apps\/app_auto_build$/, { timeout: 30_000 })
  expect(buildCalled).toBe(true)
})

test('其他应用生成中，首页仍能进入新会话并完成纯聊天', async ({ page }) => {
  await page.route('**/api/tasks/active', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      task: { id: 'task_busy', type: 'generate', status: 'RUNNING' },
      appId: 'app_busy',
      appName: 'SPC控制图工具',
    }),
  }))
  await page.route(/\/api\/conversations\/[^/]+\/ask$/, (route) => route.fulfill({
    contentType: 'text/event-stream; charset=utf-8',
    body: 'event: delta\ndata: {"text":"这是普通问答的回答"}\n\nevent: done\ndata: {}\n\n',
  }))

  await page.goto('/access_token?access_token=tok-yangkai37&expires_in=7200')
  await page.waitForURL(/\/create$/)
  await expect(page.getByRole('status').filter({ hasText: '仍可继续聊天' })).toBeVisible()
  await page.getByLabel('应用需求').fill('这是啥技能啊')
  await page.getByRole('button', { name: '发送', exact: true }).click()

  await expect(page).toHaveURL(/\/chat\/conv_/)
  await expect(page.getByText('这是普通问答的回答', { exact: true })).toBeVisible()
})
