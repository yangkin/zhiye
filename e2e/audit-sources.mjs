import { writeFileSync } from 'node:fs'
import { chromium } from 'playwright'

const base = (process.env.ZHIYE_BASE_URL || 'https://zhiye.csot.tcl.com').replace(/\/$/, '')
const account = process.env.ZHIYE_TEST_ACCOUNT || 'yangkai37'
const password = process.env.ZHIYE_TEST_PASSWORD
if (!password) throw new Error('需要 ZHIYE_TEST_PASSWORD')
const browser = await chromium.launch({ executablePath: process.env.ZHIYE_CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const context = await browser.newContext()
const page = await context.newPage()
let token = ''
let cookie = ''

async function api(path, method = 'GET', body) {
  const res = await fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, Cookie: cookie, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = { raw: text.slice(0, 300) } }
  return { http: res.status, ok: res.ok, data }
}

try {
  await page.goto(base + '/login', { waitUntil: 'domcontentloaded' })
  await page.getByRole('link', { name: /使用华星统一账号登录/ }).click()
  await page.locator('#j_username').fill(account)
  await page.locator('#j_password').fill(password)
  await page.locator('#normalLoginSubmit').click()
  await page.waitForURL((u) => u.origin === base && !u.pathname.startsWith('/login') && !u.pathname.startsWith('/access_token'), { timeout: 45_000 })
  token = await page.evaluate(() => localStorage.getItem('zhiye-platform-token') || '')
  cookie = (await context.cookies(base)).map((x) => `${x.name}=${x.value}`).join('; ')

  const sources = (await api('/api/sources')).data || []
  const callable = await api('/api/agw/callable-apis')
  const report = { at: new Date().toISOString(), account, callableCount: Array.isArray(callable.data) ? callable.data.length : 0, sources: [] }
  for (const s of sources) {
    const params = s.params || []
    const required = params.filter((p) => p.required)
    const sensitiveExamples = params.filter((p) => /secret|password|credential|token|app[-_]?key|authorization/i.test(p.name) && p.example)
    const item = {
      id: s.id,
      key: s.key,
      name: s.name,
      method: s.method,
      paramCount: params.length,
      requiredParams: required.map((p) => p.name),
      positions: [...new Set(params.map((p) => p.position || (s.method === 'POST' ? 'body' : 'query')))],
      hasResponseExample: !!s.sample,
      sensitiveExampleFields: sensitiveExamples.map((p) => p.name),
      classification: params.length === 0 ? '没有参数定义' : params.every((p) => p.name === '_body') ? '只有自由 JSON 请求体' : '有结构化参数',
    }
    if (required.length) {
      const r = await api(`/api/sources/${s.id}/test`, 'POST', {})
      item.emptyValidation = { http: r.http, code: r.data?.code || null, message: r.data?.message || null }
    } else if (s.key === 'agw_feishu_authorize_741ebe8d') {
      const r = await api(`/api/sources/${s.id}/test`, 'POST', {})
      item.realReadOnlyCall = { http: r.http, ok: r.ok, code: r.data?.code || null }
    } else {
      item.realReadOnlyCall = { skipped: true, reason: '无参数或写入/凭证类接口，不为审计主动调用上游' }
    }
    report.sources.push(item)
  }
  report.summary = {
    total: sources.length,
    noParams: report.sources.filter((x) => x.classification === '没有参数定义').length,
    freeBodyOnly: report.sources.filter((x) => x.classification === '只有自由 JSON 请求体').length,
    structured: report.sources.filter((x) => x.classification === '有结构化参数').length,
    realReadOnlySucceeded: report.sources.filter((x) => x.realReadOnlyCall?.ok).length,
    validationRejectedAsExpected: report.sources.filter((x) => x.emptyValidation?.http === 400).length,
  }
  writeFileSync(process.env.ZHIYE_SOURCE_AUDIT_OUT || 'runtime/source-audit-20261125.json', JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report.summary, null, 2))
  for (const s of report.sources) console.log(JSON.stringify(s))
} finally {
  await browser.close()
}
