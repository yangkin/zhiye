import { test, expect } from '@playwright/test'

test('SSE 停在排队事件时，任务快照兜底更新真实进度', async ({ page }) => {
  const now = new Date().toISOString()
  let snapshots = 0
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/meta') return route.fulfill({ json: {
      models: [{ id: 'auto', label: 'Auto' }], modes: ['快速', '适中', '深度'], styles: [],
      limits: { uploadMaxBytes: 10485760, uploadMaxFiles: 5, assetMaxBytes: 10485760 },
      auth: { mode: 'sso', user: { id: 'u1', loginName: 'tester', realName: '测试员' }, admin: false },
      builtinSkills: [], hubSkills: [], fivestar: false,
    } })
    if (path === '/api/tasks/active') return route.fulfill({ json: null })
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/apps/app-stream', (route) => route.fulfill({ json: {
    id: 'app-stream', name: '进度测试', type: '日常开发', projectKind: 'template', phase: 'generating',
    prompt: '测试任务进度', mode: '快速', model: 'auto', source: 'ai', style: 'lavender',
    activeTask: { id: 'task-stream', type: 'generate', status: 'QUEUED' },
    currentVersion: null, publishedVersionId: null, assets: [], createdAt: now, updatedAt: now,
  } }))
  await page.route('**/api/tasks/task-stream', (route) => {
    snapshots++
    return route.fulfill({ json: {
      id: 'task-stream', type: 'generate', status: 'RUNNING', progress: 20, phaseLabel: '正在编写代码',
      appId: 'app-stream', createdAt: now, startedAt: now, todos: [],
    } })
  })
  await page.route('**/api/tasks/task-stream/events*', (route) => route.fulfill({
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
    body: 'id: 1\nevent: task\ndata: {"seq":1,"type":"status","payload":{"status":"QUEUED","phaseLabel":"排队中","progress":0}}\n\n',
  }))

  await page.goto('/apps/app-stream')
  await expect(page.locator('.task-card')).toContainText('正在编写代码', { timeout: 8000 })
  await expect(page.locator('.task-card .progress-title')).toContainText('20%')
  expect(snapshots).toBeGreaterThanOrEqual(2)
})
