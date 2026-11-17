#!/usr/bin/env node
// 织页自动化基准评测脚本：
//   --check : 离线校验评测集格式、评分器逻辑，并用真实 app-template / vue-template 跑一遍构建+冒烟门禁；
//   --live  : 对运行中的本地/远程织页服务（默认 http://127.0.0.1:8080）执行端到端生成+评测。
import assert from 'node:assert/strict'
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verify } from '../runner/src/verify.mjs'
import { runLiveFlow } from '../runner/src/smoke.mjs'
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

  // 验证评分器能正确抓出违例用例（包括「仅靠 README 文档关键词绕过」的作弊场景）
  const badDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-eval-bad-'))
  try {
    writeFileSync(path.join(badDir, 'App.tsx'), 'import { BrowserRouter } from "react-router-dom";')
    const bad = scoreWorkspace(badDir, cases.find((c) => c.id === 'multipage-hash-router'))
    assert.equal(bad.passed, false)
  } finally {
    rmSync(badDir, { recursive: true, force: true })
  }

  const fakeReadmeDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-eval-readme-'))
  try {
    writeFileSync(path.join(fakeReadmeDir, 'README.md'), '支持按容量筛选并导出 CSV。')
    writeFileSync(path.join(fakeReadmeDir, 'App.tsx'), 'export default function App(){ return <main>空壳页面</main> }')
    const readmeOnly = scoreWorkspace(fakeReadmeDir, cases.find((c) => c.id === 'incremental-modify'))
    assert.equal(readmeOnly.passed, false, '仅靠 README 文档关键词不应通过增量修改评分')
  } finally {
    rmSync(fakeReadmeDir, { recursive: true, force: true })
  }

  console.log(JSON.stringify({ mode: 'offline-check', caseCount: cases.length, templateBaseline: summarizeResults(results) }, null, 2))
}

async function runLive() {
  const baseUrl = (process.env.ZHIYE_EVAL_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '')
  const limit = Number(process.env.ZHIYE_EVAL_LIMIT || cases.length)
  const onlyIds = process.env.ZHIYE_EVAL_IDS ? new Set(process.env.ZHIYE_EVAL_IDS.split(',').map((s) => s.trim())) : null
  const targetCases = (onlyIds ? cases.filter((c) => onlyIds.has(c.id)) : cases).slice(0, limit)
  const cookieJar = []
  const req = async (method, p, body, raw = false) => {
    const headers = { ...(cookieJar.length ? { Cookie: cookieJar.join('; ') } : {}) }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const res = await fetch(`${baseUrl}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const setCookie = res.headers.get('set-cookie')
    if (setCookie) {
      for (const part of setCookie.split(/,(?=\s*[^;,\s]+=)/)) cookieJar.push(part.split(';')[0].trim())
    }
    const text = await res.text()
    if (!res.ok) throw new Error(`${method} ${p} -> HTTP ${res.status}: ${text.slice(0, 200)}`)
    if (raw) return text
    return text ? JSON.parse(text) : null
  }
  const waitTask = async (id) => {
    const deadline = Date.now() + 360_000
    while (Date.now() < deadline) {
      const t = await req('GET', `/api/tasks/${id}`)
      if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(t.status)) return t
      await new Promise((r) => setTimeout(r, 1500))
    }
    throw new Error(`任务 ${id} 超时`)
  }

  const chrome = ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/chromium', process.env.ZHIYE_CHROME_PATH].find((p) => p && existsSync(p))
  const out = []
  for (const c of targetCases) {
    const started = Date.now()
    let appId = null
    let task = null
    let tokenUsed = 0
    let contract = { passed: false, errors: [] }
    let previewOk = null
    let liveFlow = null
    try {
      const created = await req('POST', '/api/apps', {
        type: c.type, mode: c.mode || '快速', prompt: c.prompt, model: 'auto', assetIds: [],
      })
      appId = created.id
      task = created.activeTask?.id ? await waitTask(created.activeTask.id) : null
      tokenUsed += Number(task?.tokenUsed || 0)
      if (task && task.type === 'plan' && task.status === 'SUCCEEDED') {
        if (c.cloud) await req('POST', `/api/apps/${appId}/cloud/enable`, {})
        const gen = await req('POST', `/api/apps/${appId}/generate`, {})
        task = await waitTask(gen.id)
        tokenUsed += Number(task?.tokenUsed || 0)
      }
      if (task?.status === 'SUCCEEDED' && c.followUpModify) {
        const mod = await req('POST', `/api/apps/${appId}/messages`, { content: c.followUpModify })
        task = await waitTask(mod.task.id)
        tokenUsed += Number(task?.tokenUsed || 0)
      }
      const app = await req('GET', `/api/apps/${appId}`)
      const vid = app?.currentVersion?.id
      if (task?.status === 'SUCCEEDED' && vid) {
        if (app.previewUrl) {
          try {
            const pr = await fetch(app.previewUrl)
            const html = await pr.text()
            previewOk = pr.ok && html.includes('<html')
            if (previewOk && chrome && (c.liveFlow || ['template', 'vue'].includes(c.expect?.projectKind))) {
              liveFlow = await runLiveFlow(app.previewUrl, c.liveFlow || { probeControls: true }, { chromePath: chrome })
            }
          } catch { previewOk = false }
        }
        const fileList = await req('GET', `/api/versions/${vid}/files`)
        const tmp = mkdtempSync(path.join(os.tmpdir(), `zhiye-live-${c.id}-`))
        try {
          for (const f of fileList || []) {
            if (!f.path || f.size > 512_000) continue
            const content = await req('GET', `/api/versions/${vid}/file?path=${encodeURIComponent(f.path)}`, undefined, true)
            const dst = path.join(tmp, f.path)
            mkdirSync(path.dirname(dst), { recursive: true })
            writeFileSync(dst, content)
          }
          contract = scoreWorkspace(tmp, c, liveFlow ? { checks: [], liveFlow } : null)
        } finally {
          rmSync(tmp, { recursive: true, force: true })
        }
      } else {
        contract = { passed: false, errors: [task?.error?.message || `任务状态 ${task?.status}`] }
      }
    } catch (e) {
      contract = { passed: false, errors: [e.message] }
    }
    const passed = task?.status === 'SUCCEEDED' && contract.passed && (previewOk !== false)
    out.push({
      id: c.id,
      name: c.name,
      appId,
      status: task?.status || 'ERROR',
      contractPassed: contract.passed,
      previewOk,
      liveFlowOk: liveFlow ? liveFlow.ok : null,
      passed,
      durationMs: Date.now() - started,
      tokenUsed,
      errors: contract.errors,
    })
  }
  const summary = summarizeResults(out)
  const report = {
    mode: 'live',
    at: new Date().toISOString(),
    baseUrl,
    avgDurationMs: out.length ? Math.round(out.reduce((s, r) => s + r.durationMs, 0) / out.length) : 0,
    totalTokens: out.reduce((s, r) => s + (r.tokenUsed || 0), 0),
    ...summary,
  }
  try {
    mkdirSync(path.join(root, 'runtime'), { recursive: true })
    writeFileSync(path.join(root, 'runtime', 'eval-report.json'), JSON.stringify(report, null, 2))
  } catch {}
  console.log(JSON.stringify(report, null, 2))
  if (summary.passed < summary.total) process.exitCode = 1
}

if (process.argv.includes('--live') || process.env.ZHIYE_EVAL_LIVE === '1') await runLive()
else await runOfflineCheck()
