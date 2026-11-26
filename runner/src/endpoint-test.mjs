// 全栈/单体本地回环接口实测引擎（test_endpoint）与临时服务启动器：
// 严格仅绑定 127.0.0.1 随机空闲端口与临时 DATA_DIR，自动等待就绪、执行多步有状态 HTTP 断言并回收进程。
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { Type } from 'typebox'
import { log, normalizeShellCommand } from './util.mjs'
import { discoverWorkspaceLayout, loadProjectWorkflow } from './workflow.mjs'

const FORBIDDEN_START = /(?:^|[;&|`($\s])(sudo|su|doas|curl|wget|ssh|scp|nc|ncat|netcat|docker|kubectl|rm\s+-rf\s+\/)\b/i

function resolveSafeSubdir(cwd, rel) {
  const target = rel ? path.resolve(cwd, rel) : path.resolve(cwd)
  const r = path.relative(path.resolve(cwd), target)
  if (r.startsWith('..') || path.isAbsolute(r)) throw new Error(`zhiye.yaml 中的工作目录越界：${rel}`)
  return target
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer()
    srv.unref()
    srv.on('error', reject)
    srv.listen(0, '127.0.0.1', () => {
      const addr = srv.address()
      const port = typeof addr === 'object' && addr ? addr.port : 0
      srv.close(() => resolve(port))
    })
  })
}

function readJsonSafe(file) {
  try { return JSON.parse(readFileSync(file, 'utf8')) } catch { return null }
}

function findBuiltJavaJar(dir) {
  if (existsSync(path.join(dir, 'app.jar'))) return path.join(dir, 'app.jar')
  for (const sub of ['target', 'build/libs']) {
    const tdir = path.join(dir, sub)
    if (!existsSync(tdir)) continue
    try {
      const jars = readdirSync(tdir).filter((f) => f.endsWith('.jar') && !f.endsWith('-original.jar') && !f.endsWith('-plain.jar') && !f.endsWith('-sources.jar'))
      if (jars.length) return path.join(tdir, jars[0])
    } catch {}
  }
  return null
}

function detectLaunchSpec(cwd) {
  // 0. 项目自声明工作流（zhiye.yaml -> start.command）拥有最高优先级，不写死任何框架或入口
  const wf = loadProjectWorkflow(cwd)
  if (wf?.start?.command) {
    if (FORBIDDEN_START.test(wf.start.command)) {
      throw new Error(`zhiye.yaml start.command 包含不允许的系统/网络命令：${wf.start.command}`)
    }
    const base = resolveSafeSubdir(cwd, wf.start.cwd)
    return {
      cmd: 'sh',
      args: ['-c', wf.start.command],
      cwd: base,
      label: `zhiye.yaml: ${wf.start.command}`,
      healthPath: wf.start.health || '/api/health',
      timeoutMs: wf.start.timeoutMs || 30_000,
    }
  }

  const layout = discoverWorkspaceLayout(cwd)
  const candDirs = [...new Set([...(layout.backend?.dir ? [layout.backend.dir] : []), '', 'backend', 'server', 'api', 'service', 'app'])]

  // 1. Java / Spring Boot（已构建的可执行 JAR，支持根目录与任意后端子目录）
  for (const dir of candDirs) {
    const base = dir ? path.join(cwd, dir) : cwd
    if (!existsSync(path.join(base, 'pom.xml')) && !existsSync(path.join(base, 'build.gradle')) && !existsSync(path.join(base, 'build.gradle.kts')) && !existsSync(path.join(base, 'app.jar'))) continue
    const jar = findBuiltJavaJar(base)
    if (jar) {
      return { cmd: 'java', args: ['-jar', jar, '--server.port=__PORT__', '--server.address=127.0.0.1'], cwd: base, label: `java -jar ${path.relative(cwd, jar)}`, timeoutMs: 45_000 }
    }
  }
  // 2. Node.js 入口（不限于固定 server/index.mjs，兼容任意子目录）
  for (const dir of candDirs) {
    const base = dir ? path.join(cwd, dir) : cwd
    for (const entry of ['server/index.mjs', 'server/index.js', 'index.mjs', 'server.mjs', 'server.js', 'app.mjs', 'app.js', 'index.js']) {
      if (dir === '' && entry === 'index.js') continue
      const full = path.join(base, entry)
      if (!existsSync(full)) continue
      const rel = path.relative(cwd, full)
      if (rel.startsWith('src/') || rel.startsWith('frontend/') || rel.startsWith('web/')) continue
      return { cmd: process.execPath, args: [full], cwd: base, label: `node ${rel}` }
    }
  }
  // 3. package.json start 脚本（Node 后端）
  for (const dir of candDirs) {
    const base = dir ? path.join(cwd, dir) : cwd
    const pkg = readJsonSafe(path.join(base, 'package.json'))
    if (pkg?.scripts?.start) {
      const start = String(pkg.scripts.start).trim()
      const nodeMatch = /^node\s+([^\s]+)(?:\s+(.*))?$/.exec(start)
      if (nodeMatch) {
        const rest = nodeMatch[2] ? nodeMatch[2].split(/\s+/).filter(Boolean) : []
        return { cmd: process.execPath, args: [nodeMatch[1], ...rest], cwd: base, label: start }
      }
      return { cmd: 'sh', args: ['-c', start], cwd: base, label: start }
    }
  }
  // 4. Python（FastAPI / Flask / Django / 标准 HTTP）
  for (const dir of candDirs) {
    const base = dir ? path.join(cwd, dir) : cwd
    if (existsSync(path.join(base, 'manage.py'))) {
      return { cmd: 'python3', args: ['manage.py', 'runserver', '127.0.0.1:__PORT__', '--noreload'], cwd: base, label: 'django runserver', timeoutMs: 25_000 }
    }
    for (const py of ['main.py', 'app.py', 'server.py']) {
      const full = path.join(base, py)
      if (!existsSync(full)) continue
      const src = readFileSync(full, 'utf8')
      if (/FastAPI\s*\(/.test(src)) {
        const mod = py.replace(/\.py$/, '')
        return { cmd: 'python3', args: ['-m', 'uvicorn', `${mod}:app`, '--host', '127.0.0.1', '--port', '__PORT__'], cwd: base, label: `uvicorn ${mod}:app` }
      }
      return { cmd: 'python3', args: [py], cwd: base, label: `python3 ${py}` }
    }
  }
  // 5. 已编译 Go / Rust 二进制
  for (const dir of candDirs) {
    const base = dir ? path.join(cwd, dir) : cwd
    for (const bin of ['app', 'server', 'target/release/app', 'target/debug/app']) {
      const full = path.join(base, bin)
      if (existsSync(full)) {
        return { cmd: full, args: [], cwd: base, label: path.relative(cwd, full) }
      }
    }
  }
  return null
}

function httpRequest({ port, method = 'GET', reqPath = '/', headers = {}, body = null, timeoutMs = 8000 }) {
  return new Promise((resolve, reject) => {
    const cleanPath = String(reqPath || '/').startsWith('/') ? String(reqPath || '/') : '/' + String(reqPath || '')
    const hdrs = { Accept: 'application/json, text/plain, */*', ...headers }
    let payload = null
    if (body !== null && body !== undefined && !['GET', 'HEAD'].includes(method.toUpperCase())) {
      payload = typeof body === 'string' ? body : JSON.stringify(body)
      if (!Object.keys(hdrs).some((k) => k.toLowerCase() === 'content-type')) {
        hdrs['Content-Type'] = 'application/json'
      }
      hdrs['Content-Length'] = Buffer.byteLength(payload)
    }
    const req = http.request({ host: '127.0.0.1', port, path: cleanPath, method: method.toUpperCase(), headers: hdrs, timeout: timeoutMs }, (res) => {
      const chunks = []
      let total = 0
      res.on('data', (c) => {
        if (total < 256 * 1024) { chunks.push(c); total += c.length }
      })
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let json = null
        try { json = JSON.parse(text) } catch {}
        resolve({ status: res.statusCode || 0, headers: res.headers, text, json })
      })
    })
    req.on('timeout', () => { req.destroy(new Error(`请求超时（${timeoutMs}ms）`)) })
    req.on('error', reject)
    if (payload !== null) req.write(payload)
    req.end()
  })
}

async function waitReady(port, healthPath, proc, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  let lastErr = ''
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) {
      throw new Error(`服务启动后提前退出（exit=${proc.exitCode}）`)
    }
    try {
      const r = await httpRequest({ port, method: 'GET', reqPath: healthPath || '/api/health', timeoutMs: 1500 })
      if (r.status > 0 && r.status < 500) return r.status
      lastErr = `HTTP ${r.status}`
    } catch (e) {
      lastErr = e.message
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error(`服务在 ${timeoutMs}ms 内未就绪（${lastErr || '无响应'}）`)
}

/** 临时拉起全栈/单体后端（隔离 DATA_DIR + 127.0.0.1 随机端口），调用 close() 自动清理。 */
export async function startTempServiceServer(cwd, env = process.env, { healthPath = '/api/health', startupTimeoutMs = 15000 } = {}) {
  const spec = detectLaunchSpec(cwd)
  if (!spec) throw new Error('未能识别可启动的后端入口（需要 server/index.mjs、package.json start 或 main.py）')
  const port = await freePort()
  const dataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-ep-data-'))
  const args = spec.args.map((a) => a.replace(/__PORT__/g, String(port)).replace(/\$\{PORT(?::-\d+)?\}/g, String(port)))
  const layout = discoverWorkspaceLayout(cwd)
  const frontDist = layout.frontendDir ? path.join(cwd, layout.frontendDir, 'dist') : null
  const staticCandidate = (frontDist && existsSync(frontDist)) ? frontDist : existsSync(path.join(cwd, 'frontend', 'dist')) ? path.join(cwd, 'frontend', 'dist') : path.join(cwd, 'dist')
  const effectiveHealth = spec.healthPath || healthPath || '/api/health'
  const logs = []
  const pushLog = (prefix, chunk) => {
    const s = String(chunk || '')
    for (const line of s.split(/\r?\n/)) {
      if (!line.trim()) continue
      if (logs.length >= 200) logs.shift()
      logs.push(`${prefix}${line}`.slice(0, 500))
    }
  }
  const executable = process.platform === 'win32' && spec.cmd === 'python3' ? 'python' : spec.cmd
  const launchArgs = process.platform === 'win32' && spec.cmd === 'sh' && args[0] === '-c'
    ? [args[0], normalizeShellCommand(args[1]), ...args.slice(2)] : args
  const proc = spawn(executable, launchArgs, {
    cwd: spec.cwd,
    env: {
      ...env,
      PORT: String(port),
      SERVER_PORT: String(port),
      SERVER_ADDRESS: '127.0.0.1',
      HOST: '127.0.0.1',
      DATA_DIR: dataDir,
      NODE_ENV: 'test',
      ...(existsSync(staticCandidate) ? { STATIC_DIR: staticCandidate } : {}),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  proc.stdout?.on('data', (c) => pushLog('', c))
  proc.stderr?.on('data', (c) => pushLog('[stderr] ', c))

  const close = async () => {
    try {
      if (proc.exitCode === null) {
        const terminate = (signal) => {
          if (process.platform === 'win32') {
            spawnSync('taskkill', ['/pid', String(proc.pid), '/T', '/F'], { stdio: 'ignore' })
          } else {
            try { proc.kill(signal) } catch {}
          }
        }
        terminate('SIGTERM')
        await new Promise((r) => {
          const t = setTimeout(() => { terminate('SIGKILL'); r() }, 2000)
          proc.once('exit', () => { clearTimeout(t); r() })
        })
      }
    } catch {}
    try { rmSync(dataDir, { recursive: true, force: true }) } catch {}
  }

  const effectiveTimeout = Math.max(startupTimeoutMs, spec.timeoutMs || 0)
  try {
    const readyStatus = await waitReady(port, effectiveHealth, proc, effectiveTimeout)
    return { port, origin: `http://127.0.0.1:${port}`, label: spec.label, readyStatus, healthPath: effectiveHealth, logs, close }
  } catch (e) {
    const tail = logs.slice(-20).join('\n')
    await close()
    throw new Error(`${e.message}${tail ? '\n服务日志：\n' + tail : ''}`)
  }
}

function getJsonPath(obj, dotPath) {
  if (!obj || !dotPath) return undefined
  let cur = obj
  for (const seg of String(dotPath).split('.')) {
    if (cur === null || cur === undefined) return undefined
    cur = Array.isArray(cur) && /^\d+$/.test(seg) ? cur[Number(seg)] : cur[seg]
  }
  return cur
}

function interpolate(val, vars) {
  if (typeof val === 'string') {
    return val.replace(/\{\{([A-Za-z0-9_]+)\}\}/g, (_, k) => (vars[k] !== undefined ? String(vars[k]) : `{{${k}}}`))
  }
  if (Array.isArray(val)) return val.map((x) => interpolate(x, vars))
  if (val && typeof val === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(val)) out[k] = interpolate(v, vars)
    return out
  }
  return val
}

function matchesSubset(actual, expected) {
  if (expected === null || typeof expected !== 'object') return actual === expected
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual)) return false
    return expected.every((item, idx) => matchesSubset(actual[idx], item))
  }
  if (!actual || typeof actual !== 'object') return false
  return Object.entries(expected).every(([k, v]) => matchesSubset(actual[k], v))
}

/** 对临时拉起的后端按顺序执行多步有状态 HTTP 断言（如 POST → GET → PATCH → DELETE）。 */
export async function runEndpointTests(cwd, params = {}, env = process.env) {
  const requests = Array.isArray(params.requests) ? params.requests : []
  if (!requests.length) return { ok: false, summary: 'requests 不能为空，请至少提供 1 个接口测试步骤。', results: [], logs: [] }
  if (requests.length > 20) return { ok: false, summary: '单次最多执行 20 个接口测试步骤。', results: [], logs: [] }

  let srv
  try {
    srv = await startTempServiceServer(cwd, env, { healthPath: params.healthPath || '/api/health' })
  } catch (e) {
    return { ok: false, summary: `无法拉起本地后端服务：${e.message}`, results: [], logs: [] }
  }

  const vars = {}
  const results = []
  let allOk = true
  try {
    for (let i = 0; i < requests.length; i++) {
      const step = requests[i] || {}
      const method = String(step.method || 'GET').toUpperCase()
      const reqPath = interpolate(String(step.path || '/'), vars)
      if (/^https?:\/\//i.test(reqPath)) {
        results.push({ index: i + 1, name: step.name || `${method} ${reqPath}`, ok: false, error: '只允许测试本机相对路径（如 /api/orders），禁止访问外部 URL' })
        allOk = false
        break
      }
      const headers = interpolate(step.headers || {}, vars)
      const body = step.body !== undefined ? interpolate(step.body, vars) : null
      const t0 = Date.now()
      try {
        const res = await httpRequest({ port: srv.port, method, reqPath, headers, body, timeoutMs: 8000 })
        const failures = []
        if (step.expectStatus !== undefined && res.status !== Number(step.expectStatus)) {
          failures.push(`期望状态码 ${step.expectStatus}，实际 ${res.status}`)
        } else if (step.expectStatus === undefined && (res.status < 200 || res.status >= 300)) {
          failures.push(`期望 2xx 状态码，实际 ${res.status}`)
        }
        if (step.expectBodyIncludes && !res.text.includes(String(step.expectBodyIncludes))) {
          failures.push(`响应体未包含「${step.expectBodyIncludes}」`)
        }
        if (step.expectJsonIncludes !== undefined) {
          if (res.json === null) failures.push('响应不是合法 JSON')
          else if (!matchesSubset(res.json, step.expectJsonIncludes)) {
            failures.push(`JSON 未满足预期片段 ${JSON.stringify(step.expectJsonIncludes)}`)
          }
        }
        if (step.saveVar && step.saveJsonPath && res.json !== null) {
          const val = getJsonPath(res.json, step.saveJsonPath)
          if (val !== undefined && val !== null) vars[String(step.saveVar)] = val
        }
        const ok = failures.length === 0
        if (!ok) allOk = false
        results.push({
          index: i + 1,
          name: step.name || `${method} ${reqPath}`,
          method,
          path: reqPath,
          status: res.status,
          durationMs: Date.now() - t0,
          ok,
          error: failures.join('；') || undefined,
          bodySnippet: res.text.slice(0, 400),
        })
        if (!ok && step.stopOnFail !== false) break
      } catch (e) {
        allOk = false
        results.push({ index: i + 1, name: step.name || `${method} ${reqPath}`, method, path: reqPath, durationMs: Date.now() - t0, ok: false, error: e.message })
        break
      }
    }
  } finally {
    await srv.close()
  }

  const passed = results.filter((r) => r.ok).length
  const lines = results.map((r) => `${r.ok ? '✓' : '✗'} [${r.index}] ${r.name} → HTTP ${r.status ?? '-'} (${r.durationMs ?? 0}ms)${r.error ? `\n   失败原因：${r.error}` : ''}${!r.ok && r.bodySnippet ? `\n   响应片段：${r.bodySnippet}` : ''}`)
  const logTail = srv.logs.slice(-25).join('\n')
  const summary = `${allOk ? '全部接口实测通过' : '接口实测未通过'}（${passed}/${results.length}）\n${lines.join('\n')}${!allOk && logTail ? `\n\n### 服务运行日志（最近 25 行）\n${logTail}` : ''}`
  log(`test_endpoint ${passed}/${results.length} ok=${allOk}`)
  return { ok: allOk, summary, results, logs: srv.logs.slice(-40) }
}

export function createEndpointTestExtension({ cwd, projectKind, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'test_endpoint',
      label: '实测后端接口',
      description: '临时拉起当前全栈/单体后端（隔离 127.0.0.1 随机端口与临时 DATA_DIR），按顺序执行一组真实 HTTP 接口断言（支持通过 saveVar/saveJsonPath 和 {{var}} 串联 POST→GET→PATCH→DELETE 持久化闭环），自动回收进程并返回响应与日志。',
      parameters: Type.Object({
        healthPath: Type.Optional(Type.String({ description: '健康检查路径，默认 /api/health' })),
        requests: Type.Array(Type.Object({
          name: Type.Optional(Type.String({ description: '步骤名称，如「创建工单」' })),
          method: Type.String({ description: 'HTTP 方法：GET / POST / PUT / PATCH / DELETE' }),
          path: Type.String({ description: '相对路径，如 /api/items 或 /api/items/{{itemId}}' }),
          headers: Type.Optional(Type.Record(Type.String(), Type.String())),
          body: Type.Optional(Type.Any({ description: 'JSON 对象或字符串请求体' })),
          expectStatus: Type.Optional(Type.Integer({ description: '期望状态码，默认校验 2xx' })),
          expectBodyIncludes: Type.Optional(Type.String({ description: '期望响应文本包含的子串' })),
          expectJsonIncludes: Type.Optional(Type.Any({ description: '期望响应 JSON 包含的子集对象' })),
          saveVar: Type.Optional(Type.String({ description: '从响应 JSON 提取并保存的变量名，后续步骤可用 {{变量名}}' })),
          saveJsonPath: Type.Optional(Type.String({ description: '点分路径，如 id 或 data.id' })),
          stopOnFail: Type.Optional(Type.Boolean({ description: '该步失败后是否中止后续步骤，默认 true' })),
        }), { minItems: 1, maxItems: 20 }),
      }),
      async execute(_id, params) {
        if (projectKind !== 'service') {
          return { content: [{ type: 'text', text: 'test_endpoint 仅适用于全栈/单体后端项目；前端/云函数应用请使用 browser_test 和 verify_app。' }], details: { ok: false } }
        }
        const r = await runEndpointTests(cwd, params || {}, env)
        return { content: [{ type: 'text', text: r.summary }], details: { ok: r.ok, passed: r.results.filter((x) => x.ok).length, total: r.results.length } }
      },
    })
  }
}
