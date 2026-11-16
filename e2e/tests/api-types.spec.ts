// 接口级端到端：新的创作类型（开发skill、技术文档）产出符合约定的项目（真实 Kimi）
import { test, expect } from '@playwright/test'
import { Api } from './helpers'

test.describe.configure({ mode: 'serial' })

let api: Api
test.beforeAll(async ({ playwright }) => {
  api = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await api.setFaults({})
})

async function files(versionId: string): Promise<string[]> {
  return (await api.get(`/api/versions/${versionId}/files`)).map((f: any) => f.path)
}
async function file(versionId: string, path: string) {
  return (await api.req.get(`/api/versions/${versionId}/file?path=${encodeURIComponent(path)}`)).text()
}

test('开发skill：产出技能包（不是网页），不可预览/发布，可下载，可直接编辑文件并通过检查后存为新版本', async ({ page }) => {
  // 技能不需要创造计划：创建后直接开始生成
  const app = await api.post('/api/apps', { type: '开发skill', prompt: '做一个“周报生成” skill：根据本周完成事项、问题和下周计划，按公司格式生成四段式周报，附模板和一个统计字数的 Python 脚本。', mode: '快速', model: 'auto', assetIds: [] })
  expect(app.projectKind).toBe('skill')
  expect(app.phase).toBe('generating')
  expect(app.activeTask?.type).toBe('generate')
  expect((await api.get(`/api/apps/${app.id}/tasks`)).some((t: any) => t.type === 'plan')).toBeFalsy()
  const done = await api.waitTask(app.activeTask.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.previewUrl).toBeNull()
  expect(detail.siteUrl).toBeNull()
  expect(detail.visualEditable).toBe(false)
  const list = await files(detail.currentVersion.id)
  expect(list).toContain('SKILL.md')
  expect(list.some((p) => p.startsWith('scripts/'))).toBeTruthy()
  expect(list.some((p) => p.startsWith('src/') || p === 'package.json' || p === 'index.html')).toBeFalsy()
  const md = await file(detail.currentVersion.id, 'SKILL.md')
  expect(md).toMatch(/^---[\s\S]*name:\s*[a-z0-9-]+[\s\S]*description:[\s\S]*---/)
  const events = await api.events(done.id)
  const verify = events.filter((e) => e.type === 'verify').at(-1)
  expect(verify.payload.checks.map((c: any) => c.name)).toEqual(expect.arrayContaining(['SKILL.md 格式', '引用文件', '脚本语法']))
  // 不可发布，可下载技能包
  await api.expectError('POST', `/api/apps/${app.id}/publish`, {}, 400, 'SKILL_NOT_PUBLISHABLE')
  const zip = await api.req.get(`/api/versions/${detail.currentVersion.id}/export`, { timeout: 60_000 })
  expect(zip.ok()).toBeTruthy()
  const name = md.match(/name:\s*([a-z0-9-]+)/)![1]
  expect((await zip.body()).includes(Buffer.from(name + '/SKILL.md'))).toBeTruthy()
  // 直接编辑 SKILL.md → 检查通过 → 新版本
  const edited = md.replace(/\n# /, '\n> 由织页编辑器修改\n\n# ')
  const t = await api.post(`/api/apps/${app.id}/code`, { baseVersionId: detail.currentVersion.id, files: [{ path: 'SKILL.md', content: edited }] })
  expect(t.type).toBe('code_save')
  const saved = await api.waitTask(t.id, { timeoutMs: 3 * 60_000 })
  expect(saved.status, JSON.stringify(saved.error)).toBe('SUCCEEDED')
  const after = await api.get(`/api/apps/${app.id}`)
  expect(after.currentVersion.number).toBe(2)
  expect(await file(after.currentVersion.id, 'SKILL.md')).toContain('由织页编辑器修改')
  // 改坏格式 → 检查不通过，不产生版本
  const bad = await api.post(`/api/apps/${app.id}/code`, { baseVersionId: after.currentVersion.id, files: [{ path: 'SKILL.md', content: '没有 YAML 头部' }] })
  const failed = await api.waitTask(bad.id, { timeoutMs: 3 * 60_000 })
  expect(failed.status).toBe('FAILED')
  expect((await api.get(`/api/apps/${app.id}`)).currentVersion.number).toBe(2)
  // 非法路径
  await api.expectError('POST', `/api/apps/${app.id}/code`, { baseVersionId: after.currentVersion.id, files: [{ path: '../evil.md', content: 'x' }] }, 400)
})

test('技术文档：Markdown 放在 docs/，页面有目录并能切换文档', async ({ page }) => {
  const app = await api.createApp('写一份“内部接口网关接入指南”技术文档，包含概述、申请接入、鉴权方式、错误码四个章节，附代码示例。', '技术文档')
  const done = await api.generate(app.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  const mdFiles = (await files(detail.currentVersion.id)).filter((p) => p.startsWith('docs/') && p.endsWith('.md'))
  expect(mdFiles.length).toBeGreaterThan(0)
  await page.goto(detail.previewUrl)
  await expect(page.locator('body')).toContainText(/鉴权/)
  await expect(page.locator('pre, code').first()).toBeVisible()
})

test('纯脚本项目：日常开发里写 python 程序 → 产出脚本、没有预览也不能发布，可下载源码', async () => {
  const app = await api.createApp('写一个 python 程序，用来清洗 excel 数据：去重列名、删空行、全角转半角，命令行指定输入输出文件。', '日常开发')
  expect(app.projectKind).toBe('script')
  const done = await api.generate(app.id)
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  // 脚本项目：没有页面构建产物
  expect(detail.previewUrl ?? null).toBeNull()
  const files = await api.get(`/api/versions/${detail.currentVersion.id}/files`)
  expect(files.some((f: any) => /\.py$/.test(f.path))).toBe(true)
  expect(files.some((f: any) => f.path === 'README.md')).toBe(true)
  // 不能发布、没有单文件页面
  await api.expectError('POST', `/api/apps/${app.id}/publish`, {}, 400)
  await api.expectError('GET', `/api/versions/${detail.currentVersion.id}/page`, undefined, 400)
  // 导出源码可以
  const zip = await api.req.get(`/api/versions/${detail.currentVersion.id}/export`, { timeout: 60_000 })
  expect(zip.status()).toBe(200)
})
