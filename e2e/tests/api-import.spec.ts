// 接口级端到端：导入代码并部署（HTML / ZIP / 文件夹 / 框架识别 / 失败 / 取消）
import { test, expect } from '@playwright/test'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { zipSync, strToU8 } from 'fflate'
import { Api } from './helpers'

test.describe.configure({ mode: 'serial' })
const FIX = path.join(__dirname, '..', 'fixtures')

let api: Api
test.beforeAll(async ({ playwright }) => {
  api = new Api(await playwright.request.newContext({ baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080' }))
  await api.setFaults({})
})

async function upload(name: string, buffer: Buffer, folderName?: string) {
  const res = await api.req.post('/api/imports/upload', {
    multipart: { file: { name, mimeType: name.endsWith('.zip') ? 'application/zip' : 'text/html', buffer }, ...(folderName ? { folderName } : {}) },
  })
  const text = await res.text()
  return { status: res.status(), body: text ? JSON.parse(text) : null }
}
function zip(files: Record<string, string>) { return Buffer.from(zipSync(Object.fromEntries(Object.entries(files).map(([k, v]) => [k, strToU8(v)])))) }
function zipDir(dir: string) {
  const out: Record<string, Uint8Array> = {}
  const walk = (d: string, rel: string) => { for (const f of readdirSync(d)) { const p = path.join(d, f); const r = rel ? rel + '/' + f : f; if (statSync(p).isDirectory()) walk(p, r); else out[path.basename(dir) + '/' + r] = readFileSync(p) } }
  walk(dir, '')
  return Buffer.from(zipSync(out))
}
async function appIds() { return (await api.get('/api/apps')).map((a: any) => a.id) }

const VITE_PKG = JSON.stringify({ name: 'v', private: true, type: 'module', scripts: { build: 'vite build' }, dependencies: { react: '19.2.0', 'react-dom': '19.2.0' }, devDependencies: { vite: '7.2.7', '@vitejs/plugin-react': '5.1.0' } })
const VITE_FILES = {
  'package.json': VITE_PKG,
  'vite.config.js': "import { defineConfig } from 'vite'\nimport react from '@vitejs/plugin-react'\nexport default defineConfig({ plugins: [react()], base: './' })\n",
  'index.html': '<!doctype html><html><body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body></html>',
  'src/main.jsx': "import { createRoot } from 'react-dom/client'\nimport { useState } from 'react'\nfunction App(){ const [n,setN]=useState(0); return <main><h1>导入的 Vite 应用</h1><button onClick={()=>setN(n+1)}>点了 {n} 次</button></main> }\ncreateRoot(document.getElementById('root')).render(<App/>)\n",
}

test('单文件 HTML：识别 → 导入并部署 → 预览可交互 → 可视化编辑写回源码', async ({ page }) => {
  const { status, body: m } = await upload('小小花园.html', readFileSync(path.join(FIX, 'garden.html')))
  expect(status, JSON.stringify(m)).toBe(200)
  expect(m.framework).toBe('HTML')
  expect(m.deployable).toBe(true)
  expect(m.fileCount).toBe(1)
  expect(m.name).toBe('小小花园')

  const { app, task } = await api.post('/api/imports', { uploadId: m.uploadId, name: '小小花园' })
  expect(await appIds()).not.toContain(app.id) // 成功前不出现在列表
  const done = await api.waitTask(task.id, { timeoutMs: 5 * 60_000 })
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(await appIds()).toContain(app.id)
  expect(detail.source).toBe('import')
  expect(detail.visualEditable).toBe(true)
  expect(detail.siteUrl).toBeTruthy() // 导入即部署
  const versions = await api.get(`/api/apps/${app.id}/versions`)
  expect(versions).toHaveLength(1)
  expect(versions[0].isPublished).toBe(true)
  const hist = await api.get(`/api/history?appId=${app.id}`)
  expect(hist).toHaveLength(1)

  await page.goto(detail.siteUrl)
  await page.getByRole('button', { name: '种下一份灵感' }).click()
  await expect(page.getByRole('button', { name: '已记录一份灵感' })).toBeVisible()

  await page.goto(detail.previewUrl)
  const loc = await page.locator('h1').getAttribute('data-loc')
  expect(loc).toMatch(/^index\.html:\d+:\d+$/)
  await api.put(`/api/apps/${app.id}/draft`, { baseVersionId: detail.currentVersion.id, patches: [{ loc, text: '让想法，自然生长。', styles: { color: '#4f78eb' } }] })
  const save = await api.post(`/api/apps/${app.id}/draft/commit`, {})
  expect((await api.waitTask(save.id)).status).toBe('SUCCEEDED')
  const cur = await api.get(`/api/apps/${app.id}`)
  const src = await (await api.req.get(`/api/versions/${cur.currentVersion.id}/file?path=index.html`)).text()
  expect(src).toContain('让想法，自然生长。')
  expect(src).toContain('onclick=') // 原有交互保留
  expect(src).not.toContain('data-loc') // 源码里不带预览标记
  await page.goto(cur.previewUrl)
  await expect(page.locator('h1')).toHaveText('让想法，自然生长。')
  await page.getByRole('button', { name: '种下一份灵感' }).click()
  await expect(page.getByRole('button', { name: '已记录一份灵感' })).toBeVisible()
})

test('ZIP 与文件夹（前端打包）导入静态项目', async ({ page }) => {
  const z = await upload('HTML项目.zip', readFileSync(path.join(FIX, 'html-project.zip')))
  expect(z.status, JSON.stringify(z.body)).toBe(200)
  expect(['HTML', '静态站点']).toContain(z.body.framework)
  const r1 = await api.post('/api/imports', { uploadId: z.body.uploadId, name: 'ZIP 花园' })
  expect((await api.waitTask(r1.task.id, { timeoutMs: 5 * 60_000 })).status).toBe('SUCCEEDED')

  const f = await upload('HTML项目.zip', zipDir(path.join(FIX, 'html-project')), 'HTML项目')
  expect(f.status, JSON.stringify(f.body)).toBe(200)
  expect(f.body.name).toBe('HTML项目')
  const r2 = await api.post('/api/imports', { uploadId: f.body.uploadId, name: '文件夹花园' })
  expect((await api.waitTask(r2.task.id, { timeoutMs: 5 * 60_000 })).status).toBe('SUCCEEDED')
  const app = await api.get(`/api/apps/${r2.app.id}`)
  await page.goto(app.siteUrl)
  await expect(page.locator('h1')).toBeVisible()
})

test('无效文件与不安全的压缩包给出可修正提示', async () => {
  const bad = await upload('坏文件.zip', Buffer.from('这不是 zip'))
  expect(bad.status).toBe(400)
  expect(bad.body.code).toBe('IMPORT_INVALID')
  expect(bad.body.message).toBeTruthy()
  const slip = await upload('slip.zip', zip({ '../evil.html': '<h1>x</h1>', 'index.html': '<h1>ok</h1>' }))
  expect(slip.status).toBe(400)
  const empty = await upload('empty.html', Buffer.from('   '))
  expect(empty.status).toBe(400)
})

test('框架识别：Next.js 不可部署并说明原因，后端拒绝创建', async () => {
  const n = await upload('next-app.zip', zip({ 'next-app/package.json': JSON.stringify({ dependencies: { next: '15.0.0', react: '19.0.0' } }), 'next-app/app/page.tsx': 'export default function P(){return <h1>hi</h1>}' }))
  expect(n.status).toBe(200)
  expect(n.body.framework).toBe('Next.js')
  expect(n.body.deployable).toBe(false)
  expect(n.body.unsupportedReason).toContain('Next.js')
  const before = await appIds()
  await api.expectError('POST', '/api/imports', { uploadId: n.body.uploadId, name: 'next' }, 400, 'IMPORT_UNSUPPORTED')
  expect(await appIds()).toEqual(before)
  const py = await upload('py.zip', zip({ 'app.py': 'print(1)', 'requirements.txt': 'flask' }))
  expect(py.body.framework).toBe('Python')
  expect(py.body.deployable).toBe(false)
})

test('Vite 项目：真实安装依赖并构建部署', async ({ page }) => {
  const u = await upload('vite-app.zip', zip(Object.fromEntries(Object.entries(VITE_FILES).map(([k, v]) => ['vite-app/' + k, v]))))
  expect(u.body.framework).toBe('Vite + React')
  expect(u.body.deployable).toBe(true)
  const { app, task } = await api.post('/api/imports', { uploadId: u.body.uploadId, name: 'Vite 导入' })
  const done = await api.waitTask(task.id, { timeoutMs: 10 * 60_000 })
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  await page.goto(detail.siteUrl)
  await page.getByRole('button', { name: /点了 0 次/ }).click()
  await expect(page.getByRole('button', { name: /点了 1 次/ })).toBeVisible()
})

test('Vite 项目构建失败：任务失败、显示日志、不留下应用', async () => {
  const broken = { ...VITE_FILES, 'src/main.jsx': 'import { createRoot } from "react-dom/client"\nconst = ;\n' }
  const u = await upload('broken.zip', zip(Object.fromEntries(Object.entries(broken).map(([k, v]) => ['broken/' + k, v]))))
  const before = await appIds()
  const { app, task } = await api.post('/api/imports', { uploadId: u.body.uploadId, name: '坏项目' })
  const done = await api.waitTask(task.id, { timeoutMs: 10 * 60_000 })
  expect(done.status).toBe('FAILED')
  expect(done.error.message).toBeTruthy()
  expect(await appIds()).toEqual(before)
  await api.expectError('GET', `/api/apps/${app.id}`, undefined, 404)
})

test('导入中取消：不创建应用', async () => {
  const u = await upload('vite-cancel.zip', zip(Object.fromEntries(Object.entries(VITE_FILES).map(([k, v]) => ['c/' + k, v]))))
  const before = await appIds()
  const { app, task } = await api.post('/api/imports', { uploadId: u.body.uploadId, name: '要取消的导入' })
  await new Promise((r) => setTimeout(r, 3000))
  await api.post(`/api/tasks/${task.id}/cancel`)
  const done = await api.waitTask(task.id, { timeoutMs: 2 * 60_000 })
  expect(done.status).toBe('CANCELLED')
  expect(await appIds()).toEqual(before)
  await api.expectError('GET', `/api/apps/${app.id}`, undefined, 404)
})

test('前后端同仓库：只部署前端子目录；默认 base、前置检查失败也能构建；预览子路径可用；之后改代码离线重建', async ({ page }) => {
  const front = {
    ...VITE_FILES,
    'package.json': JSON.stringify({ ...JSON.parse(VITE_PKG), scripts: { build: 'node -e "process.exit(1)" && vite build' } }),
    'vite.config.js': "import { defineConfig } from 'vite'\nimport react from '@vitejs/plugin-react'\nexport default defineConfig({ plugins: [react()], server: { proxy: { '/api': 'http://localhost:8081' } } })\n",
  }
  const files: Record<string, string> = { 'repo/pom.xml': '<project/>', 'repo/src/main/java/App.java': 'class App {}' }
  for (const [k, v] of Object.entries(front)) files['repo/frontend/' + k] = v
  const u = await upload('repo.zip', zip(files))
  expect(u.body.deployable).toBe(true)
  expect(u.body.rootDir).toBe('frontend')
  expect(u.body.warnings.map((w: any) => w.code)).toEqual(expect.arrayContaining(['backend', 'api']))
  const { app, task } = await api.post('/api/imports', { uploadId: u.body.uploadId, name: '前后端仓库', rootDir: 'frontend' })
  const done = await api.waitTask(task.id, { timeoutMs: 10 * 60_000 })
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await api.get(`/api/apps/${app.id}`)
  expect(detail.framework).toBe('Vite + React')
  await page.goto(detail.previewUrl)
  await page.getByRole('button', { name: /点了 0 次/ }).click()
  await expect(page.getByRole('button', { name: /点了 1 次/ })).toBeVisible()

  const main = await (await api.req.get(`/api/versions/${detail.currentVersion.id}/file?path=src/main.jsx`)).text()
  const save = await api.post(`/api/apps/${app.id}/code`, { baseVersionId: detail.currentVersion.id, files: [{ path: 'src/main.jsx', content: main.replace('导入的 Vite 应用', '改过的导入应用') }] })
  const saved = await api.waitTask(save.id, { timeoutMs: 5 * 60_000 })
  expect(saved.status, JSON.stringify(saved.error)).toBe('SUCCEEDED')
  const after = await api.get(`/api/apps/${app.id}`)
  await page.goto(after.previewUrl)
  await expect(page.getByRole('heading', { name: '改过的导入应用' })).toBeVisible()
})
