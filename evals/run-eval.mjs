#!/usr/bin/env node
// 织页自动化基准评测脚本：
//   --check : 离线校验评测集格式、评分器逻辑，并用真实 app-template / vue-template 跑一遍构建+冒烟门禁；
//   --live  : 对运行中的本地/远程织页服务（默认 http://127.0.0.1:8080）执行端到端生成+评测。
import assert from 'node:assert/strict'
import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verify } from '../runner/src/verify.mjs'
import { scoreWorkspace, summarizeResults } from './score.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const cases = JSON.parse(readFileSync(path.join(here, 'cases.json'), 'utf8'))

async function runOfflineCheck() {
  assert.ok(Array.isArray(cases) && cases.length >= 10, 'cases.json 至少包含 10 条基准案例')
  const ids = new Set()
  for (const c of cases) {
    assert.ok(c.id && !ids.has(c.id), `重复或缺少 case id: ${c.id}`)
    ids.add(c.id)
    assert.ok(c.prompt && c.expect?.projectKind, `case ${c.id} 缺少 prompt 或 expect.projectKind`)
  }

  // 验证官方 React / Vue 模板自身就能通过最新 verify + smokeTest（含 390px 与沙箱门禁）
  const chrome = ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/chromium', process.env.ZHIYE_CHROME_PATH].find((p) => p && existsSync(p))
  const checks = [
    { kind: 'template', tpl: 'packages/app-template', cache: 'runtime/template-cache/node_modules' },
    { kind: 'vue', tpl: 'packages/vue-template', cache: 'runtime/vue-template-cache/node_modules' },
  ]
  const results = []
  for (const item of checks) {
    const nm = path.join(root, item.cache)
    if (!existsSync(nm)) {
      console.log(`[eval:check] 跳过 ${item.kind}（未发现 ${item.cache}）`)
      continue
    }
    const dir = mkdtempSync(path.join(os.tmpdir(), `zhiye-eval-${item.kind}-`))
    try {
      cpSync(path.join(root, item.tpl), dir, { recursive: true })
      symlinkSync(nm, path.join(dir, 'node_modules'))
      const res = await verify(dir, { projectKind: item.kind }, { ...process.env, ...(chrome ? { ZHIYE_CHROME_PATH: chrome } : {}) })
      const scored = scoreWorkspace(dir, { id: `tpl-${item.kind}`, name: `基线模板 ${item.kind}`, expect: { projectKind: item.kind, minFiles: 2 } }, res)
      assert.equal(scored.passed, true, `基线模板 ${item.kind} 未通过校验：${scored.errors.join('; ')}`)
      results.push(scored)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  }

  // 验证评分器能正确抓出违例用例
  const badDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-eval-bad-'))
  try {
    writeFileSync(path.join(badDir, 'App.tsx'), 'import { BrowserRouter } from "react-router-dom";')
    const bad = scoreWorkspace(badDir, cases.find((c) => c.id === 'multipage-hash-router'))
    assert.equal(bad.passed, false)
  } finally {
    rmSync(badDir, { recursive: true, force: true })
  }

  console.log(JSON.stringify({ mode: 'offline-check', caseCount: cases.length, templateBaseline: summarizeResults(results) }, null, 2))
}

async function runLive() {
  const baseUrl = (process.env.ZHIYE_EVAL_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '')
  const limit = Number(process.env.ZHIYE_EVAL_LIMIT || cases.length)
  const targetCases = cases.slice(0, limit)
  const cookieJar = []
  const req = async (method, p, body) => {
    const headers = { ...(cookieJar.length ? { Cookie: cookieJar.join('; ') } : {}) }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const res = await fetch(`${baseUrl}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const setCookie = res.headers.get('set-cookie')
    if (setCookie) cookieJar.push(setCookie.split(';')[0])
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch {}
    if (!res.ok) throw new Error(`${method} ${p} -> HTTP ${res.status}: ${text.slice(0, 200)}`)
    return json
  }
  const waitTask = async (id) => {
    const deadline = Date.now() + 300_000
    while (Date.now() < deadline) {
      const t = await req('GET', `/api/tasks/${id}`)
      if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
      await new Promise((r) => setTimeout(r, 1500))
    }
    throw new Error(`任务 ${id} 超时`)
  }

  const out = []
  for (const c of targetCases) {
    const started = Date.now()
    const created = await req('POST', '/api/apps', {
      type: c.type, mode: c.mode || '快速', prompt: c.prompt, model: 'auto', assetIds: [],
    })
    const appId = created.id
    let task = created.activeTask?.id ? await waitTask(created.activeTask.id) : null
    if (task && task.type === 'plan' && task.status === 'SUCCEEDED') {
      if (c.cloud) await req('POST', `/api/apps/${appId}/cloud/enable`, {})
      const gen = await req('POST', `/api/apps/${appId}/generate`, {})
      task = await waitTask(gen.id)
    }
    if (task?.status === 'SUCCEEDED' && c.followUpModify) {
      const mod = await req('POST', `/api/apps/${appId}/messages`, { content: c.followUpModify })
      task = await waitTask(mod.task.id)
    }
    out.push({
      id: c.id,
      name: c.name,
      status: task?.status || 'UNKNOWN',
      passed: task?.status === 'SUCCEEDED',
      durationMs: Date.now() - started,
      tokenUsed: task?.tokenUsed || 0,
      error: task?.error?.message || null,
    })
  }
  const summary = summarizeResults(out)
  console.log(JSON.stringify({ mode: 'live', baseUrl, ...summary }, null, 2))
  if (summary.passed < summary.total) process.exitCode = 1
}

if (process.argv.includes('--live') || process.env.ZHIYE_EVAL_LIVE === '1') await runLive()
else await runOfflineCheck()
