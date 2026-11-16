// 冒烟测试：127.0.0.1 随机端口的静态文件服务器 + playwright-core 驱动系统 Chrome（无头）。
import http from 'node:http'
import { createReadStream, existsSync, statSync, mkdtempSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { log } from './util.mjs'

const MIME = {
  '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.webp': 'image/webp', '.avif': 'image/avif', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
  '.otf': 'font/otf', '.txt': 'text/plain; charset=utf-8', '.wasm': 'application/wasm', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mp3': 'audio/mpeg', '.map': 'application/json',
}

export function startStaticServer(root) {
  const base = path.resolve(root)
  const server = http.createServer((req, res) => {
    let p
    try { p = decodeURIComponent(new URL(req.url, 'http://x').pathname) } catch { res.writeHead(400).end(); return }
    let file = path.resolve(base, '.' + p)
    if (file !== base && !file.startsWith(base + path.sep)) { res.writeHead(403).end(); return }
    if (existsSync(file) && statSync(file).isDirectory()) file = path.join(file, 'index.html')
    if (!existsSync(file) || !statSync(file).isFile()) { res.writeHead(404, { 'content-type': 'text/plain' }).end('not found'); return }
    res.writeHead(200, { 'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'cache-control': 'no-store' })
    createReadStream(file).pipe(res)
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, close: () => new Promise((r) => server.close(() => r())) }))
  })
}

/**
 * 返回 { ok, skipped?, detail }
 * requireRoot：模板项目要求挂载节点（React 模板 #root / Vue 模板 #app）非空
 */
export async function smokeTest(dir, { requireRoot = false, chromePath = process.env.ZHIYE_CHROME_PATH, timeoutMs = 30000 } = {}) {
  if (!existsSync(path.join(dir, 'index.html'))) return { ok: false, detail: '构建产物里没有 index.html' }
  if (!chromePath || !existsSync(chromePath)) {
    return skipped(`没有可用的 Chrome（ZHIYE_CHROME_PATH=${chromePath || '未设置'}）`)
  }
  let srv
  try { srv = await startStaticServer(dir) } catch (e) { return skipped(`无法启动本地服务器：${e.message}`) }
  const { chromium } = await import('playwright-core')
  const userDataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-chrome-'))
  let browser
  const errors = []
  const warnings = []
  try {
    try {
      browser = await chromium.launch({
        executablePath: chromePath,
        headless: true,
        timeout: 30000,
        args: ['--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
          '--disable-component-update', '--disable-sync', '--metrics-recording-only', '--disable-features=MediaRouter,OptimizationHints,Translate', '--use-mock-keychain', '--password-store=basic'],
        env: { ...process.env, HOME: process.env.HOME || userDataDir },
      })
    } catch (e) {
      return skipped(`Chrome 无法启动：${String(e.message).split('\n')[0]}`)
    }
    const origin = `http://127.0.0.1:${srv.port}`
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page = await ctx.newPage()
    const blocked = new Set()
    await page.route('**/*', (route) => {
      const u = route.request().url()
      if (u.startsWith(origin) || u.startsWith('data:') || u.startsWith('blob:')) return route.continue()
      blocked.add(u)
      return route.abort('blockedbyclient')
    })
    page.on('pageerror', (err) => errors.push(`页面脚本错误：${err.message}`))
    page.on('console', (msg) => {
      if (msg.type() !== 'error') return
      const url = msg.location()?.url || ''
      const text = msg.text()
      if ((url && !url.startsWith(origin)) || /ERR_BLOCKED_BY_CLIENT/.test(text)) { warnings.push(`外部资源未加载：${url || text}`); return }
      errors.push(`控制台错误：${text}${url ? `（${url.replace(origin, '')}）` : ''}`)
    })
    page.on('response', (res) => {
      const u = res.url()
      if (u.startsWith(origin) && res.status() >= 400 && !/\/favicon\.ico$/.test(u)) errors.push(`资源加载失败：${u.replace(origin, '')}（HTTP ${res.status()}）`)
    })
    try {
      await page.goto(origin + '/index.html', { waitUntil: 'load', timeout: timeoutMs })
    } catch (e) {
      errors.push(`页面打开失败：${String(e.message).split('\n')[0]}`)
    }
    await page.waitForTimeout(800)
    let content = null
    try {
      content = await page.evaluate(() => {
        const root = document.getElementById('root') || document.getElementById('app')
        const visible = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' }
        const isUi = (el) => !!el.closest('[data-zhiye-editor-ui]')
        const text = (document.body?.innerText || '').trim()
        const media = Array.from(document.querySelectorAll('body img, body svg, body canvas, body video, body button, body input')).filter((el) => !isUi(el) && visible(el)).length
        return { hasRoot: !!root, rootChildren: root ? root.childElementCount : 0, textLen: text.length, media, title: document.title }
      })
    } catch (e) {
      errors.push(`无法检查页面内容：${String(e.message).split('\n')[0]}`)
    }
    if (content) {
      if (requireRoot && (!content.hasRoot || content.rootChildren === 0)) errors.push('页面是空白的：挂载节点（#root / #app）里没有渲染出任何内容（可能是渲染时报错或 App 返回了空内容）')
      else if (content.textLen === 0 && content.media === 0) errors.push('页面是空白的：body 里没有可见内容')
    }
    if (blocked.size) warnings.push(...[...blocked].slice(0, 5).map((u) => `已拦截外部请求：${u}`))
    await ctx.close().catch(() => {})
    const uniq = [...new Set(errors)]
    const detail = [...uniq, ...[...new Set(warnings)].map((w) => `（提示）${w}`)].join('\n')
    return { ok: uniq.length === 0, detail: uniq.length ? detail : warnings.length ? detail : '' }
  } finally {
    if (browser) await browser.close().catch(() => {})
    await srv.close().catch(() => {})
    try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
  }
}

function skipped(reason) {
  log(`smoke skipped: ${reason}`)
  if (process.env.ZHIYE_SMOKE_REQUIRED === '1') return { ok: false, detail: `冒烟测试无法执行：${reason}` }
  return { ok: true, skipped: true, detail: `已跳过：${reason}` }
}
