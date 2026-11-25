#!/usr/bin/env node
// 织页 agent-runner：HTTP 控制通道（见 docs/runner-protocol.md）。
// stdout 不再承载协议，统一转到 stderr；事件和回复都从 SSE 流发出。
import { log, RunnerError, flushLogs, setWriter, setLogSink } from './util.mjs'
import { ControlServer } from './control-server.mjs'

// 防止任何依赖往 stdout 打印东西：stdout 一律转到 stderr。
process.stdout.write = (chunk, ...rest) => process.stderr.write(chunk, ...rest)
for (const k of ['log', 'info', 'warn', 'error', 'debug', 'trace']) console[k] = (...a) => log(`[console.${k}]`, ...a.map((x) => (typeof x === 'string' ? x : String(x?.stack || JSON.stringify(x)))))

const { Agent } = await import('./agent.mjs')
const { verify } = await import('./verify.mjs')
const { applyVisualPatches } = await import('./visual.mjs')
const { importArchive, installDeps } = await import('./importer.mjs')
const { runQaReview } = await import('./qa.mjs')
const { generateArchitectureSummary } = await import('./code-intel.mjs')

const token = process.env.ZHIYE_RUNNER_TOKEN
if (!token) {
  process.stderr.write('[runner] 缺少 ZHIYE_RUNNER_TOKEN，拒绝启动\n')
  process.exit(2)
}

const cwd = process.cwd()
const agent = new Agent()
let currentPrompt = null
let shuttingDown = false

const HANDLERS = {
  init: (c) => agent.init(c, { cwd }),
  prompt: async (c) => {
    currentPrompt = agent.prompt(c)
    try { return await currentPrompt } finally { currentPrompt = null }
  },
  verify: (c) => verify(cwd, c),
  qa_review: (c) => runQaReview(c, { cwd }),
  arch_summary: (c) => ({ markdown: generateArchitectureSummary(cwd, c?.projectKind || 'template') }),
  visual: (c) => {
    if (c.projectKind === 'skill') throw new RunnerError('BAD_REQUEST', '技能项目不支持可视化编辑')
    if (c.projectKind && c.projectKind !== 'template' && c.projectKind !== 'static') throw new RunnerError('BAD_REQUEST', '这个项目类型不支持可视化编辑')
    const { applied, failures, locMap } = applyVisualPatches(cwd, c.patches)
    return { applied, failures, locMap }
  },
  import: (c) => importArchive(cwd, c),
  install: () => installDeps(cwd),
  shutdown: async () => {
    shuttingDown = true
    if (agent.busy) await agent.abort().catch(() => {})
    return {}
  },
}

async function handleAbort() {
  try {
    await agent.abort()
    if (currentPrompt) await currentPrompt.catch(() => {})
    return {}
  } catch (e) {
    throw new RunnerError('INTERNAL', `停止失败：${e.message}`)
  }
}

async function handleSteer(cmd) {
  return agent.steer(cmd)
}

const handlers = {
  ...HANDLERS,
  abort: handleAbort,
  steer: handleSteer,
  confirm: (c) => agent.confirm(c),
  // abort/steer/confirm 必须在 prompt 执行期间插入，因此放在 bypass 集合里
}

const server = new ControlServer({
  token,
  host: process.env.ZHIYE_RUNNER_HOST || '127.0.0.1',
  port: Number(process.env.ZHIYE_RUNNER_PORT || 0),
  handlers,
  bypass: ['abort', 'steer', 'confirm'],
  beforeReply: () => flushLogs(),
  onShutdown: () => { exit(0) },
})

setWriter((obj) => server.publish(obj))
setLogSink((line) => server.log(line))

let exiting = false
async function exit(code) {
  if (exiting) return
  exiting = true
  shuttingDown = true
  try { if (agent.busy) await agent.abort() } catch {}
  try { agent.dispose() } catch {}
  flushLogs()
  await server.close()
  process.exit(code)
}

process.on('uncaughtException', (e) => {
  log('uncaughtException', e)
  try { server.replyError(null, 'INTERNAL', `runner 内部错误：${e?.message || e}`) } catch {}
  setTimeout(() => process.exit(1), 50)
})
process.on('unhandledRejection', (e) => {
  log('unhandledRejection', e)
  try { server.replyError(null, 'INTERNAL', `runner 内部错误：${e?.message || e}`) } catch {}
  setTimeout(() => process.exit(1), 50)
})
process.on('SIGTERM', () => { log('SIGTERM'); exit(143) })
process.on('SIGINT', () => { log('SIGINT'); exit(130) })

const port = await server.listen()
process.stderr.write(`ZHIYE_RUNNER_READY port=${port}\n`)
log(`runner started pid=${process.pid} cwd=${cwd} node=${process.version} port=${port}`)