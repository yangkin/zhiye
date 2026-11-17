// Pi 会话封装：init / prompt / abort。
import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import {
  createAgentSession,
  DefaultResourceLoader,
  ModelRuntime,
  SessionManager,
  SettingsManager,
} from '@earendil-works/pi-coding-agent'
import { createGuardExtension } from './guard.mjs'
import { CLOUD_USAGE, systemPrompt } from './prompts.mjs'
import { createConfirmExtension, createScreenshotExtension, createTodoExtension, snapshotBefore, stepDetail, toolSummary, ThinkingStream } from './steps.mjs'

export { toolSummary }
import { emitEvent, log, RunnerError } from './util.mjs'

const THINKING = { off: 'low', minimal: 'low', low: 'low', medium: 'high', high: 'high', xhigh: 'max', max: 'max' }

/**
 * 写 agentDir/models.json：把 provider 的 baseUrl 改为网关地址（保留已有内容）。
 * model 传入时整条覆盖内置模型定义（用于改 contextWindow），所以必须是完整定义，缺字段会退回默认值。
 */
export function writeModelsJson(agentDir, provider, baseUrl, model) {
  const file = path.join(agentDir, 'models.json')
  let cfg = {}
  try { cfg = JSON.parse(readFileSync(file, 'utf8')) } catch {}
  cfg.providers = cfg.providers || {}
  cfg.providers[provider] = { ...(cfg.providers[provider] || {}), baseUrl }
  if (model) cfg.providers[provider].models = [model]
  writeFileSync(file, JSON.stringify(cfg, null, 2))
}

/**
 * 模型定义里的运行时字段（provider / baseUrl）写进 models.json 会互相打架，剥掉后再落盘。
 * 深拷贝一份，避免改动传入的内置定义。
 */
export function stripRuntimeFields(model) {
  const { provider: _provider, baseUrl: _baseUrl, ...definition } = JSON.parse(JSON.stringify(model))
  return definition
}

/**
 * 解析 ZHIYE_MODEL_DEF：一整条自定义模型定义（JSON），用于上游模型不在 Pi 内置清单里的情况
 * （例如公司中转的 Coding-Infra-pd-router/... 路由名）。未配置时返回 null；配置但不合法直接报错，
 * 防止悄悄退回内置默认定义（maxTokens / reasoning 静默丢失）。
 */
export function modelDefFromEnv(raw) {
  if (!raw || !raw.trim()) return null
  let def
  try { def = JSON.parse(raw) } catch {
    throw new RunnerError('BAD_REQUEST', 'ZHIYE_MODEL_DEF 不是合法 JSON')
  }
  if (!def || typeof def.id !== 'string' || typeof def.api !== 'string')
    throw new RunnerError('BAD_REQUEST', 'ZHIYE_MODEL_DEF 必须是含 id / api 的模型定义')
  return stripRuntimeFields(def)
}

/**
 * 把模型定义里的 contextWindow 改成 want。
 * models.json 的自定义条目会整体替换内置定义，所以先拷一份内置定义再改这一个字段，
 * 否则 reasoning / maxTokens / thinkingLevelMap 会退回默认值（推理和思考档位会一起丢）。
 */
export function contextWindowOverride(model, want) {
  return { ...stripRuntimeFields(model), contextWindow: want }
}

function classifyError(msg) {
  const m = String(msg || '')
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

export class Agent {
  constructor() {
    this.toolState = new Map()
    this.session = null
    this.running = null
    this.abortRequested = false
    this.confirmWaiters = new Map()
  }

  async init(cmd, { cwd, env = process.env }) {
    if (this.session) throw new RunnerError('BAD_STATE', '会话已经初始化')
    const agentDir = cmd.agentDir
    if (!agentDir || typeof agentDir !== 'string') throw new RunnerError('BAD_REQUEST', '缺少 agentDir')
    const provider = cmd.model?.provider || 'kimi-coding'
    const modelId = cmd.model?.id || 'kimi-for-coding'
    this.projectKind = cmd.projectKind || 'template'
    this.maxTurns = Number(cmd.maxTurns) > 0 ? Number(cmd.maxTurns) : 60
    this.cwd = cwd
    this.thinking = new ThinkingStream({ cwd, emit: emitEvent })
    const gateway = env.ZHIYE_GATEWAY_URL
    const token = env.ZHIYE_TASK_TOKEN
    if (!gateway) throw new RunnerError('BAD_REQUEST', '缺少环境变量 ZHIYE_GATEWAY_URL')
    if (!token) throw new RunnerError('BAD_REQUEST', '缺少环境变量 ZHIYE_TASK_TOKEN')

    mkdirSync(agentDir, { recursive: true })
    const sessionsDir = path.join(agentDir, 'sessions')
    mkdirSync(sessionsDir, { recursive: true })
    writeModelsJson(agentDir, provider, gateway)
    // 自定义模型定义（ZHIYE_MODEL_DEF）：上游模型名不在 Pi 内置清单时整条覆写 providers[provider].models
    const customDef = modelDefFromEnv(env.ZHIYE_MODEL_DEF)
    if (customDef) writeModelsJson(agentDir, provider, gateway, customDef)

    const createRuntime = () => ModelRuntime.create({ authPath: path.join(agentDir, 'auth.json'), modelsPath: path.join(agentDir, 'models.json') })
    let modelRuntime = await createRuntime()
    await modelRuntime.setRuntimeApiKey(provider, token)
    let model = modelRuntime.getModel(provider, modelId)
    if (!model) throw new RunnerError('BAD_REQUEST', `找不到模型 ${provider}/${modelId}`)
    // 部署侧可以压低上下文窗口（ZHIYE_MODEL_CONTEXT_WINDOW / init 命令里的 model.contextWindow），
    // 例如把 1M 的内置窗口收到 256k，让 Pi 更早压缩、单次请求更省
    const wantContext = Number(cmd.model?.contextWindow || env.ZHIYE_MODEL_CONTEXT_WINDOW || 0)
    if (wantContext > 0 && wantContext !== model.contextWindow) {
      writeModelsJson(agentDir, provider, gateway, contextWindowOverride(model, wantContext))
      modelRuntime = await createRuntime()
      await modelRuntime.setRuntimeApiKey(provider, token)
      model = modelRuntime.getModel(provider, modelId)
      if (!model) throw new RunnerError('INTERNAL', `改上下文窗口后找不到模型 ${provider}/${modelId}`)
    }
    log(`model ${model.provider}/${model.id} api=${model.api} baseUrl=${model.baseUrl} context=${model.contextWindow} maxTokens=${model.maxTokens}`)

    const settingsManager = SettingsManager.inMemory({
      compaction: { enabled: true },
      retry: { enabled: true, maxRetries: 1 },
      enableInstallTelemetry: false,
      enableAnalytics: false,
      cacheWarming: 'off',
      quietStartup: true,
    })
    const withShot = ['template', 'vue', 'static', 'vite'].includes(this.projectKind)
    const resourceLoader = new DefaultResourceLoader({
      cwd,
      agentDir,
      settingsManager,
      noExtensions: true,
      noSkills: true,
      noPromptTemplates: true,
      noThemes: true,
      noContextFiles: true,
      disabledBuiltinExtensions: ['mcp'],
      systemPrompt: systemPrompt(this.projectKind, cmd.cloudEnabled),
      extensionFactories: [
        { name: 'zhiye-guard', factory: createGuardExtension({ cwd, projectKind: this.projectKind, agentDir, log }) },
        { name: 'zhiye-todo', factory: createTodoExtension({ cwd, onTodo: (items) => this.onTodo(items) }) },
        { name: 'zhiye-confirm', factory: createConfirmExtension({ emit: emitEvent, waiters: this.confirmWaiters, onAccepted: () => '云服务用法：\n' + CLOUD_USAGE }) },
        ...(withShot ? [{ name: 'zhiye-screenshot', factory: createScreenshotExtension({ cwd, projectKind: this.projectKind }) }] : []),
      ],
      // 只保留织页自己的内联扩展，防止任何发现机制带进别的扩展
      extensionsOverride: (base) => ({ ...base, extensions: base.extensions.filter((e) => ['<inline:zhiye-guard>', '<inline:zhiye-todo>', '<inline:zhiye-confirm>', '<inline:zhiye-screenshot>'].includes(e.path)) }),
      skillsOverride: () => ({ skills: [], diagnostics: [] }),
      promptsOverride: () => ({ prompts: [], diagnostics: [] }),
      agentsFilesOverride: () => ({ agentsFiles: [] }),
      appendSystemPromptOverride: () => [],
    })
    await resourceLoader.reload()
    const exts = resourceLoader.getExtensions()
    if (exts.errors.length) log('extension errors', exts.errors)
    if (exts.extensions.length !== (withShot ? 4 : 3)) throw new RunnerError('INTERNAL', `织页扩展加载失败（${exts.extensions.length}）`)

    let sessionManager
    if (cmd.sessionFile) {
      if (!existsSync(cmd.sessionFile)) throw new RunnerError('BAD_REQUEST', `会话文件不存在：${cmd.sessionFile}`)
      sessionManager = SessionManager.open(cmd.sessionFile, sessionsDir, cwd)
    } else {
      sessionManager = SessionManager.create(cwd, sessionsDir)
    }

    const { session } = await createAgentSession({
      cwd,
      agentDir,
      model,
      thinkingLevel: THINKING[cmd.thinking] || 'low',
      modelRuntime,
      resourceLoader,
      tools: ['read', 'write', 'edit', 'bash', 'todo_write', ...(this.projectKind === 'skill' ? [] : ['request_confirm']), ...(withShot ? ['screenshot'] : [])],
      sessionManager,
      settingsManager,
    })
    this.session = session
    session.subscribe((e) => this.onEvent(e))
    log(`session ${session.sessionId} file=${session.sessionFile} tools=${session.getActiveToolNames().join(',')}`)
    return { sessionFile: session.sessionFile }
  }

  onEvent(e) {
    const st = this.running
    switch (e.type) {
      case 'turn_start':
        if (!st) return
        st.turns++
        emitEvent({ type: 'turn', n: st.turns })
        if (st.turns > this.maxTurns && !st.maxTurnsHit) {
          st.maxTurnsHit = true
          log(`max turns ${this.maxTurns} reached, aborting`)
          this.session.abort().catch(() => {})
        }
        break
      case 'message_update': {
        const ae = e.assistantMessageEvent
        if (ae?.type === 'text_delta' && ae.delta) emitEvent({ type: 'agent_text', delta: ae.delta })
        else if (ae?.type === 'thinking_start') this.thinking.start()
        else if (ae?.type === 'thinking_delta') this.thinking.delta(ae.delta)
        else if (ae?.type === 'thinking_end') this.thinking.end()
        break
      }
      case 'message_end':
        this.thinking.end() // 中途出错或被停止时也收尾
        break
      case 'tool_execution_start': {
        let snap = null
        try { snap = snapshotBefore(this.cwd, e.toolName, e.args) } catch (err) { log('snapshot failed', err) }
        this.toolState.set(e.toolCallId, { args: e.args || {}, snap })
        emitEvent({ type: 'tool', id: e.toolCallId, name: e.toolName, summary: toolSummary(this.cwd, e.toolName, e.args || {}, { created: snap ? !snap.existed : undefined }), state: 'start' })
        break
      }
      case 'tool_execution_end': {
        const ts = this.toolState.get(e.toolCallId) || { args: {}, snap: null }
        this.toolState.delete(e.toolCallId)
        let detail = null, counts = ''
        try { ({ detail, counts } = stepDetail(this.cwd, e.toolName, ts.args, ts.snap, e.result, e.isError)) } catch (err) { log('step detail failed', err) }
        const summary = toolSummary(this.cwd, e.toolName, ts.args, { created: ts.snap ? !ts.snap.existed : undefined }) + counts
        emitEvent({ type: 'tool', id: e.toolCallId, name: e.toolName, summary, state: 'end', ok: !e.isError, ...(detail ? { detail } : {}) })
        break
      }
      case 'auto_retry_start':
        log(`auto retry ${e.attempt}/${e.maxAttempts}: ${e.errorMessage}`)
        break
      case 'agent_settled':
        if (st) st.settledAborted = e.aborted
        break
      default:
    }
  }

  async prompt(cmd) {
    if (!this.session) throw new RunnerError('BAD_STATE', '请先执行 init')
    if (typeof cmd.text !== 'string' || !cmd.text.trim()) throw new RunnerError('BAD_REQUEST', '缺少指令文字 text')
    const st = { turns: 0, maxTurnsHit: false, settledAborted: false }
    this.running = st
    this.abortRequested = false
    const before = this.session.messages.length
    try {
      await this.session.prompt(cmd.text)
    } catch (e) {
      if (!this.abortRequested) throw new RunnerError(classifyError(e.message), `模型调用失败：${e.message}`)
    } finally {
      this.running = null
    }
    const msgs = this.session.messages.slice(before)
    const lastAssistant = [...msgs].reverse().find((m) => m.role === 'assistant')
    const aborted = this.abortRequested || (!st.maxTurnsHit && (st.settledAborted || lastAssistant?.stopReason === 'aborted'))
    if (!aborted && !st.maxTurnsHit && lastAssistant?.stopReason === 'error') {
      const m = lastAssistant.errorMessage || '未知错误'
      throw new RunnerError(classifyError(m), `模型调用失败：${m}`, { turns: st.turns })
    }
    let text = ''
    if (lastAssistant && Array.isArray(lastAssistant.content)) text = lastAssistant.content.filter((c) => c.type === 'text').map((c) => c.text).join('').trim()
    return {
      text,
      turns: st.turns,
      aborted,
      // 结构化错误码：服务端据此提示「需求太大、已保留进度」，不要当成模型故障
      error: st.maxTurnsHit ? { code: 'TURN_LIMIT', message: `超过最大轮数 ${this.maxTurns}，已停止` } : null,
    }
  }

  onTodo(items) {
    emitEvent({ type: 'todo', items })
  }

  /** 运行中追加用户要求：在当前轮的工具调用结束后、下一次请求模型前送达 */
  async steer(cmd) {
    if (!this.session) throw new RunnerError('BAD_STATE', '请先执行 init')
    if (typeof cmd.text !== 'string' || !cmd.text.trim()) throw new RunnerError('BAD_REQUEST', '缺少追加内容 text')
    if (!this.running) throw new RunnerError('BAD_STATE', '当前没有正在运行的 prompt')
    const status = await this.session.steer(cmd.text)
    log(`steer → ${status}`)
    return { status }
  }

  /** 平台转来的用户选择（确认卡）：{ confirmId, accept, note? }（id 字段是命令自己的编号） */
  confirm(cmd) {
    const w = this.confirmWaiters.get(cmd?.confirmId)
    if (!w) throw new RunnerError('BAD_STATE', '没有等待中的确认：' + cmd?.confirmId)
    w({ accept: !!cmd.accept, note: typeof cmd.note === 'string' ? cmd.note.slice(0, 2000) : '' })
    return {}
  }

  async abort() {
    this.abortRequested = true
    if (this.session) await this.session.abort()
  }

  get busy() { return !!this.running }

  dispose() {
    try { this.session?.dispose() } catch {}
    this.session = null
  }
}
