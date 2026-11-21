// 截图自查：构建一份不带编辑桥的产物（静态项目直接用工作区），本地静态服务器 + 无头 Chrome 打开，
// 返回截图和页面文字摘要（标题、按钮、可见文字、横向溢出 / 文字被截断等布局问题、控制台报错）。
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { pathToFileURL } from 'node:url'
import { startStaticServer } from './smoke.mjs'
import { buildEnv, viteBuild } from './verify.mjs'

export const SHOT_DIR = '.zhiye/dist-shot'
export const DEVICES = {
  desktop: { viewport: { width: 1280, height: 800 }, label: '桌面 1280×800' },
  wide: { viewport: { width: 1920, height: 1080 }, label: '宽屏 1920×1080' },
  mobile: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, label: '手机 390×844' },
}

async function startViteDevServer(cwd) {
  const viteEntry = path.join(cwd, 'node_modules', 'vite', 'dist', 'node', 'index.js')
  if (!existsSync(viteEntry)) return null
  try {
    const { createServer } = await import(pathToFileURL(viteEntry).href)
    const server = await createServer({
      root: cwd,
      mode: 'development',
      clearScreen: false,
      logLevel: 'silent',
      optimizeDeps: { holdUntilCrawlEnd: false },
      server: { host: '127.0.0.1', port: 0, strictPort: false, hmr: false, fs: { allow: [cwd] } },
    })
    await server.listen()
    const addr = server.httpServer?.address()
    if (!addr || typeof addr !== 'object') { await server.close().catch(() => {}); return null }
    return { port: addr.port, close: () => server.close().catch(() => {}) }
  } catch {
    return null
  }
}

/** 返回 { ok, text, image?, mimeType? }；构建失败或没有浏览器时 ok=false，text 说明原因 */
export async function takeScreenshot(cwd, { projectKind = 'template', route = '', device = 'desktop', fullPage = false } = {}, env = process.env) {
  const chromePath = env.ZHIYE_CHROME_PATH
  if (!chromePath || !existsSync(chromePath)) return { ok: false, text: '截图不可用：当前环境没有浏览器，请用类型检查和构建结果自查。' }
  let root = cwd
  let srv = null
  const useVite = projectKind === 'template' || projectKind === 'vue' || projectKind === 'vite'
  if (useVite && existsSync(path.join(cwd, 'index.html'))) {
    srv = await startViteDevServer(cwd)
  }
  if (!srv) {
    if (useVite) {
      const b = await viteBuild(cwd, buildEnv(env, { ZHIYE_LOC_PLUGIN: '' }), { outDir: SHOT_DIR, minify: false })
      if (!b.ok) return { ok: false, text: `构建失败，先修好再截图：\n${String(b.detail).slice(0, 3000)}` }
      root = path.join(cwd, SHOT_DIR)
    }
    if (!existsSync(path.join(root, 'index.html'))) return { ok: false, text: '没有 index.html，无法截图' }
    srv = await startStaticServer(root)
  }
  const dev = DEVICES[device] || DEVICES.desktop
  const { chromium } = await import('playwright-core')
  const userDataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-shot-'))
  let browser
  const errors = []
  try {
    browser = await chromium.launch({
      executablePath: chromePath, headless: true, timeout: 30000,
      args: ['--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
        '--disable-component-update', '--disable-sync', '--use-mock-keychain', '--password-store=basic', '--font-render-hinting=none'],
      env: { ...process.env, HOME: process.env.HOME || userDataDir },
    })
    const origin = `http://127.0.0.1:${srv.port}`
    const ctx = await browser.newContext({ viewport: dev.viewport, isMobile: !!dev.isMobile, hasTouch: !!dev.hasTouch, deviceScaleFactor: 1, locale: 'zh-CN' })
    const page = await ctx.newPage()
    await page.route('**/*', (r) => {
      const u = r.request().url()
      return u.startsWith(origin) || u.startsWith('data:') || u.startsWith('blob:') ? r.continue() : r.abort('blockedbyclient')
    })
    page.on('pageerror', (e) => errors.push(`页面脚本错误：${e.message}`))
    page.on('console', (m) => { if (m.type() === 'error' && !/ERR_BLOCKED_BY_CLIENT|favicon/.test(m.text())) errors.push(`控制台错误：${m.text()}`) })
    const r = String(route || '').trim()
    const target = r.startsWith('#') ? `${origin}/index.html${r}` : r.startsWith('/') && projectKind === 'static' ? origin + r : `${origin}/index.html`
    await page.goto(target, { waitUntil: 'load', timeout: 30000 }).catch((e) => errors.push(`页面打开失败：${String(e.message).split('\n')[0]}`))
    await page.waitForTimeout(1200)
    const info = await page.evaluate(() => {
      const vw = window.innerWidth
      const visible = (el) => { const b = el.getBoundingClientRect(); const cs = getComputedStyle(el); return b.width > 0 && b.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' }
      const desc = (el) => {
        const t = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 30)
        const cls = typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : ''
        return `<${el.tagName.toLowerCase()}${cls}>${t ? ' ' + t : ''}`
      }
      const all = Array.from(document.body.querySelectorAll('*')).filter((el) => !el.closest('[data-zhiye-editor-ui]'))
      const overflow = []
      const clipped = []
      for (const el of all) {
        if (!visible(el)) continue
        const b = el.getBoundingClientRect()
        if (b.right > vw + 2 && b.width < vw * 3 && overflow.length < 6 && !all.some((p) => p !== el && p.contains(el) && overflow.includes(desc(p)))) {
          const sx = getComputedStyle(el.parentElement || el).overflowX
          if (sx !== 'auto' && sx !== 'scroll') overflow.push(desc(el))
        }
        const cs = getComputedStyle(el)
        if (clipped.length < 6 && el.children.length === 0 && (el.innerText || '').trim() && el.scrollWidth > el.clientWidth + 2 && (cs.overflow === 'hidden' || cs.overflowX === 'hidden') && cs.textOverflow !== 'ellipsis')
          clipped.push(desc(el))
      }
      const pick = (sel, n) => Array.from(document.querySelectorAll(sel)).filter(visible).slice(0, n).map((e) => (e.innerText || e.getAttribute('aria-label') || e.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 40)).filter(Boolean)
      // 宽屏（>=1600px）留白检测：后台/看板如果把侧边栏或主工作区锁在 max-w-[1440px] mx-auto，左右会各空出一大块
      let narrowCentered = null
      if (vw >= 1600) {
        const isAppShell = !!document.querySelector('aside, nav, table, [class*="sidebar"], [class*="dashboard"]')
        if (isAppShell) {
          const contentNodes = all.filter((el) => {
            if (!visible(el)) return false
            const tag = el.tagName
            if (tag === 'HTML' || tag === 'BODY') return false
            const b = el.getBoundingClientRect()
            // 忽略占满整屏的背景包裹层，只看实际承载内容的区块（文字、表格、侧边栏、卡片、按钮）
            const hasDirectContent =
              tag === 'ASIDE' || tag === 'TABLE' || tag === 'MAIN' || tag === 'HEADER' || tag === 'SECTION' ||
              tag === 'H1' || tag === 'H2' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'SVG'
            return hasDirectContent && b.width < vw - 16 && b.width >= 40 && b.top < window.innerHeight
          })
          if (contentNodes.length >= 2) {
            let minL = vw
            let maxR = 0
            for (const el of contentNodes) {
              const b = el.getBoundingClientRect()
              if (b.left < minL) minL = b.left
              if (b.right > maxR) maxR = b.right
            }
            const leftGap = Math.round(minL)
            const rightGap = Math.round(vw - maxR)
            const usedRatio = (maxR - minL) / vw
            if (leftGap >= 110 && rightGap >= 110 && usedRatio < 0.84) {
              narrowCentered = { leftGap, rightGap, usedPct: Math.round(usedRatio * 100) }
            }
          }
        }
      }
      return {
        title: document.title,
        scrollWidth: document.documentElement.scrollWidth, vw, height: document.documentElement.scrollHeight,
        hasViewport: !!document.querySelector('meta[name="viewport"]'),
        headings: pick('h1,h2,h3', 12), buttons: pick('button,a[role=button],[role=tab]', 20), inputs: pick('input,textarea,select', 12),
        text: (document.body.innerText || '').trim().replace(/\n{3,}/g, '\n\n').slice(0, 1500),
        overflow, clipped, narrowCentered,
      }
    }).catch((e) => { errors.push(`无法读取页面：${String(e.message).split('\n')[0]}`); return null })
    const shot = await page.screenshot({ type: 'jpeg', quality: 70, fullPage: false, ...(fullPage ? { clip: { x: 0, y: 0, width: dev.viewport.width, height: Math.min(info?.height || dev.viewport.height, 2400) }, fullPage: true } : {}) })
    await ctx.close().catch(() => {})
    const lines = [`截图（${dev.label}${r ? '，' + r : ''}）`]
    if (info) {
      lines.push(`标题：${info.title || '（无）'}；页面高度 ${info.height}px`)
      if (info.headings.length) lines.push(`标题文字：${info.headings.join(' / ')}`)
      if (info.buttons.length) lines.push(`按钮：${info.buttons.join(' / ')}`)
      if (info.inputs.length) lines.push(`输入框：${info.inputs.join(' / ')}`)
      const issues = []
      const actualWidth = Math.max(info.scrollWidth, info.hasViewport ? info.vw : 0)
      if (actualWidth > dev.viewport.width + 2 && info.scrollWidth > info.vw + 2 || (info.hasViewport && info.vw > dev.viewport.width + 2)) {
        issues.push(`页面横向溢出 ${Math.max(info.scrollWidth, info.vw) - dev.viewport.width}px（出现横向滚动条）`)
      }
      if (info.overflow.length) issues.push(`超出屏幕右侧的元素：${info.overflow.join('，')}`)
      if (info.clipped.length) issues.push(`文字被截断（没有省略号）：${info.clipped.join('，')}`)
      if (info.narrowCentered) {
        const { leftGap, rightGap, usedPct } = info.narrowCentered
        issues.push(`宽屏下内容区未铺满（仅占 ${usedPct}% 宽度，左侧留白 ${leftGap}px、右侧留白 ${rightGap}px），请去掉外层 max-w-* mx-auto 窄容器，改为 w-full 流式布局使侧边栏与主区贴边铺展`)
      }
      if (!info.text) issues.push('页面没有任何可见文字（可能是空白页）')
      lines.push(issues.length ? `⚠ 布局问题：\n- ${issues.join('\n- ')}` : '没有检测到横向溢出或文字截断。')
      lines.push(`可见文字（前 1500 字）：\n${info.text}`)
    }
    if (errors.length) lines.push(`⚠ 报错：\n- ${[...new Set(errors)].slice(0, 8).join('\n- ')}`)
    lines.push('（本地截图里没有织页云服务、数据源和 AI——页面显示对应的空态或提示是正常的。）')
    return { ok: true, text: lines.join('\n'), image: shot.toString('base64'), mimeType: 'image/jpeg' }
  } catch (e) {
    return { ok: false, text: `截图失败：${String(e.message).split('\n')[0]}` }
  } finally {
    if (browser) await browser.close().catch(() => {})
    await srv.close().catch(() => {})
    try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
  }
}
