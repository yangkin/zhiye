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
    res.writeHead(200, { 'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream', 'cache-control': 'no-store', 'access-control-allow-origin': '*' })
    createReadStream(file).pipe(res)
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, close: () => new Promise((r) => server.close(() => r())) }))
  })
}

const SAFE_TAB_SKIP = /删除|移除|清空|重置|退出|注销|取消|提交|保存|发布|购买|支付|导出|下载|确认|发送/

async function readRenderState(target) {
  return target.evaluate(() => {
    const root = document.getElementById('root') || document.getElementById('app')
    const visible = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' }
    const isUi = (el) => !!el.closest('[data-zhiye-editor-ui]')
    const text = (document.body?.innerText || '').trim()
    const media = Array.from(document.querySelectorAll('body img, body svg, body canvas, body video, body button, body input')).filter((el) => !isUi(el) && visible(el)).length
    const vw = window.innerWidth
    const scrollWidth = document.documentElement ? document.documentElement.scrollWidth : vw
    const hasViewport = !!document.querySelector('meta[name="viewport"]')
    const brokenImgs = Array.from(document.querySelectorAll('img')).filter((img) => {
      if (isUi(img) || !visible(img)) return false
      const src = img.getAttribute('src') || ''
      if (/^https?:\/\//i.test(src) && !src.startsWith(location.origin)) return false
      return img.complete && img.naturalWidth === 0
    }).map((img) => img.getAttribute('alt') || img.getAttribute('src') || '图片').slice(0, 3)

    const btns = Array.from(document.querySelectorAll('nav button, header button, main button, a[role="button"]')).filter((el) => !isUi(el) && visible(el)).slice(0, 30)
    const overlaps = []
    for (let i = 0; i < btns.length && overlaps.length < 2; i++) {
      const a = btns[i].getBoundingClientRect()
      for (let j = i + 1; j < btns.length; j++) {
        if (btns[i].contains(btns[j]) || btns[j].contains(btns[i])) continue
        const b = btns[j].getBoundingClientRect()
        const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left)
        const oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
        if (ox > 8 && oy > 8) {
          const la = (btns[i].innerText || btns[i].getAttribute('aria-label') || '按钮').trim().slice(0, 12)
          const lb = (btns[j].innerText || btns[j].getAttribute('aria-label') || '按钮').trim().slice(0, 12)
          overlaps.push(`「${la}」与「${lb}」`)
          break
        }
      }
    }
    return { hasRoot: !!root, rootChildren: root ? root.childElementCount : 0, textLen: text.length, media, title: document.title, vw, scrollWidth, hasViewport, brokenImgs, overlaps }
  })
}

function checkRenderState(content, requireRoot, errors, prefix = '', maxWidth = 0) {
  if (!content) return
  if (requireRoot && (!content.hasRoot || content.rootChildren === 0)) {
    errors.push(`${prefix}页面是空白的：挂载节点（#root / #app）里没有渲染出任何内容（可能是渲染时报错或 App 返回了空内容）`)
  } else if (content.textLen === 0 && content.media === 0) {
    errors.push(`${prefix}页面是空白的：body 里没有可见内容`)
  } else if (maxWidth > 0 && (content.scrollWidth > content.vw + 16 || (content.hasViewport && content.vw > maxWidth + 16))) {
    const actual = Math.max(content.scrollWidth, content.vw)
    errors.push(`${prefix}存在横向溢出：页面宽度 ${actual}px 超出视口 ${maxWidth}px，请检查布局响应式适配`)
  } else if (content.brokenImgs?.length) {
    errors.push(`${prefix}存在无法加载的图片：${content.brokenImgs.join('、')}`)
  } else if (content.overlaps?.length) {
    errors.push(`${prefix}控件位置重叠：${content.overlaps.join('、')}`)
  }
}

async function attachPageWatchers(page, { origin, blocked, errors, warnings, prefix = '' }) {
  await page.route('**/*', (route) => {
    const u = route.request().url()
    if (u.startsWith(origin) || u.startsWith('data:') || u.startsWith('blob:')) return route.continue()
    blocked.add(u)
    return route.abort('blockedbyclient')
  })
  page.on('pageerror', (err) => errors.push(`${prefix}页面脚本错误：${err.message}`))
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return
    const url = msg.location()?.url || ''
    const text = msg.text()
    if ((url && !url.startsWith(origin)) || /ERR_BLOCKED_BY_CLIENT/.test(text)) { warnings.push(`外部资源未加载：${url || text}`); return }
    errors.push(`${prefix}控制台错误：${text}${url ? `（${url.replace(origin, '')}）` : ''}`)
  })
  page.on('response', (res) => {
    const u = res.url()
    if (u.startsWith(origin) && res.status() >= 400 && !/\/favicon\.ico$/.test(u)) errors.push(`${prefix}资源加载失败：${u.replace(origin, '')}（HTTP ${res.status()}）`)
  })
}

/**
 * 返回 { ok, skipped?, detail }
 * requireRoot：模板项目要求挂载节点（React 模板 #root / Vue 模板 #app）非空
 */
export async function smokeTest(dir, { requireRoot = false, captureShot = false, chromePath = process.env.ZHIYE_CHROME_PATH, timeoutMs = 30000 } = {}) {
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
    await attachPageWatchers(page, { origin, blocked, errors, warnings })
    try {
      await page.goto(origin + '/index.html', { waitUntil: 'load', timeout: timeoutMs })
    } catch (e) {
      errors.push(`页面打开失败：${String(e.message).split('\n')[0]}`)
    }
    await page.waitForTimeout(800)
    try {
      checkRenderState(await readRenderState(page), requireRoot, errors, '', requireRoot ? 1280 : 0)
    } catch (e) {
      errors.push(`无法检查页面内容：${String(e.message).split('\n')[0]}`)
    }

    // 多页面 hash 路由巡检：不点击操作按钮，只切换侧边/导航里的 #/ 链接验证不会报错白屏
    if (requireRoot && errors.length === 0) {
      try {
        const hashes = await page.evaluate(() => {
          const cur = window.location.hash
          const out = []
          for (const a of document.querySelectorAll('a[href^="#/"]')) {
            const h = a.getAttribute('href')
            if (h && h !== cur && !out.includes(h)) out.push(h)
            if (out.length >= 5) break
          }
          return out
        })
        for (const h of hashes) {
          await page.evaluate((hash) => { window.location.hash = hash }, h)
          await page.waitForTimeout(200)
          checkRenderState(await readRenderState(page), requireRoot, errors, `路由 ${h} `)
        }
      } catch (e) {
        errors.push(`路由切换检查失败：${String(e.message).split('\n')[0]}`)
      }
    }

    // 非破坏性导航/标签探活：点开前 3 个安全 tab / 导航按钮，验证切换不会抛出运行时异常或白屏
    if (requireRoot && errors.length === 0) {
      try {
        const candidates = await page.evaluate(() => {
          const btns = Array.from(document.querySelectorAll('[role="tab"], nav button, aside button'))
          return btns.map((b, idx) => {
            const r = b.getBoundingClientRect()
            const cs = getComputedStyle(b)
            const label = (b.innerText || b.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ')
            const ok = r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && !b.disabled
            return { idx, label, ok }
          }).filter((x) => x.ok && x.label)
        })
        let clicked = 0
        for (const c of candidates) {
          if (clicked >= 3 || errors.length > 0) break
          if (SAFE_TAB_SKIP.test(c.label)) continue
          await page.locator('[role="tab"], nav button, aside button').nth(c.idx).click({ timeout: 2000 }).catch(() => {})
          await page.waitForTimeout(150)
          checkRenderState(await readRenderState(page), requireRoot, errors, `切换「${c.label.slice(0, 16)}」后 `)
          clicked++
        }
        // 安全搜索框输入探活：验证筛选状态变化不触发崩溃或空白
        if (errors.length === 0) {
          const searchInput = page.locator('input[type="search"], input[placeholder*="搜索"], input[placeholder*="筛选"]').first()
          if (await searchInput.isVisible({ timeout: 300 }).catch(() => false)) {
            await searchInput.fill('测试').catch(() => {})
            await page.waitForTimeout(100)
            await searchInput.fill('').catch(() => {})
            await page.waitForTimeout(100)
            checkRenderState(await readRenderState(page), requireRoot, errors, '输入搜索词后 ')
          }
        }
        // 安全下拉框与非破坏性弹窗按钮探活：验证筛选切换与新建弹窗打开不崩溃
        if (errors.length === 0) {
          await probeSafeControls(page, requireRoot, errors)
        }
      } catch (e) {
        errors.push(`交互切换检查失败：${String(e.message).split('\n')[0]}`)
      }
    }

    let screenshot = null
    if (captureShot && errors.length === 0) {
      try {
        screenshot = (await page.screenshot({ type: 'jpeg', quality: 60, fullPage: false })).toString('base64')
      } catch {}
    }

    // 移动端（390×844）视口冒烟：检测常见的小屏横向溢出或渲染报错
    if (requireRoot && errors.length === 0) {
      let mobileCtx
      try {
        mobileCtx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
        const mp = await mobileCtx.newPage()
        await attachPageWatchers(mp, { origin, blocked, errors, warnings, prefix: '移动端 ' })
        await mp.goto(origin + '/index.html', { waitUntil: 'load', timeout: timeoutMs })
        await mp.waitForTimeout(450)
        checkRenderState(await readRenderState(mp), requireRoot, errors, '移动端 ', 390)
      } catch (e) {
        errors.push(`移动端检查失败：${String(e.message).split('\n')[0]}`)
      } finally {
        if (mobileCtx) await mobileCtx.close().catch(() => {})
      }
    }

    // 沙箱 iframe 冒烟：工作台预览使用 sandbox="allow-scripts allow-forms"（禁用 localStorage/sessionStorage）
    if (errors.length === 0) {
      const sboxPage = await ctx.newPage()
      const sboxErrors = []
      try {
        await attachPageWatchers(sboxPage, { origin, blocked, errors: sboxErrors, warnings, prefix: '沙箱预览' })
        await sboxPage.setContent(
          `<!doctype html><html><body style="margin:0"><iframe sandbox="allow-scripts allow-forms" src="${origin}/index.html" style="width:1280px;height:800px;border:0"></iframe></body></html>`,
          { waitUntil: 'load', timeout: timeoutMs },
        )
        const frame = sboxPage.frames()[1]
        if (frame) {
          await frame.waitForLoadState('load', { timeout: timeoutMs }).catch(() => {})
          await sboxPage.waitForTimeout(600)
          checkRenderState(await readRenderState(frame), requireRoot, sboxErrors, '沙箱预览')
        }
      } catch (e) {
        sboxErrors.push(`沙箱预览打开失败：${String(e.message).split('\n')[0]}`)
      } finally {
        await sboxPage.close().catch(() => {})
      }
      if (sboxErrors.length) {
        const msg = sboxErrors.join('\n')
        const storageHint = /localStorage|sessionStorage|SecurityError|空白/.test(msg)
          ? '（工作台预览 iframe 使用 sandbox="allow-scripts allow-forms" 禁用本地存储，请对所有 localStorage / sessionStorage 读写加 try/catch 降级为内存状态）'
          : ''
        errors.push(...sboxErrors)
        if (storageHint) errors.push(storageHint)
      }
    }

    if (blocked.size) warnings.push(...[...blocked].slice(0, 5).map((u) => `已拦截外部请求：${u}`))
    await ctx.close().catch(() => {})
    const uniq = [...new Set(errors)]
    const detail = [...uniq, ...[...new Set(warnings)].map((w) => `（提示）${w}`)].join('\n')
    const out = { ok: uniq.length === 0, detail: uniq.length ? detail : warnings.length ? detail : '' }
    if (screenshot && out.ok) out.screenshot = screenshot
    return out
  } finally {
    if (browser) await browser.close().catch(() => {})
    await srv.close().catch(() => {})
    try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
  }
}

const SAFE_MODAL_TRIGGER = /^(新增|新建|创建|添加|登记|筛选|详情|查看)/

async function probeSafeControls(page, requireRoot, errors) {
  const selects = page.locator('select')
  const selCount = Math.min(2, await selects.count().catch(() => 0))
  for (let i = 0; i < selCount && errors.length === 0; i++) {
    const sel = selects.nth(i)
    if (!(await sel.isVisible({ timeout: 200 }).catch(() => false))) continue
    const vals = await sel.evaluate((el) => Array.from(el.options).filter((o) => !o.disabled).map((o) => o.value)).catch(() => [])
    if (vals.length >= 2) {
      await sel.selectOption(vals[1]).catch(() => {})
      await page.waitForTimeout(100)
      checkRenderState(await readRenderState(page), requireRoot, errors, '切换下拉筛选项后 ')
      await sel.selectOption(vals[0]).catch(() => {})
      await page.waitForTimeout(80)
    }
  }
  if (errors.length === 0) {
    const btns = await page.evaluate(() => Array.from(document.querySelectorAll('button')).map((b, idx) => {
      const r = b.getBoundingClientRect()
      const cs = getComputedStyle(b)
      const label = (b.innerText || b.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ')
      return { idx, label, ok: r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && !b.disabled }
    }).filter((x) => x.ok && x.label)).catch(() => [])
    const target = btns.find((b) => SAFE_MODAL_TRIGGER.test(b.label) && !SAFE_TAB_SKIP.test(b.label))
    if (target) {
      await page.locator('button').nth(target.idx).click({ timeout: 1500 }).catch(() => {})
      await page.waitForTimeout(150)
      checkRenderState(await readRenderState(page), requireRoot, errors, `点击「${target.label.slice(0, 12)}」后 `)
      await page.keyboard.press('Escape').catch(() => {})
      await page.waitForTimeout(100)
    }
  }
}

/**
 * 面向实盘评测与端到端校验的真实浏览器交互流验证：
 * 验证页面在真实 Chromium 中渲染出预期业务文案，并执行无破坏交互探活。
 */
export async function runLiveFlow(url, flow = {}, { chromePath = process.env.ZHIYE_CHROME_PATH, timeoutMs = 30000 } = {}) {
  if (!chromePath || !existsSync(chromePath)) return { ok: true, skipped: true, detail: '无 Chrome' }
  const { chromium } = await import('playwright-core')
  const userDataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-flow-'))
  let browser
  const errors = []
  const warnings = []
  try {
    browser = await chromium.launch({
      executablePath: chromePath,
      headless: true,
      timeout: timeoutMs,
      args: ['--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check'],
      env: { ...process.env, HOME: process.env.HOME || userDataDir },
    })
    const origin = new URL(url).origin
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } })
    const page = await ctx.newPage()
    await attachPageWatchers(page, { origin, blocked: new Set(), errors, warnings })
    await page.goto(url, { waitUntil: 'load', timeout: timeoutMs })
    await page.waitForTimeout(800)
    checkRenderState(await readRenderState(page), true, errors, '', 1280)
    const bodyText = await page.evaluate(() => document.body?.innerText || '')
    for (const txt of flow.expectText || []) {
      if (!bodyText.includes(txt)) errors.push(`浏览器实机页面未渲染预期业务内容：${txt}`)
    }
    if (flow.probeControls !== false && errors.length === 0) {
      await probeSafeControls(page, true, errors)
    }
    await ctx.close().catch(() => {})
    return { ok: errors.length === 0, detail: errors.join('；') }
  } catch (e) {
    return { ok: false, detail: `浏览器实机流程检查异常：${e.message}` }
  } finally {
    if (browser) await browser.close().catch(() => {})
    try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
  }
}

function skipped(reason) {
  log(`smoke skipped: ${reason}`)
  if (process.env.ZHIYE_SMOKE_REQUIRED === '1') return { ok: false, detail: `冒烟测试无法执行：${reason}` }
  return { ok: true, skipped: true, detail: `已跳过：${reason}` }
}
