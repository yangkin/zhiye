// 沙箱内 runner 的 HTTP 控制通道（见 docs/runner-protocol.md）。
// 命令走短 POST，回复和事件都从一条有序的 SSE 流回来；断线按序号续传。
import { createServer } from 'node:http'
import { timingSafeEqual } from 'node:crypto'

const MAX_BODY = 1024 * 1024
const REPLAY_LIMIT = 4096
const REPLAY_BYTES = 8 * 1024 * 1024
const CLIENT_BUFFER_LIMIT = 8 * 1024 * 1024
const KEEPALIVE_MS = 15000

function authorized(req, token) {
  const h = req.headers.authorization || ''
  if (!h.startsWith('Bearer ')) return false
  const got = Buffer.from(h.slice(7))
  const want = Buffer.from(token)
  return got.length === want.length && timingSafeEqual(got, want)
}

function json(res, status, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

/**
 * handlers: { [cmd]: async (cmd) => result }
 * bypass: 可以在普通命令执行期间插入的命令（abort / steer）
 */
export class ControlServer {
  constructor({ token, host = '127.0.0.1', port = 0, handlers, bypass = ['abort', 'steer'], onShutdown, beforeReply }) {
    if (!token) throw new Error('缺少 ZHIYE_RUNNER_TOKEN')
    this.beforeReply = beforeReply
    this.token = token
    this.host = host
    this.port = port
    this.handlers = handlers
    this.bypass = new Set(bypass)
    this.onShutdown = onShutdown
    this.seq = 0
    this.buffer = []
    this.bufferBytes = 0
    this.oldest = 1
    this.clients = new Set()
    this.queue = []
    this.busy = false
    this.closed = false
    this.server = createServer((req, res) => this.route(req, res))
  }

  // ---------- 发布 ----------

  publish(obj) {
    if (this.closed && obj.type !== 'reply') return
    const seq = ++this.seq
    const payload = JSON.stringify({ ...obj, seq })
    const record = { seq, payload }
    this.buffer.push(record)
    this.bufferBytes += payload.length
    while (this.buffer.length > REPLAY_LIMIT || this.bufferBytes > REPLAY_BYTES) {
      const dropped = this.buffer.shift()
      if (!dropped) break
      this.bufferBytes -= dropped.payload.length
      this.oldest = dropped.seq + 1
    }
    const frame = `id: ${seq}\ndata: ${payload}\n\n`
    for (const c of this.clients) {
      const ok = c.write(frame)
      if (!ok && c.writableLength > CLIENT_BUFFER_LIMIT) {
        this.clients.delete(c)
        try { c.destroy() } catch {}
      }
    }
  }

  log(line) {
    const text = String(line ?? '')
    for (const l of text.split('\n')) if (l.trim()) this.publish({ type: 'log', line: l })
  }

  reply(id, result) { this.publish({ type: 'reply', id, ok: true, result: result ?? {} }) }
  replyError(id, code, message) { this.publish({ type: 'reply', id: id ?? null, ok: false, error: { code, message } }) }

  // ---------- 命令队列 ----------

  accept(cmd) {
    if (this.bypass.has(cmd.cmd)) { this.dispatch(cmd); return }
    this.queue.push(cmd)
    this.pump()
  }

  async dispatch(cmd) {
    const t = Date.now()
    try {
      const h = this.handlers[cmd.cmd]
      if (!h) { this.replyError(cmd.id, 'UNKNOWN_COMMAND', `未知命令：${cmd.cmd}`); return }
      const result = await h(cmd)
      this.beforeReply?.()
      this.reply(cmd.id, result)
      this.log(`cmd ${cmd.cmd}#${cmd.id} ok ${Date.now() - t}ms`)
    } catch (e) {
      this.beforeReply?.()
      const code = e?.code && typeof e.code === 'string' && /^[A-Z_]+$/.test(e.code) ? e.code : 'INTERNAL'
      this.log(`cmd ${cmd.cmd}#${cmd.id} failed ${code}: ${e?.stack || e}`)
      this.replyError(cmd.id, code, e?.message || String(e))
    }
    if (cmd.cmd === 'shutdown' && this.onShutdown) this.onShutdown()
  }

  async pump() {
    if (this.busy) return
    this.busy = true
    while (this.queue.length) {
      const cmd = this.queue.shift()
      await this.dispatch(cmd)
    }
    this.busy = false
  }

  // ---------- HTTP ----------

  async listen() {
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(this.port, this.host, () => { this.server.off('error', reject); resolve() })
    })
    this.actualPort = this.server.address().port
    return this.actualPort
  }

  route(req, res) {
    const url = new URL(req.url, 'http://localhost')
    if (url.pathname === '/v1/healthz') {
      return json(res, 200, { ok: true, pid: process.pid })
    }
    if (!authorized(req, this.token)) return json(res, 401, { error: 'unauthorized' })
    if (req.method === 'GET' && url.pathname === '/v1/events') return this.events(res, Number(url.searchParams.get('after') || 0))
    if (req.method === 'POST' && url.pathname === '/v1/commands') return this.command(req, res)
    json(res, 404, { error: 'not found' })
  }

  events(res, after) {
    const from = Number.isFinite(after) && after > 0 ? Math.floor(after) : 0
    if (from > this.seq) return json(res, 409, { error: 'seq ahead', latest: this.seq })
    if (from < this.oldest - 1) return json(res, 409, { error: 'replay buffer overrun', oldest: this.oldest, latest: this.seq })
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    res.write(': connected\n\n')
    for (const rec of this.buffer) if (rec.seq > from) res.write(`id: ${rec.seq}\ndata: ${rec.payload}\n\n`)
    const keepalive = setInterval(() => { try { res.write(': keepalive\n\n') } catch {} }, KEEPALIVE_MS)
    this.clients.add(res)
    const cleanup = () => { clearInterval(keepalive); this.clients.delete(res) }
    res.on('close', cleanup)
    res.on('error', cleanup)
  }

  command(req, res) {
    let size = 0
    const chunks = []
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY) { req.destroy(); return }
      chunks.push(c)
    })
    req.on('end', () => {
      if (res.writableEnded) return
      let cmd
      try { cmd = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { return json(res, 400, { error: '命令不是合法的 JSON' }) }
      if (!cmd || typeof cmd !== 'object' || Array.isArray(cmd)) return json(res, 400, { error: '命令必须是 JSON 对象' })
      if (typeof cmd.cmd !== 'string') return json(res, 400, { error: '缺少 cmd 字段' })
      if (cmd.id === undefined || cmd.id === null || (typeof cmd.id !== 'string' && typeof cmd.id !== 'number')) {
        return json(res, 400, { error: '缺少 id 字段' })
      }
      if (this.closed) return json(res, 409, { error: 'runner 正在退出' })
      json(res, 202, { accepted: true })
      this.accept(cmd)
    })
    req.on('error', () => { try { res.destroy() } catch {} })
  }

  async close() {
    this.closed = true
    for (const c of this.clients) { try { c.end() } catch {} }
    this.clients.clear()
    await new Promise((resolve) => {
      this.server.close(() => resolve())
      setTimeout(resolve, 1000).unref()
    })
  }
}