import { writeFileSync } from 'node:fs'
import { chromium } from 'playwright'

const base = (process.env.ZHIYE_BASE_URL || 'https://zhiye.csot.tcl.com').replace(/\/$/, '')
const account = process.env.ZHIYE_TEST_ACCOUNT || 'yangkai37'
const password = process.env.ZHIYE_TEST_PASSWORD
if (!password) throw new Error('需要 ZHIYE_TEST_PASSWORD，且不会写入报告')
const out = process.env.ZHIYE_DEV_TEST_OUT || 'runtime/dev-load-test-20261125.json'
const browser = await chromium.launch({
  executablePath: process.env.ZHIYE_CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true,
})
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const report = { startedAt: new Date().toISOString(), base, account, steps: [], concurrency: null }
let token = ''
let cookie = ''

async function api(path, method = 'GET', body) {
  const started = performance.now()
  const res = await fetch(base + path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Cookie: cookie,
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = { raw: text.slice(0, 300) } }
  return { status: res.status, ok: res.ok, data, elapsedMs: Math.round(performance.now() - started) }
}

function unwrap(r) {
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${JSON.stringify(r.data).slice(0, 800)}`)
  return r.data
}

async function waitTask(id, timeoutMs = 20 * 60_000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const r = await api('/api/tasks/' + id)
    const t = unwrap(r)
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
    await new Promise((r) => setTimeout(r, 2000))
  }
  throw new Error('任务等待超时：' + id)
}

async function streamTask(id) {
  const started = Date.now()
  const res = await fetch(`${base}/api/tasks/${id}/events`, {
    headers: { Authorization: `Bearer ${token}`, Cookie: cookie, Accept: 'text/event-stream' },
    signal: AbortSignal.timeout(25 * 60_000),
  })
  if (!res.ok || !res.body) throw new Error('SSE HTTP ' + res.status)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const eventTypes = {}
  let events = 0
  let firstEventMs = null
  let lastSeq = 0
  let finished = false
  while (!finished) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    for (const part of buffer.split(/\r?\n\r?\n/)) {
      if (!part.trim()) continue
      const line = part.split(/\r?\n/).find((x) => x.startsWith('data:'))
      if (!line) continue
      let e
      try { e = JSON.parse(line.slice(5).trim()) } catch { continue }
      events++
      eventTypes[e.type] = (eventTypes[e.type] || 0) + 1
      if (firstEventMs === null) firstEventMs = Date.now() - started
      lastSeq = Math.max(lastSeq, Number(e.seq) || 0)
      if (e.type === 'finished') finished = true
    }
    buffer = buffer.slice(buffer.lastIndexOf('\n\n') + 2)
  }
  return { firstEventMs, events, lastSeq, eventTypes, streamFinished: finished }
}

async function tracked(id) {
  const stream = await streamTask(id)
  const task = await waitTask(id)
  return {
    taskId: id,
    type: task.type,
    status: task.status,
    error: task.error || null,
    durationMs: task.startedAt && task.finishedAt ? Date.parse(task.finishedAt) - Date.parse(task.startedAt) : null,
    sse: stream,
  }
}

async function removeApp(id) {
  if (!id) return
  const r = await api('/api/apps/' + id, 'DELETE')
  report.steps.push({ name: '清理测试应用', appId: id, http: r.status })
}

try {
  await page.goto(base + '/login', { waitUntil: 'domcontentloaded', timeout: 30_000 })
  await page.getByRole('link', { name: /使用华星统一账号登录/ }).click()
  await page.locator('#j_username').waitFor({ timeout: 30_000 })
  await page.locator('#j_username').fill(account)
  await page.locator('#j_password').fill(password)
  await page.locator('#normalLoginSubmit').click()
  await page.waitForURL((u) => u.origin === base && !u.pathname.startsWith('/login') && !u.pathname.startsWith('/access_token'), { timeout: 45_000 })
  await page.waitForFunction(() => !!localStorage.getItem('zhiye-platform-token'), { timeout: 15_000 })
  token = await page.evaluate(() => localStorage.getItem('zhiye-platform-token') || '')
  cookie = (await context.cookies(base)).map((c) => `${c.name}=${c.value}`).join('; ')
  if (!token || !cookie) throw new Error('登录后没有取得会话')
  const me = await api('/api/auth/me')
  report.login = { http: me.status, account: me.data?.user?.loginName || me.data?.loginName || account }
  console.log('登录成功，开始真实开发链路')

  const marker = '压测计数器-' + new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
  const created = await api('/api/apps', 'POST', {
    type: '日常开发', prompt: `做一个叫${marker}的极简计数器，只显示一个数字和一个加一按钮，界面使用中文`, mode: '快速', model: 'auto', assetIds: [], skillAppIds: [],
  })
  const app = unwrap(created)
  const appId = app.id
  report.appId = appId
  report.steps.push({ name: '创建应用与计划', http: created.status, result: await tracked(app.activeTask.id) })

  const generated = unwrap(await api('/api/apps/' + appId + '/generate', 'POST', {}))
  report.steps.push({ name: '真实代码生成', result: await tracked(generated.id) })

  const modified = unwrap(await api('/api/apps/' + appId + '/messages', 'POST', {
    content: '把加一按钮的文字改为“点我加一”，其他功能和样式保持不变', assetIds: [], skillAppIds: [],
  }))
  report.steps.push({ name: '对话修改', result: await tracked(modified.task.id) })

  const detail = unwrap(await api('/api/apps/' + appId))
  const published = await api('/api/apps/' + appId + '/publish', 'POST', {})
  const pub = unwrap(published)
  await page.goto(pub.siteUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 })
  await page.waitForTimeout(1500)
  const button = page.getByRole('button', { name: /点我加一/ })
  await button.click()
  const body = await page.locator('body').innerText()
  if (!/\b1\b/.test(body)) throw new Error('发布后计数器点击没有得到 1')
  report.steps.push({
    name: '发布与浏览器交互', version: detail.currentVersion?.number, siteUrl: pub.siteUrl,
    browser: { buttonClicked: true, counterObserved: true },
  })
  await removeApp(appId)

  const concurrentStarted = performance.now()
  const calls = await Promise.all(Array.from({ length: 5 }, (_, i) => api('/api/apps', 'POST', {
    type: '日常开发', prompt: `并发压测计划 ${i + 1}：做一个极简计数器`, mode: '快速', model: 'auto', assetIds: [], skillAppIds: [],
  })))
  report.concurrency = {
    name: '同一账号 5 路并发创建应用',
    elapsedMs: Math.round(performance.now() - concurrentStarted),
    responses: calls.map((r) => ({ http: r.status, code: r.data?.code || null, taskId: r.data?.activeTask?.id || null })),
  }
  const accepted = calls.filter((r) => r.ok)
  for (const r of accepted) {
    const appId = r.data.id
    if (r.data.activeTask?.id) {
      const result = await tracked(r.data.activeTask.id)
      report.concurrency.acceptedTask = result
    }
    await removeApp(appId)
  }
  report.concurrentAccepted = accepted.length
  report.concurrentRejected = calls.length - accepted.length
  console.log(JSON.stringify({ fullFlow: '通过', concurrentAccepted: report.concurrentAccepted, concurrentRejected: report.concurrentRejected }))
} catch (e) {
  report.failure = e?.message || String(e)
  console.error(report.failure)
  process.exitCode = 1
} finally {
  report.finishedAt = new Date().toISOString()
  writeFileSync(out, JSON.stringify(report, null, 2))
  await browser.close()
  console.log('REPORT=' + out)
}
