// 公共工具：stdout 协议输出、stderr 日志、节流的 log 事件、子进程执行。
import { spawn } from 'node:child_process'

let writer = (obj) => process.stdout.write(JSON.stringify(obj) + '\n')
let logSink = null
export function setWriter(fn) { writer = fn }
/** 内部日志除写 stderr 外，也通过控制通道发给主服务（远端沙箱里 stderr 拿不到）。 */
export function setLogSink(fn) { logSink = fn }
export function send(obj) { writer(obj) }
export function emitEvent(event) { send({ type: 'event', event }) }
export function log(...args) {
  const line = `[runner ${new Date().toISOString()}] ${args.map((a) => (typeof a === 'string' ? a : a instanceof Error ? a.stack : JSON.stringify(a))).join(' ')}`
  process.stderr.write(line + '\n')
  if (logSink) { try { logSink(line) } catch {} }
}

export class RunnerError extends Error {
  constructor(code, message, extra) { super(message); this.code = code; if (extra) Object.assign(this, extra) }
}

/** 模型调用错误分类：与服务端 KimiClient.codeFor 保持同一套规则。 */
export function classifyError(msg) {
  const m = String(msg || '')
  // 每分钟限流（TPM/RPM）：临时限流，不是额度用完，单独错误码避免误导
  if (/tokens?\s*per\s*minute|requests?\s*per\s*minute|\btpm\b|\brpm\b/i.test(m)) return 'PROVIDER_RATE_LIMIT'
  // 供应商配额：上游 429 / usage limit / current_quota，和任务自身的 token 预算无关。
  // 429 要求与配额类词同现，避免把 "1429000 tokens" 这类文本误判成配额错误
  if (/usage limit|current_quota|rate.?limit/i.test(m)) return 'PROVIDER_QUOTA'
  if (/\b429\b/.test(m) && /quota|limit|usage|rate|exceed/i.test(m)) return 'PROVIDER_QUOTA'
  // 任务预算：网关用 402 + insufficient_quota 表达「本任务 token 预算已用完」
  if (/insufficient_quota/i.test(m)) return /token|budget|预算/i.test(m) ? 'BUDGET_EXCEEDED' : 'PROVIDER_QUOTA'
  if (/budget|预算/i.test(m)) return 'BUDGET_EXCEEDED'
  // 其余 402（例如 DeepSeek 余额不足 Insufficient Balance）是供应商账户额度，不是任务预算
  if (/\b402\b|insufficient balance/i.test(m)) return 'PROVIDER_QUOTA'
  return 'MODEL_ERROR'
}

/** log 事件节流：每秒最多 5 条，多余的合并，最后补发。 */
const LOG_LIMIT = 5
let windowStart = 0
let inWindow = 0
let pending = []
let flushTimer = null
export function emitLog(line) {
  line = String(line).replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').trimEnd()
  if (!line.trim()) return
  if (line.length > 500) line = line.slice(0, 500) + '…'
  const now = Date.now()
  if (now - windowStart >= 1000) { windowStart = now; inWindow = 0 }
  if (inWindow < LOG_LIMIT && !pending.length) { inWindow++; emitEvent({ type: 'log', line }); return }
  pending.push(line)
  if (!flushTimer) flushTimer = setTimeout(flushLogs, 1000 - (now - windowStart) + 5)
}
export function flushLogs() {
  if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
  if (!pending.length) return
  const lines = pending
  pending = []
  windowStart = Date.now(); inWindow = 1
  // 合并成一条，避免丢失（保留最后 20 行）
  const kept = lines.length > 20 ? [`…（省略 ${lines.length - 20} 行）`, ...lines.slice(-20)] : lines
  emitEvent({ type: 'log', line: kept.join('\n') })
}

export function normalizeShellCommand(command) {
  const text = String(command || '')
  return process.platform === 'win32' ? text.replace(/\bpython3\b/g, 'python') : text
}

/**
 * 执行命令，收集输出。返回 { code, out, timedOut, signal }。
 * onLine：每行输出的回调（用于 log 事件）。
 */
export function run(cmd, args, { cwd, env, timeoutMs = 300000, onLine, input, signal } = {}) {
  return new Promise((resolve) => {
    let out = ''
    let timedOut = false
    let child
    try {
      const needsShell = process.platform === 'win32' && ['npm', 'npm.cmd', 'npx', 'npx.cmd', 'pnpm', 'pnpm.cmd', 'yarn', 'yarn.cmd'].includes(cmd)
      const executable = process.platform === 'win32' && cmd === 'python3' ? 'python' : cmd
      const actualArgs = process.platform === 'win32' && cmd === 'sh' && args[0] === '-c'
        ? [args[0], normalizeShellCommand(args[1]), ...args.slice(2)] : args
      child = spawn(executable, actualArgs, { cwd, env: env || process.env, stdio: ['pipe', 'pipe', 'pipe'], detached: process.platform !== 'win32', shell: needsShell })
    } catch (e) {
      resolve({ code: -1, out: String(e.message), timedOut: false })
      return
    }
    const kill = () => { try { process.kill(-child.pid, 'SIGKILL') } catch { try { child.kill('SIGKILL') } catch {} } }
    const timer = setTimeout(() => { timedOut = true; kill() }, timeoutMs)
    const onAbort = () => kill()
    signal?.addEventListener('abort', onAbort)
    let partial = ''
    const onData = (buf) => {
      const s = buf.toString('utf8')
      if (out.length < 2_000_000) out += s
      if (onLine) {
        partial += s
        const lines = partial.split(/\r?\n/)
        partial = lines.pop()
        for (const l of lines) onLine(l)
      }
    }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.on('error', (e) => { out += `\n${e.message}` })
    child.on('close', (code, sig) => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      if (onLine && partial) onLine(partial)
      resolve({ code: code ?? -1, out, timedOut, signal: sig })
    })
    if (input) child.stdin.end(input); else child.stdin.end()
  })
}

/** 报错摘要：去掉 ANSI，截断到 max 字符（保留头尾）。 */
export function summarize(text, max = 4000) {
  let s = String(text || '').replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').trim()
  if (s.length <= max) return s
  const head = Math.floor(max * 0.6)
  return s.slice(0, head) + '\n…（中间省略）…\n' + s.slice(s.length - (max - head - 20))
}
