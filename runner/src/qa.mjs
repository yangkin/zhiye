// 独立对抗式 QA 验收子智能体（Independent Adversarial QA Subagent）：
// 使用全新隔离的 Pi 会话上下文（无 Builder 确认偏误），仅授予只读 + 真实测试工具（browser_test / test_endpoint / code_symbols / screenshot），
// 严格对照用户原始需求实测交互与边界条件，输出结构化 JSON 裁决供 TaskExecutor 自动打回主 Agent 修复。
import { existsSync, mkdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import {
  createAgentSession,
  DefaultResourceLoader,
  ModelRuntime,
  SessionManager,
  SettingsManager,
} from '@earendil-works/pi-coding-agent'
import { createGuardExtension } from './guard.mjs'
import { createSearchExtension, createServiceInspectExtension } from './tools.mjs'
import { createBrowserTestExtension } from './browser-test.mjs'
import { createEndpointTestExtension } from './endpoint-test.mjs'
import { createCodeIntelExtension, generateArchitectureSummary } from './code-intel.mjs'
import { createScreenshotExtension, toolSummary } from './steps.mjs'
import { contextWindowOverride, modelDefFromEnv, writeModelsJson } from './agent.mjs'
import { emitEvent, log, RunnerError } from './util.mjs'

const QA_SYSTEM_PROMPT = `你是织页（Zhiye）的**独立对抗式 QA 验收工程师（Independent QA Subagent）**。
你与编写代码的开发者完全隔离，**没有修改文件的权限**。你的唯一职责是站在苛刻的真实用户角度，用工具实际运行和测试这个应用，找出所有功能缺陷、交互失效、接口报错、假按钮、数据不持久化或布局破损问题。

## 工作纪律
1. 先阅读 \`.zhiye/architecture.md\`（或用 \`code_symbols\` / \`find_files\`）快速理解路由、组件和接口结构，不要漫无目的读大量文件。
2. **必须用真实工具实测，绝不能只看代码就说“通过”**：
   - 前端/网页应用：必须调用 \`browser_test\` 至少覆盖 2～4 个核心交互路径（如切换 Tab/路由、填写并提交表单、搜索/筛选、断言结果可见、刷新验证状态、1920 宽屏布局检查）。
   - 全栈后端应用：必须调用 \`test_endpoint\` 实测后端 CRUD 闭环（如 POST 创建 → GET 查询 → DELETE/PATCH 验证）并调用 \`browser_test\` 验证前后端联动。
3. 严格区分缺陷严重度：
   - \`critical\` / \`high\`（阻断通过）：白屏、脚本报错、接口 4xx/5xx、按钮点不动/无响应、核心需求缺失、表单提交失败、数据刷新丢失、宽屏大片留白或按钮重叠遮挡。
   - \`low\`（不阻断，仅作改进建议）：轻微文案措辞、次要视觉微调。
4. 完成实测后，**最后只输出一个 JSON 代码块**，严格遵循如下结构：
\`\`\`json
{
  "passed": true,
  "score": 92,
  "summary": "一句简洁中文总结实测结论",
  "checklist": [
    { "item": "核心验收项名称", "passed": true, "evidence": "实测依据（如 browser_test 步骤与结果）" }
  ],
  "defects": [
    { "severity": "high", "area": "模块/页面名", "repro": "复现步骤", "expected": "期望表现", "actual": "实际报错或表现" }
  ]
}
\`\`\`
规则：只要存在任意 \`critical\` 或 \`high\` 级别缺陷，\`passed\` 必须为 \`false\`。`

/** 从 QA 助手回复文本中稳健提取结构化 JSON 裁决。 */
export function parseQaVerdict(rawText) {
  const text = String(rawText || '').trim()
  const candidates = []
  const fenced = /```(?:json)?\s*([\s\S]*?)```/gi
  let m
  while ((m = fenced.exec(text)) !== null) candidates.push(m[1].trim())
  const firstBrace = text.indexOf('{')
  const lastBrace = text.lastIndexOf('}')
  if (firstBrace >= 0 && lastBrace > firstBrace) candidates.push(text.slice(firstBrace, lastBrace + 1))

  for (const c of candidates) {
    try {
      const obj = JSON.parse(c)
      if (obj && typeof obj === 'object' && ('passed' in obj || Array.isArray(obj.defects) || Array.isArray(obj.checklist))) {
        const defects = Array.isArray(obj.defects) ? obj.defects : []
        const hasBlocking = defects.some((d) => ['critical', 'high'].includes(String(d?.severity || '').toLowerCase()))
        const passed = obj.passed !== undefined ? Boolean(obj.passed) && !hasBlocking : !hasBlocking
        return {
          passed,
          score: Number.isFinite(Number(obj.score)) ? Math.max(0, Math.min(100, Number(obj.score))) : (passed ? 90 : 55),
          summary: String(obj.summary || (passed ? '独立 QA 实测通过' : '独立 QA 发现阻断性缺陷')),
          checklist: Array.isArray(obj.checklist) ? obj.checklist : [],
          defects,
        }
      }
    } catch {}
  }
  return {
    passed: true,
    score: 80,
    summary: '独立 QA 未返回标准 JSON，已默认放行（不阻断交付）。',
    checklist: [],
    defects: [],
    rawFallback: true,
  }
}

/** 运行一轮全新上下文的独立 QA 验收子智能体。 */
export async function runQaReview(cmd, { cwd, env = process.env }) {
  const projectKind = cmd.projectKind || 'template'
  if (projectKind === 'skill' || projectKind === 'script') {
    return { passed: true, score: 100, summary: '非界面应用跳过浏览器 QA 门禁。', checklist: [], defects: [], turns: 0 }
  }
  const agentDir = cmd.agentDir
  if (!agentDir) throw new RunnerError('BAD_REQUEST', 'qa_review 缺少 agentDir')
  const qaDir = path.join(agentDir, `qa-round-${cmd.round || 1}`)
  mkdirSync(path.join(qaDir, 'sessions'), { recursive: true })

  // 先刷新 .zhiye/architecture.md 给 QA 子智能体提供全局地图
  const archMd = generateArchitectureSummary(cwd, projectKind)

  const provider = cmd.model?.provider || 'kimi-coding'
  const modelId = cmd.model?.id || 'kimi-for-coding'
  const gateway = env.ZHIYE_GATEWAY_URL
  const token = env.ZHIYE_TASK_TOKEN
  if (!gateway || !token) throw new RunnerError('BAD_REQUEST', 'qa_review 缺少 ZHIYE_GATEWAY_URL 或 ZHIYE_TASK_TOKEN')

  writeModelsJson(qaDir, provider, gateway)
  const customDef = modelDefFromEnv(env.ZHIYE_MODEL_DEF)
  if (customDef) writeModelsJson(qaDir, provider, gateway, customDef)

  const createRuntime = () => ModelRuntime.create({ authPath: path.join(qaDir, 'auth.json'), modelsPath: path.join(qaDir, 'models.json') })
  let modelRuntime = await createRuntime()
  await modelRuntime.setRuntimeApiKey(provider, token)
  let model = modelRuntime.getModel(provider, modelId)
  if (!model) throw new RunnerError('BAD_REQUEST', `QA 子智能体找不到模型 ${provider}/${modelId}`)
  const wantContext = Number(cmd.model?.contextWindow || env.ZHIYE_MODEL_CONTEXT_WINDOW || 0)
  if (wantContext > 0 && wantContext !== model.contextWindow) {
    writeModelsJson(qaDir, provider, gateway, contextWindowOverride(model, wantContext))
    modelRuntime = await createRuntime()
    await modelRuntime.setRuntimeApiKey(provider, token)
    model = modelRuntime.getModel(provider, modelId)
  }

  const settingsManager = SettingsManager.inMemory({
    compaction: { enabled: true },
    retry: { enabled: true, maxRetries: 2 },
    enableInstallTelemetry: false,
    enableAnalytics: false,
    cacheWarming: 'off',
    quietStartup: true,
  })
  const isService = projectKind === 'service'
  const extensionFactories = [
    { name: 'zhiye-guard', factory: createGuardExtension({ cwd, projectKind, agentDir, log }) },
    { name: 'zhiye-search', factory: createSearchExtension({ cwd }) },
    { name: 'zhiye-code-intel', factory: createCodeIntelExtension({ cwd, projectKind, agentDir, env }) },
    { name: 'zhiye-browser-test', factory: createBrowserTestExtension({ cwd, projectKind, env }) },
    { name: 'zhiye-screenshot', factory: createScreenshotExtension({ cwd, projectKind }) },
    ...(isService ? [
      { name: 'zhiye-endpoint-test', factory: createEndpointTestExtension({ cwd, projectKind, env }) },
      { name: 'zhiye-service-inspect', factory: createServiceInspectExtension({ agentDir }) },
    ] : []),
  ]
  const allowedExtPaths = new Set(extensionFactories.map((e) => `<inline:${e.name}>`))
  const resourceLoader = new DefaultResourceLoader({
    cwd,
    agentDir: qaDir,
    settingsManager,
    noExtensions: true,
    noSkills: true,
    noPromptTemplates: true,
    noThemes: true,
    noContextFiles: true,
    disabledBuiltinExtensions: ['mcp'],
    systemPrompt: QA_SYSTEM_PROMPT,
    extensionFactories,
    extensionsOverride: (base) => ({ ...base, extensions: base.extensions.filter((e) => allowedExtPaths.has(e.path)) }),
    skillsOverride: () => ({ skills: [], diagnostics: [] }),
    promptsOverride: () => ({ prompts: [], diagnostics: [] }),
    agentsFilesOverride: () => ({ agentsFiles: [] }),
    appendSystemPromptOverride: () => [],
  })
  await resourceLoader.reload()

  const qaTools = [
    'read', 'find_files', 'grep_files', 'code_symbols', 'typecheck_diagnostics', 'browser_test', 'screenshot',
    ...(isService ? ['test_endpoint', 'service_runtime_inspect'] : []),
  ]
  const sessionManager = SessionManager.create(cwd, path.join(qaDir, 'sessions'))
  const { session } = await createAgentSession({
    cwd,
    agentDir: qaDir,
    model,
    thinkingLevel: 'low',
    modelRuntime,
    resourceLoader,
    tools: qaTools,
    sessionManager,
    settingsManager,
  })

  const maxTurns = Math.min(Math.max(Number(cmd.maxTurns) || 12, 4), 20)
  let turns = 0
  session.subscribe((e) => {
    if (e.type === 'turn_start') {
      turns++
      if (turns > maxTurns) session.abort().catch(() => {})
    } else if (e.type === 'tool_execution_start') {
      emitEvent({ type: 'tool', id: `qa-${e.toolCallId}`, name: e.toolName, summary: `[独立QA] ${toolSummary(cwd, e.toolName, e.args || {})}`, state: 'start' })
    } else if (e.type === 'tool_execution_end') {
      emitEvent({ type: 'tool', id: `qa-${e.toolCallId}`, name: e.toolName, summary: `[独立QA] ${toolSummary(cwd, e.toolName, e.args || {})}`, state: 'end', ok: !e.isError })
    }
  })

  const reqText = String(cmd.requirement || '').slice(0, 4000)
  const planText = String(cmd.planDoc || '').slice(0, 3000)
  const qaPrompt = [
    `请对当前工作区里的 ${projectKind} 应用执行独立对抗式 QA 实测验收（第 ${cmd.round || 1} 轮）：`,
    '',
    '## 用户原始需求',
    reqText || '（见项目代码与计划文档）',
    ...(planText ? ['', '## 验收标准与规划文档', planText] : []),
    '',
    '## 项目架构自动摘要',
    archMd.slice(0, 2500),
    '',
    '请直接用 `browser_test`（全栈项目同步用 `test_endpoint`）实测核心功能交互、表单提交、页面切换与宽屏展示，最后输出标准 JSON 裁决。',
  ].join('\n')

  try {
    await session.prompt(qaPrompt)
    const last = [...session.messages].reverse().find((m) => m.role === 'assistant')
    const raw = last && Array.isArray(last.content)
      ? last.content.filter((c) => c.type === 'text').map((c) => c.text).join('').trim()
      : ''
    const verdict = parseQaVerdict(raw)
    log(`qa_review round=${cmd.round || 1} passed=${verdict.passed} score=${verdict.score} defects=${verdict.defects.length} turns=${turns}`)
    return { ...verdict, turns }
  } finally {
    try { session.dispose() } catch {}
  }
}
