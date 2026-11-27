// 交互式无头浏览器 E2E 自测引擎（browser_test）：
// 支持在 127.0.0.1 本地预览或临时全栈服务上执行真实点击、填表、下拉选择、路由切换、刷新持久化断言、控制台与接口报错监控及关键帧截图。
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { Type } from 'typebox'
import { startStaticServer } from './smoke.mjs'
import { DEVICES, SHOT_DIR, formatWideLayoutIssue, wideLayoutCheckInPage } from './screenshot.mjs'
import { buildEnv, prepareServiceFrontend, viteBuild } from './verify.mjs'
import { startTempServiceServer } from './endpoint-test.mjs'
import { log } from './util.mjs'

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
    return { port: addr.port, origin: `http://127.0.0.1:${addr.port}`, close: () => server.close().catch(() => {}) }
  } catch {
    return null
  }
}

async function resolveTarget(page, step) {
  const sel = step.selector ? String(step.selector).trim() : ''
  const txt = step.text ? String(step.text).trim() : ''
  if (sel) return page.locator(sel).first()
  if (txt) {
    // 依次尝试按可交互角色、label、placeholder 或可见文本定位
    const byBtn = page.getByRole('button', { name: txt }).first()
    if (await byBtn.count().catch(() => 0)) return byBtn
    const byTab = page.getByRole('tab', { name: txt }).first()
    if (await byTab.count().catch(() => 0)) return byTab
    const byLink = page.getByRole('link', { name: txt }).first()
    if (await byLink.count().catch(() => 0)) return byLink
    const byLabel = page.getByLabel(txt).first()
    if (await byLabel.count().catch(() => 0)) return byLabel
    const byPh = page.getByPlaceholder(txt).first()
    if (await byPh.count().catch(() => 0)) return byPh
    return page.getByText(txt, { exact: false }).first()
  }
  throw new Error('缺少 selector 或 text 定位参数')
}

export async function runBrowserTest(cwd, params = {}, { projectKind = 'template', env = process.env } = {}) {
  const chromePath = env.ZHIYE_CHROME_PATH
  if (!chromePath || !existsSync(chromePath)) {
    return { ok: false, text: 'browser_test 不可用：当前环境未配置 Chrome（ZHIYE_CHROME_PATH）。', stepsResults: [], consoleErrors: [], failedRequests: [] }
  }
  const steps = Array.isArray(params.steps) ? params.steps : []
  if (steps.length > 35) return { ok: false, text: '单次 browser_test 最多 35 个步骤。', stepsResults: [], consoleErrors: [], failedRequests: [] }

  let srv = null
  const isService = projectKind === 'service'
  const useVite = projectKind === 'template' || projectKind === 'vue' || projectKind === 'vite'
  try {
    if (isService) {
      // 自动识别根目录或 frontend/web/client 子目录并构建最新 dist（Java 项目自动同步到 static/）
      const prep = await prepareServiceFrontend(cwd, env)
      if (!prep.ok) {
        return { ok: false, text: `全栈前端构建失败，无法执行浏览器自测：\n${String(prep.detail).slice(0, 3000)}`, stepsResults: [], consoleErrors: [], failedRequests: [] }
      }
      srv = await startTempServiceServer(cwd, env, { healthPath: params.healthPath || '/api/health' })
    } else {
      if (useVite && existsSync(path.join(cwd, 'index.html'))) srv = await startViteDevServer(cwd)
      if (!srv) {
        let root = cwd
        if (useVite) {
          const b = await viteBuild(cwd, buildEnv(env, { ZHIYE_LOC_PLUGIN: '' }), { outDir: SHOT_DIR, minify: false })
          if (!b.ok) return { ok: false, text: `构建失败，无法执行浏览器自测：\n${String(b.detail).slice(0, 3000)}`, stepsResults: [], consoleErrors: [], failedRequests: [] }
          root = path.join(cwd, SHOT_DIR)
        }
        if (!existsSync(path.join(root, 'index.html'))) return { ok: false, text: '没有 index.html，无法执行浏览器自测。', stepsResults: [], consoleErrors: [], failedRequests: [] }
        const st = await startStaticServer(root)
        srv = { ...st, origin: `http://127.0.0.1:${st.port}` }
      }
    }
  } catch (e) {
    return { ok: false, text: `无法启动本地测试服务：${e.message}`, stepsResults: [], consoleErrors: [], failedRequests: [] }
  }

  const dev = DEVICES[params.device] || DEVICES.desktop
  const { chromium } = await import('playwright-core')
  const userDataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-btest-'))
  let browser = null
  const consoleErrors = []
  const failedRequests = []
  const stepsResults = []
  let shotImage = null

  try {
    browser = await chromium.launch({
      executablePath: chromePath,
      headless: true,
      timeout: 30000,
      args: [
        '--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--disable-extensions', '--disable-background-networking', '--disable-component-update',
        '--disable-sync', '--use-mock-keychain', '--password-store=basic', '--font-render-hinting=none',
      ],
      env: { ...process.env, HOME: process.env.HOME || userDataDir },
    })
    const origin = srv.origin
    const ctx = await browser.newContext({ viewport: dev.viewport, deviceScaleFactor: 1, locale: 'zh-CN' })
    const page = await ctx.newPage()
    await page.route('**/*', (r) => {
      const u = r.request().url()
      return u.startsWith(origin) || u.startsWith('data:') || u.startsWith('blob:') ? r.continue() : r.abort('blockedbyclient')
    })
    page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => {
      if (m.type() !== 'error') return
      const txt = m.text()
      if (/ERR_BLOCKED_BY_CLIENT|favicon/.test(txt)) return
      consoleErrors.push(`console.error: ${txt}`)
    })
    page.on('response', async (res) => {
      const u = res.url()
      if (!u.startsWith(origin) || res.status() < 400 || /\/favicon\.ico$/.test(u)) return
      let snippet = ''
      try { snippet = (await res.text()).slice(0, 180) } catch {}
      failedRequests.push(`${res.request().method()} ${u.replace(origin, '')} → HTTP ${res.status()}${snippet ? ` (${snippet})` : ''}`)
    })

    // 初始打开首页（或指定 route）
    const initRoute = String(params.route || '').trim()
    const startUrl = initRoute.startsWith('#')
      ? `${origin}/${isService ? '' : 'index.html'}${initRoute}`
      : initRoute.startsWith('/')
        ? `${origin}${initRoute}`
        : `${origin}/${isService ? '' : 'index.html'}`
    await page.goto(startUrl, { waitUntil: 'load', timeout: 25000 })
    await page.waitForTimeout(500)

    for (let i = 0; i < steps.length; i++) {
      const s = steps[i] || {}
      const action = String(s.action || '').toLowerCase()
      const timeout = Math.min(Math.max(Number(s.timeoutMs) || 6000, 500), 15000)
      const label = s.name || `${action}${s.selector ? ' ' + s.selector : s.text ? ' 「' + s.text + '」' : s.url ? ' ' + s.url : ''}`
      const t0 = Date.now()
      try {
        switch (action) {
          case 'goto': {
            const u = String(s.url || '/')
            if (/^https?:\/\//i.test(u)) throw new Error('禁止跳转外部域名，仅支持站内相对路径或 #/ 路由')
            const target = u.startsWith('#') ? `${origin}/${isService ? '' : 'index.html'}${u}` : `${origin}${u.startsWith('/') ? u : '/' + u}`
            await page.goto(target, { waitUntil: 'load', timeout })
            await page.waitForTimeout(300)
            break
          }
          case 'click': {
            const loc = await resolveTarget(page, s)
            await loc.click({ timeout })
            await page.waitForTimeout(250)
            break
          }
          case 'fill': {
            const loc = await resolveTarget(page, s)
            await loc.fill(String(s.value ?? ''), { timeout })
            await page.waitForTimeout(120)
            break
          }
          case 'select': {
            const loc = await resolveTarget(page, s)
            const tag = await loc.evaluate((el) => el.tagName.toLowerCase()).catch(() => '')
            if (tag === 'select') {
              await loc.selectOption(String(s.value ?? ''), { timeout })
            } else {
              await loc.click({ timeout })
              await page.waitForTimeout(150)
              await page.getByText(String(s.value ?? ''), { exact: false }).first().click({ timeout })
            }
            await page.waitForTimeout(200)
            break
          }
          case 'press': {
            if (s.selector || s.text) {
              const loc = await resolveTarget(page, s)
              await loc.press(String(s.key || 'Enter'), { timeout })
            } else {
              await page.keyboard.press(String(s.key || 'Enter'))
            }
            await page.waitForTimeout(200)
            break
          }
          case 'wait': {
            if (s.selector) await page.locator(String(s.selector)).first().waitFor({ state: 'visible', timeout })
            else if (s.text) await page.getByText(String(s.text), { exact: false }).first().waitFor({ state: 'visible', timeout })
            else await page.waitForTimeout(Math.min(Number(s.timeoutMs) || 600, 5000))
            break
          }
          case 'assert_text': {
            const want = String(s.expected ?? s.value ?? '')
            const scope = s.selector ? page.locator(String(s.selector)).first() : page.locator('body')
            const actual = (await scope.innerText({ timeout }).catch(() => '')) || ''
            if (!actual.includes(want)) throw new Error(`未找到预期文本「${want}」（当前摘要：${actual.replace(/\s+/g, ' ').slice(0, 140)}）`)
            break
          }
          case 'assert_not_text': {
            const forbid = String(s.expected ?? s.value ?? '')
            const scope = s.selector ? page.locator(String(s.selector)).first() : page.locator('body')
            const actual = (await scope.innerText({ timeout }).catch(() => '')) || ''
            if (actual.includes(forbid)) throw new Error(`页面仍包含不应出现的文本「${forbid}」`)
            break
          }
          case 'assert_visible': {
            const loc = await resolveTarget(page, s)
            await loc.waitFor({ state: 'visible', timeout })
            break
          }
          case 'assert_count': {
            if (!s.selector) throw new Error('assert_count 需要提供 selector')
            const cnt = await page.locator(String(s.selector)).count()
            if (s.expected !== undefined && cnt !== Number(s.expected)) throw new Error(`元素数量期望 ${s.expected}，实际 ${cnt}`)
            if (s.minCount !== undefined && cnt < Number(s.minCount)) throw new Error(`元素数量期望 >= ${s.minCount}，实际 ${cnt}`)
            break
          }
          case 'reload': {
            await page.reload({ waitUntil: 'load', timeout })
            await page.waitForTimeout(450)
            break
          }
          case 'screenshot': {
            const buf = await page.screenshot({ type: 'jpeg', quality: 80, fullPage: !!s.fullPage })
            shotImage = buf.toString('base64')
            break
          }
          default:
            throw new Error(`不支持的步骤动作：${action}`)
        }
        stepsResults.push({ index: i + 1, label, ok: true, durationMs: Date.now() - t0 })
      } catch (e) {
        stepsResults.push({ index: i + 1, label, ok: false, durationMs: Date.now() - t0, error: String(e.message || e).split('\n')[0] })
        // 失败时立即抓一张现场截图帮助 AI 诊断
        try { shotImage = (await page.screenshot({ type: 'jpeg', quality: 80 })).toString('base64') } catch {}
        break
      }
    }

    // 额外检查宽屏布局 & 横向溢出
    const layoutIssues = []
    try {
      const overflowPx = await page.evaluate(() => Math.max(0, (document.documentElement?.scrollWidth || 0) - window.innerWidth))
      if (overflowPx > 16) layoutIssues.push(`存在横向滚动溢出（超出视口 ${overflowPx}px）`)
      if (dev.viewport.width >= 1600) {
        const wideHit = await page.evaluate(wideLayoutCheckInPage)
        const wideMsg = formatWideLayoutIssue(wideHit)
        if (wideMsg) layoutIssues.push(wideMsg)
      }
    } catch {}

    if (!shotImage && params.captureFinal !== false) {
      try { shotImage = (await page.screenshot({ type: 'jpeg', quality: 78 })).toString('base64') } catch {}
    }

    const stepsOk = stepsResults.every((r) => r.ok)
    const ok = stepsOk && consoleErrors.length === 0 && failedRequests.length === 0 && layoutIssues.length === 0
    const lines = [
      `${ok ? '✓ 浏览器交互 E2E 自测通过' : '✗ 浏览器交互 E2E 自测发现问题'}（视口 ${dev.label}，步骤 ${stepsResults.filter((r) => r.ok).length}/${stepsResults.length}）`,
      ...stepsResults.map((r) => `${r.ok ? '✓' : '✗'} [${r.index}] ${r.label} (${r.durationMs}ms)${r.error ? ` — ${r.error}` : ''}`),
    ]
    if (consoleErrors.length) lines.push('', '控制台 / 脚本报错：', ...consoleErrors.slice(0, 10).map((e) => `- ${e}`))
    if (failedRequests.length) lines.push('', '失败的站内接口 / 资源请求：', ...failedRequests.slice(0, 10).map((e) => `- ${e}`))
    if (layoutIssues.length) lines.push('', '布局异常：', ...layoutIssues.map((e) => `- ${e}`))
    log(`browser_test ok=${ok} steps=${stepsResults.length} consoleErr=${consoleErrors.length} netErr=${failedRequests.length}`)
    return { ok, text: lines.join('\n'), stepsResults, consoleErrors, failedRequests, layoutIssues, image: shotImage, mimeType: shotImage ? 'image/jpeg' : undefined }
  } finally {
    if (browser) await browser.close().catch(() => {})
    if (srv) await srv.close().catch(() => {})
    try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
  }
}

export function createBrowserTestExtension({ cwd, projectKind, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'browser_test',
      label: '浏览器交互自测',
      description: '启动本地无头 Chrome 对当前应用执行真实端到端交互测试（支持点击、填表、下拉选择、路由跳转、断言文本/元素数量、刷新验证持久化、监控控制台错误和 /api 4xx/5xx 报错并自动截图）。支持前端与全栈后端项目。',
      parameters: Type.Object({
        route: Type.Optional(Type.String({ description: '初始打开路径，如 / 或 #/orders' })),
        device: Type.Optional(Type.Union([Type.Literal('desktop'), Type.Literal('wide')], { description: '视口：desktop(1280×800) 或 wide(1920×1080)' })),
        healthPath: Type.Optional(Type.String({ description: '全栈项目健康检查路径，默认 /api/health' })),
        captureFinal: Type.Optional(Type.Boolean({ description: '结束后是否附带截图，默认 true' })),
        steps: Type.Array(Type.Object({
          action: Type.Union([
            Type.Literal('goto'), Type.Literal('click'), Type.Literal('fill'), Type.Literal('select'),
            Type.Literal('press'), Type.Literal('wait'), Type.Literal('assert_text'), Type.Literal('assert_not_text'),
            Type.Literal('assert_visible'), Type.Literal('assert_count'), Type.Literal('reload'), Type.Literal('screenshot'),
          ]),
          name: Type.Optional(Type.String({ description: '步骤说明，如「提交新建工单」' })),
          selector: Type.Optional(Type.String({ description: 'CSS 选择器，如 button[type=submit] 或 input[name=title]' })),
          text: Type.Optional(Type.String({ description: '按按钮/页签/标签/placeholder/文本定位' })),
          value: Type.Optional(Type.String({ description: 'fill/select 的值' })),
          expected: Type.Optional(Type.Any({ description: 'assert_text/assert_not_text/assert_count 期望值' })),
          minCount: Type.Optional(Type.Integer({ description: 'assert_count 最小数量' })),
          url: Type.Optional(Type.String({ description: 'goto 的站内路径或 #/ 路由' })),
          key: Type.Optional(Type.String({ description: 'press 的按键名，如 Enter / Escape' })),
          timeoutMs: Type.Optional(Type.Integer({ description: '超时或等待毫秒数' })),
          fullPage: Type.Optional(Type.Boolean()),
        }), { minItems: 1, maxItems: 35 }),
      }),
      async execute(_id, params, _signal, _onUpdate, ctx) {
        const r = await runBrowserTest(cwd, params || {}, { projectKind, env })
        const content = [{ type: 'text', text: r.text }]
        const vision = Array.isArray(ctx?.model?.input) ? ctx.model.input.includes('image') : true
        if (r.image && vision) content.push({ type: 'image', data: r.image, mimeType: r.mimeType || 'image/jpeg' })
        return { content, details: { ok: r.ok, steps: r.stepsResults.length, consoleErrors: r.consoleErrors.length, failedRequests: r.failedRequests.length } }
      },
    })
  }
}
