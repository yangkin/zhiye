#!/usr/bin/env node
// 织页 runner 真实集成测试（需要真实 Kimi；在工具沙箱外运行）。
//
//   node runner/test/integration.mjs [--sandbox] [--skip-abort] [--keep]
//
// 密钥：环境变量 KIMI_API_KEY，或文件 $ZHIYE_KIMI_ENV（默认 /tmp/claude-501/zhiye-secrets/kimi.env）里的 KIMI_API_KEY=...
// 测试进程在 127.0.0.1 上起一个迷你「模型网关」：runner 只拿到一次性假令牌，网关换成真实密钥转发到上游
// （与 Java 网关的职责一致），因此 --sandbox（ALLOW_NET=0，只允许连 localhost）下也能跑。
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, cpSync, symlinkSync, readFileSync, writeFileSync, realpathSync, rmSync, statSync, createWriteStream } from 'node:fs'
import { createInterface } from 'node:readline'
import http from 'node:http'
import https from 'node:https'
import path from 'node:path'
import os from 'node:os'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { startStaticServer } from '../src/smoke.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const ZHIYE = path.resolve(here, '../..')
const MAIN = path.join(ZHIYE, 'runner/src/main.mjs')
const SB = path.join(ZHIYE, 'deploy/sandbox/runner.sb')
const CHROME = process.env.ZHIYE_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const UPSTREAM = process.env.ZHIYE_UPSTREAM_URL || 'https://api.kimi.com/coding/v1'
const args = new Set(process.argv.slice(2))
const SANDBOX = args.has('--sandbox')
const NODE_DIR = path.resolve(path.dirname(process.execPath), '..')

const results = []
const t0 = Date.now()
function record(name, ok, detail = '', ms) { results.push({ name, ok, detail, ms }); console.log(`${ok ? '✔' : '✘'} ${name}${ms !== undefined ? ` (${(ms / 1000).toFixed(1)}s)` : ''}${detail ? ' — ' + detail : ''}`) }
const say = (...a) => console.log('  ·', ...a)

function loadKey() {
  if (process.env.KIMI_API_KEY) return process.env.KIMI_API_KEY.trim()
  const f = process.env.ZHIYE_KIMI_ENV || '/tmp/claude-501/zhiye-secrets/kimi.env'
  const m = /^KIMI_API_KEY=(.+)$/m.exec(readFileSync(f, 'utf8'))
  if (!m) throw new Error(`在 ${f} 里找不到 KIMI_API_KEY`)
  return m[1].trim().replace(/^["']|["']$/g, '')
}

// ---------------- 迷你网关 ----------------
function startGateway(realKey, taskToken) {
  const up = new URL(UPSTREAM)
  let requests = 0
  const server = http.createServer((req, res) => {
    const auth = req.headers.authorization || ''
    if (auth !== `Bearer ${taskToken}`) { res.writeHead(401, { 'content-type': 'application/json' }).end(JSON.stringify({ error: { message: 'invalid task token' } })); return }
    requests++
    const sub = req.url.replace(/^\/llm\/v1/, '')
    const headers = { ...req.headers, host: up.host, authorization: `Bearer ${realKey}` }
    delete headers['content-length']
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const body = Buffer.concat(chunks)
      headers['content-length'] = String(body.length)
      const preq = https.request({ hostname: up.hostname, port: up.port || 443, path: up.pathname.replace(/\/$/, '') + sub, method: req.method, headers }, (pres) => {
        res.writeHead(pres.statusCode, pres.headers)
        pres.pipe(res)
      })
      preq.on('error', (e) => { if (!res.headersSent) res.writeHead(502); res.end(JSON.stringify({ error: { message: `upstream: ${e.message}` } })) })
      preq.end(body)
    })
  })
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ url: `http://127.0.0.1:${server.address().port}/llm/v1`, close: () => server.close(), get requests() { return requests } })))
}

/** 脚本化的 OpenAI 兼容网关：第一次请求返回给定的工具调用，之后返回一句总结。 */
function startScriptedGateway(taskToken, calls) {
  const toolResults = []
  const server = http.createServer((req, res) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      if ((req.headers.authorization || '') !== `Bearer ${taskToken}`) { res.writeHead(401).end('{}'); return }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
      const msgs = body.messages || []
      const tools = msgs.filter((m) => m.role === 'tool')
      for (const t of tools) toolResults.push(typeof t.content === 'string' ? t.content : JSON.stringify(t.content))
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      const send = (o) => res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 0, model: body.model, ...o })}\n\n`)
      if (!tools.length) {
        send({ choices: [{ index: 0, delta: { role: 'assistant', tool_calls: calls.map(([name, a], i) => ({ index: i, id: `call_${i}`, type: 'function', function: { name, arguments: JSON.stringify(a) } })) }, finish_reason: null }] })
        send({ choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }], usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } })
      } else {
        send({ choices: [{ index: 0, delta: { role: 'assistant', content: '已完成。' }, finish_reason: null }] })
        send({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 2, total_tokens: 12 } })
      }
      res.end('data: [DONE]\n\n')
    })
  })
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ url: `http://127.0.0.1:${server.address().port}/llm/v1`, toolResults, close: () => server.close() })))
}

// ---------------- 工作区 ----------------
function makeWorkspace(kind = 'template') {
  const base = realpathSync(mkdtempSync(path.join(os.tmpdir(), 'zhiye-it-')))
  const ws = path.join(base, 'workspace')
  const agentDir = path.join(base, 'agent')
  cpSync(path.join(ZHIYE, kind === 'skill' ? 'packages/skill-template' : 'packages/app-template'), ws, { recursive: true })
  const git = (...a) => execFileSync('git', ['-c', 'user.name=zhiye', '-c', 'user.email=zhiye@example.com', ...a], { cwd: ws, stdio: 'pipe' }).toString()
  git('init', '-q')
  git('add', '-A')
  git('commit', '-qm', '基线')
  if (kind !== 'skill') symlinkSync(path.join(ZHIYE, 'runtime/template-cache/node_modules'), path.join(ws, 'node_modules'))
  return { base, ws, agentDir, git }
}

// ---------------- runner 客户端（HTTP 控制通道 + SSE） ----------------
// 命令走短 POST，回复/事件/日志都从一条 SSE 流回来；按 id 把回复交给等待者。
class Runner {
  constructor({ ws, agentDir, gatewayUrl, token, label, logDir }) {
    this.label = label
    // runner 自己的控制通道令牌（与模型网关的 ZHIYE_TASK_TOKEN 不同）。
    this.runnerToken = 'rt_' + randomBytes(16).toString('hex')
    const env = {
      PATH: `${path.dirname(process.execPath)}:/usr/bin:/bin`,
      HOME: agentDir,
      TMPDIR: realpathSync(os.tmpdir()) + '/',
      ZHIYE_GATEWAY_URL: gatewayUrl,
      ZHIYE_TASK_TOKEN: token,
      ZHIYE_RUNNER_TOKEN: this.runnerToken,
      ZHIYE_RUNNER_HOST: '127.0.0.1',
      ZHIYE_RUNNER_PORT: '0',
      ZHIYE_LOC_PLUGIN: path.join(ZHIYE, 'packages/vite-plugin-loc/index.mjs'),
      ZHIYE_BRIDGE_PATH: path.join(ZHIYE, 'packages/editor-bridge/bridge.js'),
      ZHIYE_CHROME_PATH: CHROME,
      ZHIYE_SMOKE_REQUIRED: '1',
      LANG: 'en_US.UTF-8',
    }
    const cmd = SANDBOX ? '/usr/bin/sandbox-exec' : process.execPath
    const argv = SANDBOX
      ? ['-f', SB, '-D', `WORKSPACE=${ws}`, '-D', `AGENT_DIR=${agentDir}`, '-D', `ZHIYE_ROOT=${realpathSync(ZHIYE)}`, '-D', `NODE_DIR=${realpathSync(NODE_DIR)}`, '-D', 'ALLOW_NET=0', process.execPath, MAIN]
      : [MAIN]
    this.proc = spawn(cmd, argv, { cwd: ws, env, stdio: ['ignore', 'pipe', 'pipe'] })
    this.pending = new Map()
    this.events = []
    this.frames = []
    this.listeners = []
    this.stderr = ''
    this.badLines = []
    this.seq = 0
    this.lastSeq = 0
    this.port = null
    this.baseUrl = null
    this.closed = false
    this.sseAbort = null
    this.exit = new Promise((r) => this.proc.on('exit', (code, sig) => r({ code, sig })))
    this.ready = new Promise((resolve, reject) => { this.resolveReady = resolve; this.rejectReady = reject })
    this.ready.catch(() => {})
    const logFile = createWriteStream(path.join(logDir || path.dirname(ws), `runner-${label}.log`), { flags: 'a' })
    let stderrBuf = ''
    this.proc.stderr.on('data', (d) => {
      this.stderr += d
      logFile.write(d)
      if (process.env.VERBOSE) process.stderr.write(d)
      stderrBuf += d
      const m = /ZHIYE_RUNNER_READY port=(\d+)/.exec(stderrBuf)
      if (m && this.port === null) {
        this.port = Number(m[1])
        this.baseUrl = `http://127.0.0.1:${this.port}`
        this.connect().then(() => this.resolveReady(), (e) => this.rejectReady(e))
      }
    })
    this.proc.on('exit', (code, sig) => {
      if (this.port === null) this.rejectReady(new Error(`runner 在就绪前退出：code=${code} sig=${sig}\n${this.stderr}`))
    })
    // 协议已移到 HTTP/SSE：stdout 应始终为空，出现内容就算异常。
    createInterface({ input: this.proc.stdout }).on('line', (line) => { if (line.trim()) this.badLines.push(`stdout: ${line}`) })
  }

  // ---------- SSE 长连接 ----------
  async connect() {
    this.sseAbort = new AbortController()
    const res = await fetch(`${this.baseUrl}/v1/events?after=0`, {
      headers: { authorization: `Bearer ${this.runnerToken}` },
      signal: this.sseAbort.signal,
    })
    if (!res.ok || !res.body) throw new Error(`SSE 连接失败：HTTP ${res.status} ${await res.text().catch(() => '')}`)
    this.readSse(res.body).catch((e) => { if (!this.closed) this.badLines.push(`SSE 读取失败：${e.message}`) })
  }

  async readSse(stream) {
    const reader = stream.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let i
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, i)
        buf = buf.slice(i + 2)
        this.handleFrame(raw)
      }
    }
  }

  handleFrame(raw) {
    let seq = null
    const data = []
    for (const line of raw.split('\n')) {
      if (line.startsWith(':')) continue // connected / keepalive 注释
      if (line.startsWith('id:')) seq = Number(line.slice(3).trim())
      else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) return
    let msg
    try { msg = JSON.parse(data.join('\n')) } catch { this.badLines.push(`SSE 帧不是 JSON：${data.join('\n')}`); return }
    if (seq === null && Number.isInteger(msg.seq)) seq = msg.seq
    if (Number.isInteger(msg.seq) && msg.seq > this.lastSeq) this.lastSeq = msg.seq
    this.frames.push({ seq, message: msg })
    if (msg.type === 'reply') {
      const p = this.pending.get(msg.id)
      if (p) { this.pending.delete(msg.id); p(msg) } else this.badLines.push(`未匹配的回复：${data.join('\n')}`)
    } else if (msg.type === 'event') {
      this.events.push(msg.event)
      for (const l of this.listeners) l(msg.event)
      this.sayEvent(msg.event)
    } else if (msg.type === 'log') {
      // 与旧协议保持一致：日志也进事件数组。
      const ev = { type: 'log', line: msg.line }
      this.events.push(ev)
      for (const l of this.listeners) l(ev)
    } else {
      this.badLines.push(`未知帧类型：${data.join('\n')}`)
    }
  }

  sayEvent(e) {
    if (e.type === 'tool' && e.state === 'start') say(`[${this.label}] ${e.summary}`)
    if (e.type === 'tool' && e.state === 'end' && !e.ok) say(`[${this.label}]   ↳ 失败`)
  }

  // ---------- 命令 ----------
  async send(cmd, payload = {}) {
    await this.ready
    const id = `c${++this.seq}`
    const reply = new Promise((resolve) => this.pending.set(id, resolve))
    let res
    try {
      res = await fetch(`${this.baseUrl}/v1/commands`, {
        method: 'POST',
        headers: { authorization: `Bearer ${this.runnerToken}`, 'content-type': 'application/json' },
        body: JSON.stringify({ id, cmd, ...payload }),
      })
    } catch (e) {
      this.pending.delete(id)
      throw e
    }
    if (res.status !== 202) {
      const body = await res.text().catch(() => '')
      this.pending.delete(id)
      return { type: 'reply', id, ok: false, error: { code: `HTTP_${res.status}`, message: body } }
    }
    return reply
  }

  /** 发送原始请求体（畸形输入测试），返回 HTTP 状态和响应体。 */
  async raw(body) {
    await this.ready
    const res = await fetch(`${this.baseUrl}/v1/commands`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.runnerToken}`, 'content-type': 'application/json' },
      body,
    })
    return { status: res.status, body: await res.text().catch(() => '') }
  }

  on(fn) { this.listeners.push(fn) }

  waitFor(pred, timeoutMs = 30000) {
    const match = typeof pred === 'string' ? (e) => e.type === pred : pred
    const found = this.events.find(match)
    if (found) return Promise.resolve(found)
    return new Promise((resolve, reject) => {
      const on = (e) => {
        if (!match(e)) return
        clearTimeout(timer)
        this.listeners = this.listeners.filter((l) => l !== on)
        resolve(e)
      }
      const timer = setTimeout(() => {
        this.listeners = this.listeners.filter((l) => l !== on)
        reject(new Error(`等待事件超时：${String(pred)}`))
      }, timeoutMs)
      this.listeners.push(on)
    })
  }

  async shutdown() {
    let r
    try {
      r = await this.send('shutdown')
    } catch (e) {
      this.closeSse()
      throw e
    }
    let timer
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('runner 没有在 15s 内退出')), 15000)
      timer.unref?.()
    })
    let e
    try {
      e = await Promise.race([this.exit, timeout])
    } finally {
      clearTimeout(timer)
      this.closed = true
      this.closeSse()
    }
    return { r, e }
  }

  /** 兜底停止（测试失败时调用），行为等价于 close。 */
  async stop() {
    this.closed = true
    this.closeSse()
    if (this.proc.exitCode === null && this.proc.signalCode === null) {
      try { this.proc.kill('SIGTERM') } catch {}
    }
    return this.exit
  }

  close() { return this.stop() }

  closeSse() {
    try { this.sseAbort?.abort() } catch {}
  }
}

async function step(name, fn) {
  const t = Date.now()
  try {
    const detail = await fn()
    record(name, true, detail || '', Date.now() - t)
    return true
  } catch (e) {
    record(name, false, e.message, Date.now() - t)
    return false
  }
}
const assert = (c, m) => { if (!c) throw new Error(m) }

function verifySummary(r) {
  return r.result.checks.map((c) => `${c.name}${c.ok ? '✓' : '✗'}${c.durationMs ? ' ' + (c.durationMs / 1000).toFixed(1) + 's' : ''}`).join(' ')
}
function failureText(r) {
  return r.result.checks.filter((c) => !c.ok && !c.skipped).map((c) => `【${c.name}】\n${c.detail}`).join('\n\n')
}

/** 用 Chrome 打开构建产物，执行回调 */
async function withPage(dir, fn) {
  const { chromium } = await import('playwright-core')
  const srv = await startStaticServer(dir)
  const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] })
  try {
    const page = await browser.newPage()
    await page.goto(`http://127.0.0.1:${srv.port}/index.html`)
    await page.waitForTimeout(500)
    return await fn(page)
  } finally { await browser.close(); await srv.close() }
}

const INIT = (agentDir, sessionFile = null) => ({ agentDir, sessionFile, model: { provider: 'kimi-coding', id: 'kimi-for-coding' }, thinking: 'low', projectKind: 'template', maxTurns: 60 })
const PROMPT = '做一个点击计数器：显示当前次数，有“加一”和“重置”按钮，重置在次数为 0 时禁用'

const SKILL_PROMPT = '做一个周报生成 skill：根据本周工作记录生成四段式周报，附模板和一个统计字数的 Python 脚本'

async function mainSkill() {
  const realKey = loadKey()
  const taskToken = 'zt_' + randomBytes(16).toString('hex')
  const gw = await startGateway(realKey, taskToken)
  console.log(`织页 runner skill 集成测试 ${SANDBOX ? '（Seatbelt 沙箱）' : '（无沙箱）'}`)
  const { base, ws, agentDir, git } = makeWorkspace('skill')
  console.log(`工作区 ${ws}`)
  const runner = new Runner({ ws, agentDir, gatewayUrl: gw.url, token: taskToken, label: 'skill' })
  await step('init（skill）', async () => {
    const r = await runner.send('init', { ...INIT(agentDir), projectKind: 'skill' })
    assert(r.ok, JSON.stringify(r.error))
  })
  await step('prompt：周报生成 skill', async () => {
    const r = await runner.send('prompt', { text: SKILL_PROMPT })
    assert(r.ok && !r.result.aborted, JSON.stringify(r).slice(0, 300))
    say('总结：', r.result.text.replace(/\s+/g, ' ').slice(0, 200))
    return `${r.result.turns} 轮`
  })
  let ok = false
  for (let round = 0; round < 3 && !ok; round++) {
    let vr
    await step(round ? `verify（修复第 ${round} 轮后）` : 'verify（skill）', async () => {
      vr = await runner.send('verify', { projectKind: 'skill' })
      assert(vr.ok, JSON.stringify(vr.error))
      assert(vr.result.previewDir === null && vr.result.publishDir === null && vr.result.editable === false, '结果字段不对')
      if (!vr.result.ok) throw new Error(`${verifySummary(vr)}\n${failureText(vr)}`)
      ok = true
      return verifySummary(vr) + '；' + vr.result.checks.map((c) => c.detail).filter(Boolean).join('；')
    })
    if (!ok && vr?.ok) await runner.send('prompt', { text: `织页自动校验没有通过，请修复：\n\n${failureText(vr)}` })
  }
  await step('skill 包结构：无网站文件、无 __pycache__', async () => {
    const files = git('ls-files', '--others', '--modified', '--exclude-standard').split('\n').filter(Boolean)
    const all = execFileSync('find', ['.', '-path', './.git', '-prune', '-o', '-type', 'f', '-print'], { cwd: ws }).toString().split('\n').filter(Boolean)
    say('文件：', all.join(' '))
    assert(!all.some((f) => /^\.\/(src\/|package\.json|index\.html)/.test(f)), '创建了网站文件')
    assert(!all.some((f) => f.includes('__pycache__')), '留下了 __pycache__')
    assert(all.some((f) => /^\.\/scripts\/.+\.py$/.test(f)), '没有 Python 脚本')
    assert(all.some((f) => f.startsWith('./references/') && !f.endsWith('README.md')), '没有模板文件')
    const v = await runner.send('visual', { projectKind: 'skill', patches: [] })
    assert(!v.ok && v.error.code === 'BAD_REQUEST', 'skill 的 visual 应返回 BAD_REQUEST')
    return `${files.length} 个新增/修改文件`
  })
  const { e } = await runner.shutdown()
  record('shutdown', e.code === 0, `退出码 ${e.code}`)
  gw.close()
  return finish(base)
}

/** 列表单项手动微调：真实构建 + 冒烟 + Chrome 检查只改了那一项（不需要模型） */
async function mainInstance() {
  const { LIST_APP } = await import('./fixtures/list-app.mjs')
  console.log(`织页 runner 列表单项微调集成测试 ${SANDBOX ? '（Seatbelt 沙箱）' : '（无沙箱）'}`)
  const { base, ws, agentDir, git } = makeWorkspace()
  writeFileSync(path.join(ws, 'src/App.tsx'), LIST_APP)
  const runner = new Runner({ ws, agentDir, gatewayUrl: 'http://127.0.0.1:9/llm/v1', token: 'unused', label: 'instance' })
  const preview = path.join(ws, '.zhiye/dist-preview')
  const verifyOk = async () => {
    const vr = await runner.send('verify', { projectKind: 'template' })
    assert(vr.ok && vr.result.ok, vr.ok ? `${verifySummary(vr)}\n${failureText(vr)}` : JSON.stringify(vr.error))
    return verifySummary(vr)
  }
  const readItems = () => withPage(preview, (page) => page.evaluate(() => Array.from(document.querySelectorAll('li')).map((li) => ({
    loc: li.getAttribute('data-loc'), color: getComputedStyle(li).color, span: li.querySelector('span')?.textContent, links: li.querySelectorAll('a').length, text: li.textContent,
  }))))
  await step('verify（列表应用）', verifyOk)
  let items
  await step('visual：只改第 2 项颜色、第 1 项文字、删除第 3 项的链接', async () => {
    items = await readItems()
    assert(items.length === 3 && items.every((i) => i.loc === items[0].loc), `预期 3 个同 loc 的 li：${JSON.stringify(items)}`)
    const locs = await withPage(preview, (page) => page.evaluate(() => ({ span: document.querySelector('li span').getAttribute('data-loc'), a: document.querySelector('li a').getAttribute('data-loc') })))
    const r = await runner.send('visual', { projectKind: 'template', patches: [
      { loc: items[1].loc, index: 1, match: items[1].text, styles: { color: '#d62424' } },
      { loc: locs.span, index: 0, match: '水果', text: '精选' },
      { loc: locs.a, index: 2, match: '详情', removed: true },
    ] })
    assert(r.ok && r.result.failures.length === 0 && r.result.applied === 3, JSON.stringify(r))
    say(git('diff', '--stat').trim().split('\n').pop())
    return items[1].loc
  })
  await step('verify + Chrome：只有对应的那一项变了', async () => {
    const sum = await verifyOk()
    const after = await readItems()
    const red = 'rgb(214, 36, 36)'
    assert(after.length === 3, `li 数量 ${after.length}`)
    assert(after[1].color === red && after[0].color !== red && after[2].color !== red, `颜色：${after.map((i) => i.color)}`)
    assert(after[0].span === '精选' && after[1].span === '水果' && after[2].span === '水果', `文字：${after.map((i) => i.span)}`)
    assert(after[0].links === 1 && after[1].links === 1 && after[2].links === 0, `链接：${after.map((i) => i.links)}`)
    return sum
  })
  await step('visual：再次修改第 2 项（替换不嵌套）并改第 3 项', async () => {
    const loc = (await readItems())[0].loc
    const r = await runner.send('visual', { projectKind: 'template', patches: [{ loc, index: 1, styles: { color: '#1f6feb' } }, { loc, index: 2, styles: { color: '#d62424' } }] })
    assert(r.ok && r.result.failures.length === 0, JSON.stringify(r))
    await verifyOk()
    const after = await readItems()
    assert(after[1].color === 'rgb(31, 111, 235)' && after[2].color === 'rgb(214, 36, 36)' && after[0].color !== after[2].color, `颜色：${after.map((i) => i.color)}`)
    const src = readFileSync(path.join(ws, 'src/App.tsx'), 'utf8')
    assert((src.match(/__i === 1/g) || []).length === 1, '同一项出现了多次条件')
    return '第 2 项改蓝、第 3 项改红，源码里每项一个条件'
  })
  await step('数据文字和可复用组件被拒绝', async () => {
    const locs = await withPage(preview, (page) => page.evaluate(() => ({ b: document.querySelector('li b').getAttribute('data-loc'), i: document.querySelector('i.badge').getAttribute('data-loc') })))
    const r = await runner.send('visual', { projectKind: 'template', patches: [{ loc: locs.b, index: 0, text: 'x' }, { loc: locs.i, index: 1, sameLocCount: 2, styles: { color: 'red' } }] })
    assert(r.ok && r.result.applied === 0, JSON.stringify(r))
    return r.result.failures.map((f) => f.reason).join(' / ')
  })
  const { e } = await runner.shutdown()
  record('shutdown', e.code === 0, `退出码 ${e.code}`)
  return finish(base)
}

/** 待办清单、步骤详情、思考过程、steer（真实 Kimi） */
async function mainSteps() {
  const realKey = loadKey()
  const taskToken = 'zt_' + randomBytes(16).toString('hex')
  const gw = await startGateway(realKey, taskToken)
  console.log(`织页 runner 步骤详情集成测试 ${SANDBOX ? '（Seatbelt 沙箱）' : '（无沙箱）'}`)
  const { base, ws, agentDir } = makeWorkspace()
  const runner = new Runner({ ws, agentDir, gatewayUrl: gw.url, token: taskToken, label: 'steps' })
  await step('init', async () => { const r = await runner.send('init', { ...INIT(agentDir), thinking: 'medium' }); assert(r.ok, JSON.stringify(r.error)) })
  let steerReply = null
  await step('prompt + steer：计数器，途中追加「标题写成 我的计数器」', async () => {
    let steered = false
    runner.on((e) => {
      if (!steered && e.type === 'tool' && e.state === 'end' && e.name !== 'todo_write') {
        steered = true
        runner.send('steer', { text: '补充要求：页面标题（h1）的文字请写成「我的计数器」。' }).then((r) => { steerReply = r })
      }
    })
    const r = await runner.send('prompt', { text: PROMPT })
    assert(r.ok && !r.result.aborted, JSON.stringify(r).slice(0, 300))
    assert(steerReply?.ok && ['queued', 'handled'].includes(steerReply.result.status), `steer 回复：${JSON.stringify(steerReply)}`)
    const late = await runner.send('steer', { text: 'x' })
    assert(!late.ok && late.error.code === 'BAD_STATE', 'prompt 结束后 steer 应返回 BAD_STATE')
    return `${r.result.turns} 轮；steer → ${steerReply.result.status}`
  })
  await step('todo 事件：先列清单，逐项推进，最后全部完成', async () => {
    const todos = runner.events.filter((e) => e.type === 'todo')
    assert(todos.length >= 2, `todo 事件只有 ${todos.length} 个`)
    for (const t of todos) {
      assert(t.items.length >= 1 && t.items.length <= 8, '项数不对')
      assert(t.items.filter((i) => i.status === 'in_progress').length <= 1, '多个 in_progress')
      assert(t.items.every((i) => i.content && [...i.content].length <= 80), '内容不合法')
    }
    const firstTodo = runner.events.findIndex((e) => e.type === 'todo')
    const firstWrite = runner.events.findIndex((e) => e.type === 'tool' && (e.name === 'write' || e.name === 'edit'))
    assert(firstTodo >= 0 && (firstWrite < 0 || firstTodo < firstWrite), '应在改文件之前先列清单')
    const last = todos.at(-1).items
    assert(last.every((i) => i.status === 'completed'), `最后一次清单没有全部完成：${JSON.stringify(last)}`)
    const progress = todos.map((t) => `${t.items.filter((i) => i.status === 'completed').length}/${t.items.length}`)
    say('清单：', last.map((i) => i.content).join(' | '))
    return `进度 ${progress.join(' → ')}`
  })
  await step('tool 详情：write/edit/read/bash 的 detail 结构，不含绝对路径', async () => {
    const ends = runner.events.filter((e) => e.type === 'tool' && e.state === 'end')
    const kinds = {}
    for (const e of ends) {
      if (e.name === 'todo_write') { assert(!e.detail, 'todo_write 不应带 detail'); continue }
      assert(e.detail && e.detail.kind === e.name, `缺少 detail：${JSON.stringify(e).slice(0, 200)}`)
      kinds[e.name] = (kinds[e.name] || 0) + 1
      const d = e.detail
      if (d.kind === 'write') assert(typeof d.created === 'boolean' && d.lines > 0 && typeof d.preview === 'string' && /\+\d+ 行$/.test(e.summary), `write：${e.summary}`)
      if (d.kind === 'edit' && e.ok) assert(d.diff.startsWith(`--- a/${d.path}`) && /\+\d+ −\d+$/.test(e.summary), `edit：${e.summary}`)
      if (d.kind === 'bash') assert('exitCode' in d && typeof d.output === 'string', 'bash')
      if (d.kind === 'read') assert(Number.isInteger(d.lines), 'read')
    }
    const dump = JSON.stringify(runner.events)
    for (const p of [ws, ws.replace(/^\/private/, ''), agentDir]) assert(!dump.includes(p), `事件里出现了绝对路径 ${p}`)
    say('摘要：', ends.map((e) => e.summary).slice(0, 8).join(' ｜ '))
    return Object.entries(kinds).map(([k, v]) => `${k}×${v}`).join(' ')
  })
  await step('thinking 事件', async () => {
    const th = runner.events.filter((e) => e.type === 'thinking')
    const ends = runner.events.filter((e) => e.type === 'thinking_end')
    assert(ends.length >= 1, '没有 thinking_end（模型没有返回思考过程？）')
    assert(ends.every((e) => Number.isInteger(e.durationMs) && e.durationMs >= 0), 'durationMs')
    const bytes = th.reduce((n, e) => n + Buffer.byteLength(e.text), 0)
    assert(bytes <= 20 * 1024, '超过 20 KB')
    say('思考片段：', th.map((e) => e.text).join('').replace(/\s+/g, ' ').slice(0, 120))
    return `${th.length} 段文字（${bytes} 字节），${ends.length} 次 thinking_end`
  })
  await step('verify + 追加要求生效', async () => {
    const vr = await runner.send('verify', { projectKind: 'template' })
    if (!vr.result?.ok) {
      const fix = await runner.send('prompt', { text: `织页自动校验没有通过，请修复：\n\n${failureText(vr)}` })
      assert(fix.ok, JSON.stringify(fix.error))
    }
    const vr2 = vr.result?.ok ? vr : await runner.send('verify', { projectKind: 'template' })
    assert(vr2.ok && vr2.result.ok, `${verifySummary(vr2)}\n${failureText(vr2)}`)
    const h1 = await withPage(path.join(ws, '.zhiye/dist-publish'), (page) => page.locator('h1').first().textContent())
    assert(/我的计数器/.test(h1 || ''), `steer 没有生效，标题是「${h1}」`)
    return `${verifySummary(vr2)}；标题「${h1.trim()}」`
  })
  const { e } = await runner.shutdown()
  record('shutdown', e.code === 0, `退出码 ${e.code}`)
  gw.close()
  return finish(base)
}

async function main() {
  if (args.has('--steps')) return mainSteps()
  if (args.has('--instance')) return mainInstance()
  if (args.has('--skill')) return mainSkill()
  const realKey = loadKey()
  const taskToken = 'zt_' + randomBytes(16).toString('hex')
  const gw = await startGateway(realKey, taskToken)
  console.log(`织页 runner 集成测试 ${SANDBOX ? '（Seatbelt 沙箱）' : '（无沙箱）'}；网关 ${gw.url} → ${UPSTREAM}`)
  const { base, ws, agentDir, git } = makeWorkspace()
  console.log(`工作区 ${ws}`)

  // 1. 协议健壮性 + 网关覆盖确实生效（坏地址 → MODEL_ERROR）
  await step('协议：坏命令返回错误、网关地址覆盖生效', async () => {
    const bad = makeWorkspace()
    const r = new Runner({ ws: bad.ws, agentDir: bad.agentDir, gatewayUrl: 'http://127.0.0.1:9/llm/v1', token: taskToken, label: 'bad' })
    const malformed = await r.raw('{not json')
    assert(malformed.status === 400, `非法 JSON 应返回 400，实际 ${malformed.status}：${malformed.body}`)
    const unk = await r.send('frobnicate')
    assert(unk.ok === false && unk.error.code === 'UNKNOWN_COMMAND', '未知命令应返回 UNKNOWN_COMMAND')
    const early = await r.send('prompt', { text: 'hi' })
    assert(early.ok === false && early.error.code === 'BAD_STATE', '未 init 的 prompt 应返回 BAD_STATE')
    const init = await r.send('init', INIT(bad.agentDir))
    assert(init.ok, `init 失败：${JSON.stringify(init.error)}`)
    const p = await r.send('prompt', { text: '说一个字：好' })
    assert(p.ok === false && p.error.code === 'MODEL_ERROR', `坏网关应返回 MODEL_ERROR，实际 ${JSON.stringify(p).slice(0, 300)}`)
    const { e } = await r.shutdown()
    assert(e.code === 0, `退出码 ${e.code}`)
    assert(malformed.body.includes('JSON'), `非法 JSON 的错误说明不对：${malformed.body}`)
    rmSync(bad.base, { recursive: true, force: true })
    return `坏网关报错：${p.error.message.slice(0, 80)}`
  })

  // 1b. 安全守卫在真实 Pi 里生效：用脚本化的假模型网关强制发出违规工具调用（真实模型会自觉拒绝，无法测到守卫）
  await step('安全守卫：Pi 中拦截联网 / 写工作区外 / 改 package.json', async () => {
    const g = makeWorkspace()
    const evil = path.join(g.base, 'evil.txt')
    const calls = [
      ['bash', { command: 'curl -s https://example.com -o src/x.html' }],
      ['write', { path: evil, content: 'hello' }],
      ['write', { path: 'package.json', content: '{}' }],
      ['bash', { command: 'npm install lodash' }],
      ['read', { path: '/etc/hosts' }],
      ['write', { path: 'src/ok.txt', content: 'ok' }],
    ]
    const fake = await startScriptedGateway(taskToken, calls)
    const r = new Runner({ ws: g.ws, agentDir: g.agentDir, gatewayUrl: fake.url, token: taskToken, label: 'guard' })
    assert((await r.send('init', INIT(g.agentDir))).ok, 'init 失败')
    const p = await r.send('prompt', { text: '随便做点什么' })
    assert(p.ok, JSON.stringify(p.error))
    await r.shutdown()
    const ends = r.events.filter((e) => e.type === 'tool' && e.state === 'end')
    assert(ends.length === calls.length, `工具调用数 ${ends.length}`)
    assert(ends.slice(0, 5).every((e) => !e.ok), `违规调用没有全部被拦截：${JSON.stringify(ends)}`)
    assert(ends[5].ok, '合法写入被误拦')
    let exists = true
    try { statSync(evil) } catch { exists = false }
    assert(!exists, '工作区以外的文件被写入了！')
    assert(!g.git('status', '--porcelain').includes('package.json'), 'package.json 被修改了！')
    assert(fake.toolResults.filter((t) => t.includes('安全守卫拦截')).length === 5, `模型没有收到拦截原因：${fake.toolResults.join(' | ').slice(0, 300)}`)
    const audit = readFileSync(path.join(g.base, 'runner-guard.log'), 'utf8').split('\n').filter((l) => l.includes('[audit]') && l.includes('BLOCK')).length
    fake.close()
    rmSync(g.base, { recursive: true, force: true })
    return `5/5 违规调用被拦截、1 次合法写入放行；审计 BLOCK ${audit} 条`
  })

  // 2. 生成 → verify
  let runner = new Runner({ ws, agentDir, gatewayUrl: gw.url, token: taskToken, label: 'gen' })
  let sessionFile
  const ok1 = await step('init', async () => {
    const r = await runner.send('init', INIT(agentDir))
    assert(r.ok, JSON.stringify(r.error))
    sessionFile = r.result.sessionFile
    assert(sessionFile && sessionFile.startsWith(agentDir), `sessionFile 不在 agentDir 里：${sessionFile}`)
    return path.relative(agentDir, sessionFile)
  })
  if (!ok1) return finish(base)

  let promptResult
  await step('prompt：点击计数器', async () => {
    const before = runner.events.length
    const r = await runner.send('prompt', { text: PROMPT })
    assert(r.ok, JSON.stringify(r.error))
    promptResult = r.result
    const ev = runner.events.slice(before)
    const tools = ev.filter((e) => e.type === 'tool' && e.state === 'start')
    assert(tools.length > 0, '没有任何工具调用')
    assert(ev.some((e) => e.type === 'agent_text'), '没有 agent_text 事件')
    assert(ev.some((e) => e.type === 'turn'), '没有 turn 事件')
    assert(!r.result.aborted, 'aborted 不应为 true')
    say('总结：', r.result.text.replace(/\s+/g, ' ').slice(0, 200))
    return `${r.result.turns} 轮，${tools.length} 次工具调用`
  })

  let verified = false
  for (let round = 0; round < 3 && !verified; round++) {
    let vr
    await step(round === 0 ? 'verify（生成后）' : `verify（修复第 ${round} 轮后）`, async () => {
      vr = await runner.send('verify', { projectKind: 'template' })
      assert(vr.ok, JSON.stringify(vr.error))
      if (!vr.result.ok) throw new Error(`${verifySummary(vr)}\n${failureText(vr).slice(0, 1500)}`)
      verified = true
      return verifySummary(vr)
    })
    if (!verified && vr?.ok) {
      await step(`prompt：修复校验错误（第 ${round + 1} 轮）`, async () => {
        const r = await runner.send('prompt', { text: `织页自动校验没有通过，请修复以下问题：\n\n${failureText(vr)}` })
        assert(r.ok, JSON.stringify(r.error))
        return `${r.result.turns} 轮`
      })
    }
  }

  await step('生成结果：只改了允许的文件，交互正确', async () => {
    const changed = git('status', '--porcelain').split('\n').filter(Boolean).map((l) => l.slice(3))
    say('改动文件：', changed.join(', '))
    assert(!changed.some((f) => /package\.json|vite\.config|tsconfig|\.gitignore/.test(f)), `改动了不允许的文件：${changed}`)
    assert(changed.some((f) => f.startsWith('src/')), '没有改动 src/')
    return withPage(path.join(ws, '.zhiye/dist-publish'), async (page) => {
      const add = page.getByRole('button', { name: /加一/ })
      const reset = page.getByRole('button', { name: /重置/ })
      assert(await add.count() >= 1, '找不到「加一」按钮')
      assert(await reset.count() >= 1, '找不到「重置」按钮')
      assert(await reset.first().isDisabled(), '次数为 0 时「重置」应禁用')
      await add.first().click(); await add.first().click()
      const body = await page.locator('body').innerText()
      assert(/2/.test(body), '点两次后页面上没有显示 2')
      assert(!(await reset.first().isDisabled()), '次数大于 0 时「重置」应可用')
      await reset.first().click()
      assert(await reset.first().isDisabled(), '重置后「重置」应再次禁用')
      assert((await page.locator('[data-loc]').count()) === 0, '发布版不应带 data-loc')
      return '加一 / 重置 / 禁用逻辑正确，发布版无 data-loc'
    })
  })

  // 3. 可视化编辑：从预览版读取标题的 data-loc
  let heading
  await step('visual：改标题文字和颜色', async () => {
    heading = await withPage(path.join(ws, '.zhiye/dist-preview'), (page) => page.evaluate(() => {
      const el = document.querySelector('h1[data-loc], h2[data-loc]')
      return el && { loc: el.getAttribute('data-loc'), kind: el.getAttribute('data-zt'), text: el.textContent }
    }))
    assert(heading?.loc, '预览版里找不到带 data-loc 的标题')
    say('标题', JSON.stringify(heading))
    const patch = { loc: heading.loc, styles: { color: '#4f78eb' } }
    if (heading.kind === 's' || heading.kind === 'e') patch.text = '我的计数器'
    const r = await runner.send('visual', { projectKind: 'template', patches: [patch] })
    assert(r.ok, JSON.stringify(r.error))
    assert(r.result.failures.length === 0 && r.result.applied === 1, JSON.stringify(r.result))
    say(git('diff', '--stat').trim().split('\n').pop())
    return `${heading.loc}${patch.text ? '' : '（标题文字是动态的，只改颜色）'}`
  })

  await step('verify（可视化编辑后）', async () => {
    const vr = await runner.send('verify', { projectKind: 'template' })
    assert(vr.ok && vr.result.ok, vr.ok ? `${verifySummary(vr)}\n${failureText(vr)}` : JSON.stringify(vr.error))
    const got = await withPage(path.join(ws, '.zhiye/dist-preview'), (page) => page.evaluate((loc) => {
      const el = document.querySelector(`[data-loc="${loc}"]`)
      return el && { text: el.textContent, color: getComputedStyle(el).color }
    }, heading.loc))
    assert(got, '可视化编辑后找不到原来的标题（loc 变了）')
    assert(got.color === 'rgb(79, 120, 235)', `颜色没有生效：${got.color}`)
    if (heading.kind === 's' || heading.kind === 'e') assert(got.text.includes('我的计数器'), `文字没有生效：${got.text}`)
    const src = git('diff')
    assert(!src.includes('data-loc'), '源码里出现了 data-loc')
    return `${verifySummary(vr)}；标题「${got.text.trim()}」${got.color}`
  })

  // 4. abort + 用 sessionFile 恢复
  if (!args.has('--skip-abort')) {
    await step('abort：prompt 进行中停止', async () => {
      let aborted = false
      const abortAfterTool = new Promise((resolve) => {
        const timer = setTimeout(resolve, 20000)
        runner.on((e) => { if (!aborted && e.type === 'tool' && e.state === 'start') { clearTimeout(timer); resolve() } })
      })
      const pr = runner.send('prompt', { text: '在计数器下面加一个“减一”按钮（次数为 0 时禁用），再加一行小字显示“最多点到 99 次”，并且次数达到 99 时禁用“加一”。' })
      await abortAfterTool
      aborted = true
      const t = Date.now()
      const ar = await runner.send('abort')
      const ms = Date.now() - t
      const r = await pr
      assert(ar.ok, `abort 回复失败：${JSON.stringify(ar)}`)
      assert(r.ok && r.result.aborted === true, `prompt 应以 aborted:true 返回：${JSON.stringify(r).slice(0, 300)}`)
      return `abort 用时 ${ms}ms，prompt 返回 aborted:true（${r.result.turns} 轮）`
    })
    const { e } = await runner.shutdown()
    record('shutdown 后进程退出', e.code === 0, `退出码 ${e.code}`)

    const sizeBefore = statSync(sessionFile).size
    runner = new Runner({ ws, agentDir, gatewayUrl: gw.url, token: taskToken, label: 'resume' })
    await step('恢复：init(sessionFile) → prompt「继续」', async () => {
      const r = await runner.send('init', INIT(agentDir, sessionFile))
      assert(r.ok, JSON.stringify(r.error))
      assert(r.result.sessionFile === sessionFile, `恢复后的 sessionFile 不同：${r.result.sessionFile}`)
      const p = await runner.send('prompt', { text: '刚才的任务被中断了，请继续完成它（加“减一”按钮、提示文字和 99 次上限），完成后自查类型。' })
      assert(p.ok && !p.result.aborted, JSON.stringify(p).slice(0, 300))
      assert(statSync(sessionFile).size > sizeBefore, '会话文件没有追加内容')
      say('总结：', p.result.text.replace(/\s+/g, ' ').slice(0, 200))
      return `${p.result.turns} 轮`
    })
    let ok = false
    for (let round = 0; round < 3 && !ok; round++) {
      let vr
      await step(round ? `verify（恢复后修复第 ${round} 轮）` : 'verify（恢复后）', async () => {
        vr = await runner.send('verify', { projectKind: 'template' })
        assert(vr.ok, JSON.stringify(vr.error))
        if (!vr.result.ok) throw new Error(`${verifySummary(vr)}\n${failureText(vr).slice(0, 1500)}`)
        ok = true
        return verifySummary(vr)
      })
      if (!ok && vr?.ok) await runner.send('prompt', { text: `织页自动校验没有通过，请修复：\n\n${failureText(vr)}` })
    }
    await step('恢复后的功能：减一按钮存在，原有功能保留', () => withPage(path.join(ws, '.zhiye/dist-publish'), async (page) => {
      const minus = page.getByRole('button', { name: /减一/ })
      assert(await minus.count() >= 1, '找不到「减一」按钮')
      assert(await page.getByRole('button', { name: /加一/ }).count() >= 1, '「加一」按钮丢了')
      return '「减一」按钮已添加'
    }))
  }
  const { e } = await runner.shutdown()
  record('最终 shutdown', e.code === 0, `退出码 ${e.code}；网关转发 ${gw.requests} 次请求`)
  if (runner.badLines.length) record('stdout 为空、SSE 帧全部可解析', false, runner.badLines.slice(0, 3).join(' | '))
  gw.close()
  return finish(base)
}

function finish(base) {
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${failed.length ? '✘ 失败' : '✔ 全部通过'}：${results.length - failed.length}/${results.length}，总用时 ${((Date.now() - t0) / 1000).toFixed(0)}s`)
  if (!args.has('--keep') && !failed.length) rmSync(base, { recursive: true, force: true })
  else console.log(`保留现场：${base}`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error(e); process.exit(2) })
