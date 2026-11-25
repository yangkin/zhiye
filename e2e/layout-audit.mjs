// 页面布局巡检：新工作空间里导入一个示例应用，按 1440 / 1024 / 390 三种宽度逐页截图，
// 检查横向溢出、超出视口的元素、小于 11px 的文字、控制台错误和失败的请求。
// 用法：node e2e/layout-audit.mjs [输出目录]（需要先 dev-up；不动已有工作空间的数据）
import { chromium } from '@playwright/test'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const BASE = process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080'
const OUT = process.argv[2] || '/tmp/zhiye-audit'
const CHROME = process.env.ZHIYE_CHROME_PATH
  || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
const WIDTHS = [[1440, 900], [1024, 768], [390, 844]]
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ baseURL: BASE, locale: 'zh-CN' })
const req = ctx.request

async function json(method, url, data) {
  const r = await req.fetch(url, { method, data, headers: { 'Idempotency-Key': crypto.randomUUID() } })
  const t = await r.text()
  if (!r.ok()) throw new Error(`${method} ${url} → ${r.status()} ${t}`)
  return t ? JSON.parse(t) : null
}
async function waitTask(id) {
  for (let i = 0; i < 400; i++) {
    const t = await json('GET', `/api/tasks/${id}`)
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
    await new Promise((r) => setTimeout(r, 1500))
  }
  throw new Error('任务超时 ' + id)
}

// 准备数据：导入一个 HTML 应用（不调用模型）+ 一个空会话
await json('GET', '/api/meta')
const html = readFileSync(new URL('./fixtures/garden.html', import.meta.url))
const up = await req.post('/api/imports/upload', { multipart: { file: { name: 'garden.html', mimeType: 'text/html', buffer: html } } })
const upBody = await up.json()
const imp = await json('POST', '/api/imports', { uploadId: upBody.uploadId, name: '布局巡检·一个名字特别长的示例应用，用来看标题会不会把布局撑破' })
const done = await waitTask(imp.task.id)
if (done.status !== 'SUCCEEDED') throw new Error('导入失败 ' + JSON.stringify(done.error))
const appId = imp.app.id
const conv = await json('POST', '/api/conversations', {})

const pages = [
  ['create', '/create'],
  ['apps', '/apps'],
  ['history', '/history'],
  ['community', '/community'],
  ['chat', `/chat/${conv.id}`],
  ['workbench', `/apps/${appId}`],
  ['recover', '/recover'],
  ['wb-code', `/apps/${appId}`, '代码'],
  ['wb-files', `/apps/${appId}`, '文件'],
  ['wb-cloud', `/apps/${appId}`, '云服务'],
]

const report = []
for (const [w, h] of WIDTHS) {
  const page = await ctx.newPage()
  await page.setViewportSize({ width: w, height: h })
  const errors = []
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 404/.test(m.text())) errors.push('console: ' + m.text().slice(0, 300)) })
  page.on('pageerror', (e) => errors.push('pageerror: ' + String(e).slice(0, 300)))
  // 没有草稿时 GET /draft 是约定的 404，不算问题
  const expected = (r) => r.url().includes('/favicon') || (r.status() === 404 && /\/api\/apps\/[^/]+\/draft$/.test(r.url()))
  page.on('console', () => {})
  page.on('response', (r) => { if (r.status() >= 400 && !expected(r)) errors.push(`http ${r.status()} ${r.request().method()} ${r.url().replace(BASE, '')}`) })
  for (const [name, url, tab] of pages) {
    errors.length = 0
    await page.goto(url, { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
    if (tab) {
      const t = page.getByRole('tab', { name: tab, exact: true }).first()
      if (await t.isVisible().catch(() => false)) await t.click()
      else errors.push('找不到页签 ' + tab)
      await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {})
    }
    await page.waitForTimeout(800)
    const shot = path.join(OUT, `${name}-${w}.png`)
    await page.screenshot({ path: shot, fullPage: false })
    const issues = await page.evaluate(() => {
      const vw = window.innerWidth
      const out = { scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight, vh: window.innerHeight, vw, offscreen: [], tiny: [] }
      const desc = (el) => {
        const c = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : ''
        return el.tagName.toLowerCase() + (c ? '.' + c : '') + (el.textContent ? ` 「${el.textContent.trim().slice(0, 20)}」` : '')
      }
      const skip = (el) => el.closest('.mini, [class*="mini-"], .thumbnail, .scene-thumb, [aria-hidden="true"], iframe, .sr-only, .visually-hidden')
      for (const el of document.querySelectorAll('body *')) {
        const r = el.getBoundingClientRect()
        if (!r.width || !r.height) continue
        const cs = getComputedStyle(el)
        if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) continue
        // 只看真的被看见的溢出：祖先没有裁剪（overflow hidden/auto）
        if (r.right > vw + 1 && out.offscreen.length < 12) {
          let clipped = false
          for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            const o = getComputedStyle(p); if (/(hidden|auto|scroll|clip)/.test(o.overflowX)) { clipped = true; break }
          }
          if (!clipped && cs.position !== 'fixed') out.offscreen.push(`${desc(el)} right=${Math.round(r.right)}`)
        }
        const direct = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
        // font-size:0 是窄屏只留图标的按钮（文字仍给读屏），不算
        if (direct && parseFloat(cs.fontSize) > 0 && parseFloat(cs.fontSize) < 11 && !skip(el) && out.tiny.length < 12) out.tiny.push(`${desc(el)} ${cs.fontSize}`)
      }
      return out
    })
    // 会话页是一屏布局（输入框贴底），整页不应该能上下滚
    const row = { page: name, width: w, horizontalOverflow: issues.scrollWidth > issues.vw + 1 ? issues.scrollWidth - issues.vw : 0,
      verticalOverflow: name === 'chat' && issues.scrollHeight > issues.vh + 1 ? issues.scrollHeight - issues.vh : 0,
      offscreen: issues.offscreen, tinyText: issues.tiny, errors: [...new Set(errors)] }
    report.push(row)
    const flag = row.horizontalOverflow || row.verticalOverflow || row.offscreen.length || row.tinyText.length || row.errors.length
    console.log(`${flag ? '✗' : '✓'} ${name} @${w}` + (flag ? ' ' + JSON.stringify({ o: row.horizontalOverflow, v: row.verticalOverflow, off: row.offscreen, tiny: row.tinyText, err: row.errors }) : ''))
  }
  await page.close()
}
writeFileSync(path.join(OUT, 'report.json'), JSON.stringify({ appId, conversationId: conv.id, report }, null, 2))
await browser.close()
console.log('截图和报告：' + OUT)
