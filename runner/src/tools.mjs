// 织页扩展编码工具：结构化文件定位（find_files）、内容搜索（grep_files）、主动自检（verify_app）与全栈运行态只读探针（service_runtime_inspect）。
import { existsSync, lstatSync, readdirSync, readFileSync, realpathSync, statSync } from 'node:fs'
import path from 'node:path'
import { Type } from 'typebox'
import { verify } from './verify.mjs'

const SKIP_DIRS = new Set(['.git', 'node_modules', '.zhiye', '.pi', 'dist', 'build', 'target', '__pycache__'])
const MAX_FILE_SIZE = 512 * 1024
const MAX_OUTPUT_BYTES = 14 * 1024

function isInside(root, target) {
  const rel = path.relative(root, target)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

function resolveDir(cwd, dir) {
  const root = path.resolve(cwd)
  const raw = String(dir || '').trim().replace(/^@/, '')
  if (!raw || raw === '.') return root
  const abs = path.resolve(root, raw)
  if (!isInside(root, abs)) throw new Error(`不允许访问工作区以外的目录：${raw}`)
  if (!existsSync(abs)) throw new Error(`目录不存在：${raw}`)
  const realRoot = realpathSync(root)
  const realAbs = realpathSync(abs)
  if (!isInside(realRoot, realAbs)) throw new Error(`不允许通过符号链接访问工作区以外的目录：${raw}`)
  if (!statSync(abs).isDirectory()) throw new Error(`不是目录：${raw}`)
  return abs
}

/** 轻量 glob 转正则：支持 **、*、? */
export function globToRegExp(pattern) {
  const p = String(pattern || '').trim().replace(/\\/g, '/')
  if (!p) return null
  let out = '^'
  for (let i = 0; i < p.length; i++) {
    const c = p[i]
    if (c === '*' && p[i + 1] === '*') {
      if (p[i + 2] === '/') { out += '(?:.*/)?'; i += 2 }
      else { out += '.*'; i += 1 }
    } else if (c === '*') {
      out += '[^/]*'
    } else if (c === '?') {
      out += '[^/]'
    } else if ('^$.|+()[]{}\\'.includes(c)) {
      out += '\\' + c
    } else {
      out += c
    }
  }
  out += '$'
  return new RegExp(out, 'i')
}

function isSecretOrProtected(rel) {
  const segs = rel.split('/')
  const base = segs[segs.length - 1] || ''
  if (/^\.env/i.test(base)) return true
  if (segs.includes('.git') || segs.includes('.pi') || segs.includes('node_modules')) return true
  return false
}

function walkWorkspace(root, startDir, onFile) {
  const stack = [startDir]
  while (stack.length) {
    const dir = stack.pop()
    let entries
    try { entries = readdirSync(dir) } catch { continue }
    entries.sort()
    for (const name of entries) {
      const abs = path.join(dir, name)
      const rel = path.relative(root, abs).split(path.sep).join('/')
      if (isSecretOrProtected(rel)) continue
      let st
      try { st = lstatSync(abs) } catch { continue }
      if (st.isSymbolicLink()) continue
      if (st.isDirectory()) {
        if (SKIP_DIRS.has(name)) continue
        stack.push(abs)
      } else if (st.isFile()) {
        if (onFile(abs, rel, st) === false) return
      }
    }
  }
}

export function findFiles(cwd, { pattern = '', dir = '', maxResults = 150 } = {}) {
  const root = path.resolve(cwd)
  const start = resolveDir(cwd, dir)
  const limit = Math.max(1, Math.min(300, Number(maxResults) || 150))
  const trimmed = String(pattern || '').trim()
  const isGlob = /[*?]/.test(trimmed)
  const re = isGlob ? globToRegExp(trimmed) : null
  const needle = trimmed.toLowerCase()
  const hits = []
  let truncated = false

  walkWorkspace(root, start, (_abs, rel) => {
    if (trimmed) {
      const matched = re ? (re.test(rel) || re.test(path.basename(rel))) : rel.toLowerCase().includes(needle)
      if (!matched) return true
    }
    if (hits.length >= limit) { truncated = true; return false }
    hits.push(rel)
    return true
  })

  hits.sort()
  return { files: hits, truncated }
}

export function grepFiles(cwd, { query = '', dir = '', filePattern = '', isRegex = false, caseSensitive = false, maxMatches = 80 } = {}) {
  const q = String(query ?? '')
  if (!q.trim()) throw new Error('缺少搜索内容 query')
  const root = path.resolve(cwd)
  const start = resolveDir(cwd, dir)
  const limit = Math.max(1, Math.min(200, Number(maxMatches) || 80))
  const fileRe = filePattern && String(filePattern).trim() ? globToRegExp(String(filePattern).trim()) : null
  let re
  if (isRegex) {
    try { re = new RegExp(q, caseSensitive ? '' : 'i') } catch (e) { throw new Error(`正则表达式无效：${e.message}`) }
  } else {
    const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    re = new RegExp(escaped, caseSensitive ? '' : 'i')
  }

  const matches = []
  let truncated = false
  let bytes = 0

  walkWorkspace(root, start, (abs, rel, st) => {
    if (st.size > MAX_FILE_SIZE) return true
    if (rel === 'package-lock.json' || rel.endsWith('.lock')) return true
    if (fileRe && !fileRe.test(rel) && !fileRe.test(path.basename(rel))) return true
    let buf
    try { buf = readFileSync(abs) } catch { return true }
    if (buf.includes(0)) return true // 跳过二进制文件
    const lines = buf.toString('utf8').split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      if (!re.test(lines[i])) continue
      const lineText = lines[i].length > 260 ? lines[i].slice(0, 260) + '…' : lines[i]
      const entry = `${rel}:${i + 1}: ${lineText}`
      if (matches.length >= limit || bytes + Buffer.byteLength(entry) > MAX_OUTPUT_BYTES) {
        truncated = true
        return false
      }
      matches.push(entry)
      bytes += Buffer.byteLength(entry) + 1
    }
    return true
  })

  return { matches, truncated }
}

/** 结构化文件定位 + 内容搜索扩展 */
export function createSearchExtension({ cwd }) {
  return (pi) => {
    pi.registerTool({
      name: 'find_files',
      label: '查找文件',
      description: '按路径关键字或 glob（如 *.tsx、src/**/Table*）快速列出工作区文件，自动忽略 node_modules/.git/构建产物。比 bash find 更快更安全。',
      parameters: Type.Object({
        pattern: Type.Optional(Type.String({ description: '文件名/路径子串或 glob（支持 *、**、?）；留空列出全部源码文件' })),
        dir: Type.Optional(Type.String({ description: '限制在某个相对子目录内查找，例如 src 或 server' })),
        maxResults: Type.Optional(Type.Integer({ description: '最多返回多少条（默认 150，最大 300）' })),
      }),
      async execute(_id, params) {
        const r = findFiles(cwd, params || {})
        if (!r.files.length) return { content: [{ type: 'text', text: '没有找到匹配的文件。' }], details: { count: 0 } }
        const text = `找到 ${r.files.length} 个文件${r.truncated ? '（已截断，可缩小 dir 或 pattern）' : ''}：\n${r.files.join('\n')}`
        return { content: [{ type: 'text', text }], details: { count: r.files.length, truncated: r.truncated } }
      },
    })

    pi.registerTool({
      name: 'grep_files',
      label: '搜索代码',
      description: '在工作区源码中按关键字或正则快速搜索匹配行（返回 路径:行号: 内容），自动跳过二进制、node_modules、.env*。定位定义与调用时优先使用。',
      parameters: Type.Object({
        query: Type.String({ description: '要搜索的关键字或正则表达式' }),
        dir: Type.Optional(Type.String({ description: '只在某个相对子目录内搜索，例如 src 或 server' })),
        filePattern: Type.Optional(Type.String({ description: '只搜匹配该 glob 的文件，例如 *.tsx 或 *.mjs' })),
        isRegex: Type.Optional(Type.Boolean({ description: '是否按正则表达式匹配（默认 false 按字面量搜索）' })),
        caseSensitive: Type.Optional(Type.Boolean({ description: '是否区分大小写（默认 false）' })),
        maxMatches: Type.Optional(Type.Integer({ description: '最多返回匹配行数（默认 80）' })),
      }),
      async execute(_id, params) {
        const r = grepFiles(cwd, params || {})
        if (!r.matches.length) return { content: [{ type: 'text', text: `未找到匹配「${params?.query || ''}」的代码行。` }], details: { count: 0 } }
        const text = `共找到 ${r.matches.length} 处匹配${r.truncated ? '（结果已截断）' : ''}：\n${r.matches.join('\n')}`
        return { content: [{ type: 'text', text }], details: { count: r.matches.length, truncated: r.truncated } }
      },
    })
  }
}

/** 主动校验工具（verify_app）：让模型在交付总结前自己执行完整构建、类型检查、冒烟与全栈起服探活。 */
export function createVerifyExtension({ cwd, projectKind, env = process.env, runVerify = verify }) {
  return (pi) => {
    pi.registerTool({
      name: 'verify_app',
      label: '工程自检',
      description: '立即运行平台同款完整校验（类型检查、构建、页面冒烟测试；全栈应用还会临时拉起后端验证 /api/health 与首屏渲染）。写完代码或修完问题后、输出最终总结前务必调用一次，确认全部通过再交付。',
      parameters: Type.Object({}),
      async execute(_id, _params, _signal, _onUpdate, ctx) {
        const res = await runVerify(cwd, { projectKind }, env)
        const lines = []
        let shot = null
        for (const c of res.checks || []) {
          const mark = c.skipped ? '○' : c.ok ? '✓' : '✗'
          const head = `${mark} ${c.name}${c.durationMs !== undefined ? ` (${c.durationMs}ms)` : ''}`
          lines.push(c.detail ? `${head}\n${String(c.detail).slice(0, 3000)}` : head)
          if (!shot && typeof c.screenshot === 'string' && c.screenshot) shot = c.screenshot
        }
        const header = res.ok
          ? '工程自检全部通过！可以放心收尾总结。'
          : '工程自检未通过，请根据下面的具体报错修复后再次调用 verify_app 复检：'
        const content = [{ type: 'text', text: `${header}\n\n${lines.join('\n\n')}` }]
        const vision = Array.isArray(ctx?.model?.input) ? ctx.model.input.includes('image') : true
        if (shot && vision && res.ok) content.push({ type: 'image', data: shot, mimeType: 'image/jpeg' })
        const compactChecks = (res.checks || []).map(({ name, ok, skipped, detail }) => ({
          name,
          ok: !!ok,
          ...(skipped ? { skipped: true } : {}),
          ...(detail ? { detail: String(detail).slice(0, 1000) } : {}),
        }))
        return { content, details: { ok: !!res.ok, checks: compactChecks } }
      },
    })
  }
}

/** 全栈/单体运行态只读诊断工具：读取服务端在任务启动前写入的脱敏快照（运行状态、最近日志、环境变量键名、PG 表结构）。 */
export function readServiceRuntimeSnapshot(agentDir, section = 'all') {
  const file = path.join(agentDir, 'runtime-inspect.json')
  if (!existsSync(file)) {
    return '当前全栈服务尚未首次部署，暂无运行态日志或数据库表结构信息。'
  }
  let data
  try { data = JSON.parse(readFileSync(file, 'utf8')) } catch {
    return '无法解析运行态诊断快照。'
  }
  const sec = String(section || 'all').toLowerCase()
  const parts = []
  if (sec === 'all' || sec === 'config') {
    const cfg = data.service || {}
    parts.push([
      '### 服务运行配置',
      `- 状态：${cfg.status || 'unknown'}`,
      `- 运行时 / 框架：${cfg.runtime || '-'} / ${cfg.framework || '-'}`,
      `- 健康检查路径：${cfg.healthPath || '/api/health'}`,
      cfg.buildCommand ? `- 构建命令：${cfg.buildCommand}` : null,
      cfg.startCommand ? `- 启动命令：${cfg.startCommand}` : null,
      cfg.lastError ? `- 最近部署错误：${cfg.lastError}` : null,
    ].filter(Boolean).join('\n'))
  }
  if (sec === 'all' || sec === 'env') {
    const keys = Array.isArray(data.envKeys) ? data.envKeys : []
    parts.push(`### 已配置环境变量键名（值已脱敏隐藏）\n${keys.length ? keys.map((k) => `- ${k}`).join('\n') : '（暂未配置自定义环境变量）'}`)
  }
  if (sec === 'all' || sec === 'database') {
    const db = data.database || {}
    if (!db.enabled) {
      parts.push('### 独立 PostgreSQL 数据库\n未启用（默认使用 DATA_DIR 持久化目录；用户可在「服务托管」一键开通）')
    } else {
      const tables = Array.isArray(db.tables) ? db.tables : []
      const tLines = tables.length
        ? tables.map((t) => `- ${t.name}（列：${(t.columns || []).join(', ') || '-'}）`).join('\n')
        : '（Schema 已开通，暂无业务表）'
      parts.push(`### 独立 PostgreSQL 数据库（Schema: ${db.schemaName || '-'}）\n${tLines}`)
    }
  }
  if (sec === 'all' || sec === 'logs') {
    const logs = String(data.recentLogs || '').trim()
    parts.push(`### 最近运行日志（已脱敏）\n\`\`\`\n${logs ? logs.slice(-5000) : '（暂无运行日志）'}\n\`\`\``)
  }
  return parts.join('\n\n')
}

export function createServiceInspectExtension({ agentDir }) {
  return (pi) => {
    pi.registerTool({
      name: 'service_runtime_inspect',
      label: '检查服务运行态',
      description: '只读查看当前全栈/单体服务的真实运行状态、最近运行日志（已脱敏）、已配置的环境变量键名（不含密钥值）及独立 PostgreSQL Schema 表结构。排查线上报错、接口异常或数据结构问题时先调用它，不要盲猜。',
      parameters: Type.Object({
        section: Type.Optional(Type.Union([
          Type.Literal('all'),
          Type.Literal('logs'),
          Type.Literal('config'),
          Type.Literal('env'),
          Type.Literal('database'),
        ], { description: '要查看的部分：all（全部，默认）/ logs / config / env / database' })),
      }),
      async execute(_id, params) {
        const text = readServiceRuntimeSnapshot(agentDir, params?.section || 'all')
        return { content: [{ type: 'text', text }], details: {} }
      },
    })
  }
}
