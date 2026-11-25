import { test, expect } from '@playwright/test'

test('精选置顶作品在卡片和详情页显示统一标记', async ({ page }) => {
  const now = new Date().toISOString()
  const work = (featured: boolean) => ({
    id: featured ? 'tpl-featured' : 'tpl-normal',
    name: featured ? '精选看板' : '普通看板',
    description: '测试作品',
    type: '日常开发',
    kind: 'template',
    framework: 'React',
    sourceVersion: 1,
    fileCount: 1,
    createdAt: now,
    author: '测试员',
    sourceAppId: 'app-1',
    cloneCount: 0,
    viewCount: 0,
    likeCount: 0,
    liked: false,
    mine: false,
    featured,
    pinnedOrder: featured ? 10 : 0,
    previewUrl: null,
    siteUrl: null,
  })
  const featured = work(true)

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
  await page.route('**/api/templates?*', (route) => route.fulfill({ json: [featured, work(false)] }))
  await page.route('**/api/templates/tpl-featured', (route) => route.fulfill({ json: { ...featured, related: [] } }))

  await page.goto('/community')
  await expect(page.getByRole('button', { name: '查看 精选看板' })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看 普通看板' })).toBeVisible()
  await expect(page.getByLabel('平台精选置顶作品')).toHaveCount(1)

  await page.getByRole('button', { name: '查看 精选看板' }).click()
  await expect(page).toHaveURL(/\/community\/tpl-featured$/)
  await expect(page.getByLabel('平台精选置顶作品').first()).toBeVisible()
  await expect(page.getByRole('heading', { name: /精选看板/ })).toContainText('精选')
})
