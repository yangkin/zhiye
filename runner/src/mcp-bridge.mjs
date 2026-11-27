// 企业受控 MCP 工具桥（mcp-bridge）：
// 沙箱内不保存任何 MCP 外部凭证；TaskExecutor 在任务前将管理员白名单内的 MCP 工具清单写入 {agentDir}/mcp-servers.json，
// 实际调用经由 127.0.0.1 织页服务端代理（/internal/mcp/call，携带本次 ZHIYE_TASK_TOKEN）完成鉴权、调用与审计。
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { Type } from 'typebox'
import { log } from './util.mjs'

export function readMcpCatalog(agentDir) {
  const file = path.join(agentDir, 'mcp-servers.json')
  if (!existsSync(file)) return []
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'))
    return Array.isArray(parsed?.servers) ? parsed.servers : []
  } catch {
    return []
  }
}

function mcpEndpointUrl(env = process.env) {
  if (env.ZHIYE_MCP_PROXY_URL) return env.ZHIYE_MCP_PROXY_URL
  const gw = String(env.ZHIYE_GATEWAY_URL || '').trim()
  if (!gw) return ''
  try {
    const u = new URL(gw)
    return `${u.origin}/internal/mcp/call`
  } catch {
    return ''
  }
}

export async function invokeMcpTool(agentDir, { server, tool, arguments: toolArgs = {} } = {}, env = process.env) {
  const catalog = readMcpCatalog(agentDir)
  const srv = catalog.find((s) => s.key === server || s.name === server)
  if (!srv) {
    const avail = catalog.map((s) => s.key).join(', ')
    return { ok: false, text: `未找到已授权的 MCP 服务「${server}」。当前可用：${avail || '（无）'}` }
  }
  const effectiveTool = String(tool || '').trim()
    || (srv.transport === 'knowledge' ? 'search_knowledge' : String(srv.tools?.[0]?.name || '').trim())
  const endpoint = mcpEndpointUrl(env)
  const token = env.ZHIYE_TASK_TOKEN
  if (!endpoint || !token) {
    return { ok: false, text: '当前运行环境未配置 MCP 平台代理地址或任务令牌。' }
  }
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ serverKey: srv.key, tool: effectiveTool, arguments: toolArgs || {} }),
      signal: AbortSignal.timeout(25_000),
    })
    const raw = await res.text()
    let data = raw
    try { data = JSON.parse(raw) } catch {}
    if (!res.ok) {
      const msg = typeof data === 'object' && data?.message ? data.message : raw.slice(0, 400)
      return { ok: false, text: `MCP 调用失败（HTTP ${res.status}）：${msg}` }
    }
    const text = typeof data === 'object' && data !== null
      ? (typeof data.content === 'string' ? data.content : JSON.stringify(data.result ?? data, null, 2))
      : String(data)
    log(`mcp_call ${srv.key}.${effectiveTool} ok`)
    return { ok: true, text: text.slice(0, 12_000) }
  } catch (e) {
    return { ok: false, text: `MCP 代理调用异常：${e.message}` }
  }
}

function formatSchemaHint(schema) {
  if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') return ''
  const req = new Set(Array.isArray(schema.required) ? schema.required : [])
  const fields = Object.entries(schema.properties).slice(0, 6).map(([k, v]) => {
    const t = v && typeof v === 'object' && v.type ? `:${v.type}` : ''
    return `${k}${req.has(k) ? '*' : ''}${t}`
  })
  return fields.length ? ` 参数(${fields.join(', ')})` : ''
}

export function readConnectorCatalog(agentDir) {
  const file = path.join(agentDir, 'connectors.json')
  if (!existsSync(file)) return []
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'))
    return Array.isArray(parsed?.connectors) ? parsed.connectors : []
  } catch {
    return []
  }
}

export function hasBitableConnectors(agentDir) {
  return readConnectorCatalog(agentDir).some((c) => c && (c.kind === 'feishu_bitable' || c.kind === 'feishu_app'))
}

function internalProxyUrl(suffix, env = process.env) {
  const gw = String(env.ZHIYE_GATEWAY_URL || '').trim()
  if (!gw) return ''
  try {
    const u = new URL(gw)
    return `${u.origin}${suffix}`
  } catch {
    return ''
  }
}

export async function invokeBitableInspect(agentDir, { connectorId = '', pageSize = 15 } = {}, env = process.env) {
  const catalog = readConnectorCatalog(agentDir).filter((c) => c.kind === 'feishu_bitable' || c.kind === 'feishu_app')
  if (!catalog.length) {
    return { ok: false, text: '当前工作空间未启用任何飞书多维表格连接器。' }
  }
  const endpoint = env.ZHIYE_BITABLE_INSPECT_URL || internalProxyUrl('/internal/connectors/bitable/inspect', env)
  const token = env.ZHIYE_TASK_TOKEN
  if (!endpoint || !token) {
    return { ok: false, text: '当前运行环境未配置连接器探测代理地址或任务令牌。' }
  }
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ connectorId: String(connectorId || '').trim(), pageSize: Math.max(1, Math.min(20, Number(pageSize) || 15)) }),
      signal: AbortSignal.timeout(20_000),
    })
    const raw = await res.text()
    let data = raw
    try { data = JSON.parse(raw) } catch {}
    if (!res.ok) {
      const msg = typeof data === 'object' && data?.message ? data.message : raw.slice(0, 400)
      return { ok: false, text: `飞书多维表格探测失败（HTTP ${res.status}）：${msg}` }
    }
    const text = typeof data === 'object' && data !== null
      ? (typeof data.formatted === 'string' && data.formatted ? data.formatted : JSON.stringify(data, null, 2))
      : String(data)
    log(`inspect_bitable ${connectorId || 'default'} ok`)
    return { ok: true, text: text.slice(0, 12_000) }
  } catch (e) {
    return { ok: false, text: `飞书多维表格探测异常：${e.message}` }
  }
}

export function createBitableInspectExtension({ agentDir, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'inspect_bitable',
      label: '探测飞书多维表格结构',
      description: '实时只读探测当前工作空间飞书多维表格的真实表头字段名与样本记录（密钥托管在服务端），开发接入多维表格看板/台账前请先调用它，严禁凭空猜测中英文列名。',
      parameters: Type.Object({
        connectorId: Type.Optional(Type.String({ description: '飞书多维表格连接器 ID 或名称；留空则探测工作空间默认多维表格' })),
        pageSize: Type.Optional(Type.Integer({ description: '返回样本记录条数（默认 15，最多 20）' })),
      }),
      async execute(_id, params) {
        const r = await invokeBitableInspect(agentDir, params || {}, env)
        return { content: [{ type: 'text', text: r.text }], details: { ok: r.ok } }
      },
    })
  }
}

export function createMcpBridgeExtension({ agentDir, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'mcp_list',
      label: '查看企业 MCP 工具',
      description: '列出平台管理员已授权给当前工作空间的企业 MCP 服务与可用工具清单（如公司文档、接口规范、GitLab、数据库只读 Schema 等）。',
      parameters: Type.Object({}),
      async execute() {
        const catalog = readMcpCatalog(agentDir)
        if (!catalog.length) return { content: [{ type: 'text', text: '当前未配置或未启用任何企业 MCP 服务。' }], details: { count: 0 } }
        const lines = catalog.map((s) => {
          const tools = Array.isArray(s.tools) && s.tools.length
            ? s.tools.map((t) => `  - \`${t.name}\`${formatSchemaHint(t.inputSchema)}: ${t.description || ''}`).join('\n')
            : '  - （通用工具调用）'
          return `### ${s.name} (\`${s.key}\` · ${s.transport || 'http'})\n${s.description || ''}\n${tools}`
        })
        return { content: [{ type: 'text', text: lines.join('\n\n') }], details: { count: catalog.length } }
      },
    })

    pi.registerTool({
      name: 'mcp_call',
      label: '调用企业 MCP',
      description: '通过织页安全代理调用管理员白名单内的企业 MCP 工具，获取公司文档、接口契约或内部上下文。所有凭证由服务端托管并审计。',
      parameters: Type.Object({
        server: Type.String({ description: 'MCP 服务标识（server key，可先通过 mcp_list 查看）' }),
        tool: Type.Optional(Type.String({ description: '工具名称（知识库默认 search_knowledge）' })),
        arguments: Type.Optional(Type.Record(Type.String(), Type.Any(), { description: '传给 MCP 工具的参数对象' })),
      }),
      async execute(_id, params) {
        const r = await invokeMcpTool(agentDir, params || {}, env)
        return { content: [{ type: 'text', text: r.text }], details: { ok: r.ok } }
      },
    })
  }
}
