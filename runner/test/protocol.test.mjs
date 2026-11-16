#!/usr/bin/env node
// 控制通道协议测试（HTTP + SSE）。不需要真实模型和 KIMI_API_KEY。
//
//   ZHIYE_RUNNER_TOKEN  → /v1/commands、/v1/events 的 Bearer 鉴权
//   GET  /v1/healthz    → 200 {ok:true}，免鉴权
//   POST /v1/commands   → 202 {accepted:true}，回复只从 SSE 回来
//   GET  /v1/events     → SSE：id + data；after=N 从缓冲区续传；越界 409
//
// 覆盖：缺少/错误 token → 401；after=N 重放；日志帧先于对应回复；
//       SSE 客户端断开后 runner 继续运行。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtempSync, realpathSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const MAIN = path.resolve(here, '../src/main.mjs')
const DELAY = (ms) => new Promise((r) => setTimeout(r, ms))

function tmpdir() {
  return realpathSync(mkdtempSync(path.join(os.tmpdir(), 'zhiye-proto-')))
}

/** 启动一个 runner，等 READY 行；返回 { proc, state, ready, exit }。 */
function start({ token = 'rt_' + randomBytes(16).toString('hex'), env = {}, cwd } = {}) {
  const dir = cwd || tmpdir()
  const fullEnv = {
    ...process.env,
    ZHIYE_GATEWAY_URL: 'http://127.0.0.1:9/llm/v1',
    ZHIYE_TASK_TOKEN: 'unused-task-token',
    ZHIYE_RUNNER_HOST: '127.0.0.1',
    ZHIYE_RUNNER_PORT: '0',
    ...env,
  }
  if (token === null) delete fullEnv.ZHIYE_RUNNER_TOKEN
  else fullEnv.ZHIYE_RUNNER_TOKEN = token
  const proc = spawn(process.execPath, [MAIN], { cwd: dir, env: fullEnv, stdio: ['ignore', 'pipe', 'pipe'] })
  const state = { proc, token, dir, stderr: '' }
  const exit = new Promise((resolve) => proc.on('exit', (code, sig) => resolve({ code, sig })))
  const ready = new Promise((resolve, reject) => {
    let buf = ''
    proc.stderr.on('data', (d) => {
      state.stderr += d
      buf += d
      const m = /ZHIYE_RUNNER_READY port=(\d+)/.exec(buf)
      if (m && state.port === undefined) {
        state.port = Number(m[1])
        state.base = `http://127.0.0.1:${state.port}`
        resolve(state)
      }
    })
    proc.on('exit', (code, sig) => reject(new Error(`runner 退出：code=${code} sig=${sig}\n${state.stderr}`)))
  })
  ready.catch(() => {}) // 缺少 token 的用例只等 exit
  return { proc, state, ready, exit }
}

async function stop(h) {
  if (h.proc.exitCode === null && h.proc.signalCode === null) {
    h.proc.kill('SIGKILL')
    await Promise.race([h.exit, DELAY(2000)])
  }
  rmSync(h.state.dir, { recursive: true, force: true })
}

const bearer = (token) => ({ authorization: `Bearer ${token}` })

function postCommand(base, token, body) {
  return fetch(`${base}/v1/commands`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? bearer(token) : {}) },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  })
}

/** 最小 SSE 客户端：后台读流，帧放入队列，next() 带超时。 */
class Sse {
  constructor(base, token, after = 0) {
    this.url = `${base}/v1/events?after=${after}`
    this.token = token
    this.ctrl = new AbortController()
    this.decoder = new TextDecoder()
    this.buf = ''
    this.queue = []
    this.waiters = []
    this.ended = false
    this.error = null
    this.status = null
    this.body = ''
  }

  async open() {
    const res = await fetch(this.url, { headers: bearer(this.token), signal: this.ctrl.signal })
    this.status = res.status
    if (!res.ok) { this.body = await res.text().catch(() => ''); return this }
    this.pump(res.body)
    return this
  }

  async pump(stream) {
    try {
      const reader = stream.getReader()
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        this.buf += this.decoder.decode(value, { stream: true })
        for (let f = this.take(); f; f = this.take()) {
          this.queue.push(f)
          this.wake()
        }
      }
    } catch (e) {
      if (!this.ctrl.signal.aborted) this.error = e
    }
    this.ended = true
    this.wake()
  }

  take() {
    for (;;) {
      const i = this.buf.indexOf('\n\n')
      if (i < 0) return null
      const raw = this.buf.slice(0, i)
      this.buf = this.buf.slice(i + 2)
      let seq = null
      const data = []
      for (const line of raw.split('\n')) {
        if (line.startsWith(':')) continue // connected / keepalive
        if (line.startsWith('id:')) seq = Number(line.slice(3).trim())
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (!data.length) continue
      const message = JSON.parse(data.join('\n'))
      return { seq: seq ?? message.seq, message }
    }
  }

  wake() {
    for (const w of this.waiters.splice(0)) w()
  }

  next(timeoutMs = 5000) {
    if (this.queue.length) return Promise.resolve(this.queue.shift())
    if (this.error) return Promise.reject(this.error)
    if (this.ended) return Promise.reject(new Error('SSE 流已结束'))
    return new Promise((resolve, reject) => {
      const check = () => {
        if (this.queue.length) { clearTimeout(timer); resolve(this.queue.shift()) }
        else if (this.error) { clearTimeout(timer); reject(this.error) }
        else if (this.ended) { clearTimeout(timer); reject(new Error('SSE 流已结束')) }
        else this.waiters.push(check)
      }
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== check)
        reject(new Error('等待 SSE 帧超时'))
      }, timeoutMs)
      this.waiters.push(check)
    })
  }

  close() { try { this.ctrl.abort() } catch {} }
}

async function nextReply(sse, id, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const f = await sse.next(deadline - Date.now())
    if (f.message.type === 'reply' && f.message.id === id) return f
  }
}

test('缺少 ZHIYE_RUNNER_TOKEN：拒绝启动（退出码 2，不打印 READY）', async () => {
  const h = start({ token: null })
  try {
    const e = await h.exit
    assert.equal(e.code, 2)
    assert.match(h.state.stderr, /ZHIYE_RUNNER_TOKEN/)
    assert.doesNotMatch(h.state.stderr, /ZHIYE_RUNNER_READY/)
  } finally {
    await stop(h)
  }
})

test('鉴权：healthz 免鉴权；命令/事件缺少或错误 token 返回 401', async () => {
  const h = start()
  try {
    const { base } = await h.ready

    const health = await fetch(`${base}/v1/healthz`)
    assert.equal(health.status, 200)
    assert.equal((await health.json()).ok, true)

    for (const token of [null, '', 'wrong-token']) {
      const res = await postCommand(base, token, { id: 'x', cmd: 'frobnicate' })
      assert.equal(res.status, 401, `POST /v1/commands token=${JSON.stringify(token)}`)
      await res.text()
    }
    const ev = await fetch(`${base}/v1/events?after=0`)
    assert.equal(ev.status, 401)
    await ev.text()

    // 被拒绝的命令不能被执行：合法 SSE 上不应出现 id=x 的回复。
    const sse = await new Sse(base, h.state.token, 0).open()
    try {
      const first = await sse.next()
      assert.notEqual(first.message.type, 'reply')
      await DELAY(100)
      assert.equal(sse.queue.filter((f) => f.message.type === 'reply' && f.message.id === 'x').length, 0)
    } finally {
      sse.close()
    }
  } finally {
    await stop(h)
  }
})

test('SSE after=N：从缓冲区重放之后的帧，不重复更早的帧；越界返回 409', async () => {
  const h = start()
  try {
    const { base } = await h.ready
    const live = await new Sse(base, h.state.token, 0).open()
    try {
      const p1 = await postCommand(base, h.state.token, { id: 'u1', cmd: 'frobnicate' })
      assert.equal(p1.status, 202)
      // POST 只确认接收，回复（和命令结果）不在 HTTP 响应里。
      assert.deepEqual(await p1.json(), { accepted: true })
      const r1 = await nextReply(live, 'u1')
      assert.equal(r1.message.ok, false)
      assert.equal(r1.message.error.code, 'UNKNOWN_COMMAND')

      assert.equal((await postCommand(base, h.state.token, { id: 'u2', cmd: 'frobnicate' })).status, 202)
      const r2 = await nextReply(live, 'u2')
      assert.ok(r2.seq > r1.seq, `序号没有递增：${r1.seq} → ${r2.seq}`)

      // after=r1.seq 应只重放 r1 之后的帧，并且能拿到 u2 的回复。
      const replay = await new Sse(base, h.state.token, r1.seq).open()
      assert.equal(replay.status, 200)
      try {
        const got = []
        for (;;) {
          const f = await replay.next(2000)
          got.push(f)
          if (f.message.type === 'reply' && f.message.id === 'u2') break
        }
        assert.ok(got.every((f) => f.seq > r1.seq), `重放包含旧帧：${got.map((f) => f.seq)}`)
        assert.equal(got.filter((f) => f.message.type === 'reply' && f.message.id === 'u1').length, 0, '重放了 u1')
        assert.ok(got.some((f) => f.message.type === 'reply' && f.message.id === 'u2'), '缺少 u2 的回复')

        // after 大于最新序号 → 409（位置还没发生）。
        const ahead = await fetch(`${base}/v1/events?after=${r2.seq + 1000}`, { headers: bearer(h.state.token) })
        assert.equal(ahead.status, 409)
        await ahead.text()
      } finally {
        replay.close()
      }
    } finally {
      live.close()
    }
  } finally {
    await stop(h)
  }
})

test('日志帧先于对应回复到达（init 期间的日志早于 init 回复）', async () => {
  const h = start()
  try {
    const { base } = await h.ready
    const agentDir = path.join(h.state.dir, 'agent')
    const sse = await new Sse(base, h.state.token, 0).open()
    try {
      const res = await postCommand(base, h.state.token, {
        id: 'i1',
        cmd: 'init',
        agentDir,
        sessionFile: null,
        model: { provider: 'kimi-coding', id: 'kimi-for-coding' },
        thinking: 'low',
        projectKind: 'template',
        maxTurns: 5,
      })
      assert.equal(res.status, 202)
      const seen = []
      let reply = null
      const deadline = Date.now() + 20000
      while (Date.now() < deadline) {
        const f = await sse.next(deadline - Date.now())
        seen.push(f)
        if (f.message.type === 'reply' && f.message.id === 'i1') { reply = f; break }
      }
      assert.ok(reply, `没有收到 init 回复，已收到：${seen.map((f) => f.message.type)}`)
      assert.equal(reply.message.ok, true, JSON.stringify(reply.message.error))
      const logs = seen.filter((f) => f.message.type === 'log')
      assert.ok(logs.length >= 1, `回复前没有日志帧：${seen.map((f) => f.message.type)}`)
      assert.ok(logs.some((f) => /model kimi-coding|session /.test(f.message.line)), `日志内容不对：${logs.map((f) => f.message.line)}`)
      assert.ok(logs.every((f) => f.seq < reply.seq), `日志帧不在回复之前：log=${logs.map((f) => f.seq)} reply=${reply.seq}`)
    } finally {
      sse.close()
    }
  } finally {
    await stop(h)
  }
})

test('SSE 客户端断开后 runner 继续运行并继续处理命令', async () => {
  const h = start()
  try {
    const { base } = await h.ready

    // 先接一个客户端，读一帧后直接断开。
    const temp = await new Sse(base, h.state.token, 0).open()
    const first = await temp.next()
    assert.notEqual(first.message.type, 'reply')
    temp.close()
    await DELAY(150)

    assert.equal(h.proc.exitCode, null, 'SSE 断开后 runner 进程退出了')
    const health = await fetch(`${base}/v1/healthz`)
    assert.equal(health.status, 200)

    // 新连接仍能收到命令回复。
    const live = await new Sse(base, h.state.token, 0).open()
    try {
      assert.equal((await postCommand(base, h.state.token, { id: 'd1', cmd: 'frobnicate' })).status, 202)
      const r = await nextReply(live, 'd1')
      assert.equal(r.message.ok, false)
      assert.equal(r.message.error.code, 'UNKNOWN_COMMAND')
    } finally {
      live.close()
    }
    assert.equal(h.proc.exitCode, null, 'runner 进程不应退出')
  } finally {
    await stop(h)
  }
})