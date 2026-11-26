// 代码语义大脑（code_intel）：
// 1. typecheck_diagnostics：秒级增量类型/语法诊断（不跑全量打包与 Chrome）
// 2. code_symbols：基于 @babel/parser 的符号大纲（导出、组件、API 路由、SQL 表）与跨文件引用定位
// 3. generateArchitectureSummary：自动生成 .zhiye/architecture.md，沉淀项目架构图谱
// 4. inspect_source：读取任务启动前写入的数据源真实结构与探活样例（sources-inspect.json）
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { parse as babelParse } from '@babel/parser'
import { Type } from 'typebox'
import { run } from './util.mjs'
import { buildEnv, templateLint, vueLint } from './verify.mjs'
import { discoverWorkspaceLayout } from './workflow.mjs'
import { checkBash } from './guard.mjs'

const SKIP_DIRS = new Set(['node_modules', '.git', '.zhiye', '.pi', 'dist', 'build', 'target', '__pycache__', '.venv', 'venv'])

function walkCodeFiles(root, dir = '', out = []) {
  const abs = dir ? path.join(root, dir) : root
  if (!existsSync(abs)) return out
  for (const name of readdirSync(abs)) {
    if (SKIP_DIRS.has(name)) continue
    const full = path.join(abs, name)
    let st
    try { st = statSync(full) } catch { continue }
    const rel = dir ? `${dir}/${name}` : name
    if (st.isDirectory()) walkCodeFiles(root, rel, out)
    else if (st.isFile() && st.size <= 512 * 1024) out.push(rel)
  }
  return out
}

// ---------------- 1. 增量类型与语法诊断 ----------------

function parseTscLines(raw) {
  const diags = []
  const re = /^(.+?)\((\d+),(\d+)\):\s*(error|warning)\s+(TS\d+):\s*(.+)$/
  for (const line of String(raw || '').split(/\r?\n/)) {
    const m = re.exec(line.trim())
    if (m) {
      diags.push({ file: m[1].replace(/\\/g, '/'), line: Number(m[2]), col: Number(m[3]), severity: m[4], code: m[5], message: m[6] })
    }
  }
  return diags
}

export async function runTypecheckDiagnostics(cwd, { projectKind = 'template', env = process.env } = {}) {
  const t0 = Date.now()
  const diagnostics = []
  const notes = []

  const runTs = async (baseDir, binRel) => {
    const bin = path.join(baseDir, binRel)
    if (!existsSync(bin) || !existsSync(path.join(baseDir, 'tsconfig.json'))) return
    const r = await run(process.execPath, [bin, '--noEmit', '-p', 'tsconfig.json', '--pretty', 'false'], { cwd: baseDir, env: buildEnv(env), timeoutMs: 60_000 })
    if (r.code !== 0) {
      const parsed = parseTscLines(r.out)
      if (parsed.length) diagnostics.push(...parsed)
      else if (r.out.trim()) diagnostics.push({ file: path.relative(cwd, path.join(baseDir, 'tsconfig.json')) || 'tsconfig.json', line: 1, col: 1, severity: 'error', code: 'TSC', message: r.out.trim().slice(0, 600) })
    }
  }

  const layout = discoverWorkspaceLayout(cwd)
  // 0. 项目自声明诊断命令（zhiye.yaml -> diagnostics）优先执行
  if (layout.workflow?.diagnostics?.length) {
    for (const s of layout.workflow.diagnostics) {
      const stepCwd = s.cwd ? path.resolve(cwd, s.cwd) : path.resolve(cwd)
      const blocked = checkBash(stepCwd, s.command, { projectKind: 'service' })
      if (blocked) {
        diagnostics.push({ file: layout.workflow.file, line: 1, col: 1, severity: 'error', code: 'GUARD', message: blocked })
        continue
      }
      const r = await run('sh', ['-c', s.command], { cwd: stepCwd, env: buildEnv(env), timeoutMs: s.timeoutMs || 60_000 })
      if (r.code !== 0) {
        diagnostics.push({ file: s.cwd || layout.workflow.file, line: 1, col: 1, severity: 'error', code: 'DIAG', message: String(r.out || `exit=${r.code}`).trim().slice(0, 600) })
      }
    }
  } else if (projectKind === 'vue') {
    await runTs(cwd, 'node_modules/vue-tsc/bin/vue-tsc.js')
    for (const p of vueLint(cwd)) diagnostics.push({ file: p.split('：')[0], line: 1, col: 1, severity: 'error', code: 'VUE_LINT', message: p })
  } else if (projectKind === 'template' || projectKind === 'vite') {
    await runTs(cwd, 'node_modules/typescript/bin/tsc')
    for (const p of templateLint(cwd)) diagnostics.push({ file: p.split('：')[0], line: 1, col: 1, severity: 'error', code: 'TEMPLATE_LINT', message: p })
  } else if (projectKind === 'service') {
    // 动态扫描所有存在语言/配置文件的目录（不写死 frontend/ 或 backend/）
    for (const relDir of layout.diagDirs) {
      const base = relDir ? path.join(cwd, relDir) : cwd
      if (existsSync(path.join(base, 'tsconfig.json'))) {
        if (existsSync(path.join(base, 'node_modules/vue-tsc/bin/vue-tsc.js'))) {
          await runTs(base, 'node_modules/vue-tsc/bin/vue-tsc.js')
        } else {
          await runTs(base, 'node_modules/typescript/bin/tsc')
        }
      }
      if (existsSync(path.join(base, 'pom.xml'))) {
        const r = await run('mvn', ['-B', '-o', 'test-compile'], { cwd: base, env: buildEnv(env), timeoutMs: 90_000 })
        if (r.code !== 0) {
          const errLines = String(r.out || '').split(/\r?\n/).filter((l) => l.startsWith('[ERROR]'))
          let matched = 0
          for (const l of errLines) {
            const m = /\[ERROR\]\s+(.+\.java):\[(\d+),(\d+)\]\s*(.+)/.exec(l)
            if (m) {
              diagnostics.push({ file: path.relative(cwd, m[1]) || m[1], line: Number(m[2]), col: Number(m[3]), severity: 'error', code: 'JAVAC', message: m[4].trim() })
              matched++
            }
          }
          if (matched === 0) {
            diagnostics.push({ file: path.join(relDir, 'pom.xml'), line: 1, col: 1, severity: 'error', code: 'MAVEN', message: errLines.slice(0, 8).join('\n').slice(0, 600) || `mvn test-compile exit=${r.code}` })
          }
        }
      }
      if (existsSync(path.join(base, 'go.mod'))) {
        const r = await run('go', ['vet', './...'], { cwd: base, env: buildEnv(env), timeoutMs: 60_000 })
        if (r.code !== 0) {
          diagnostics.push({ file: path.join(relDir, 'go.mod'), line: 1, col: 1, severity: 'error', code: 'GO_VET', message: String(r.out || `go vet exit=${r.code}`).trim().slice(0, 600) })
        }
      }
    }
    const files = walkCodeFiles(cwd)
    for (const rel of files.filter((f) => /\.(mjs|cjs|js)$/.test(f) && !/^(src|frontend|web|client|ui|public)\//.test(f)).slice(0, 30)) {
      const r = await run(process.execPath, ['--check', rel], { cwd, env: buildEnv(env), timeoutMs: 10_000 })
      if (r.code !== 0) diagnostics.push({ file: rel, line: 1, col: 1, severity: 'error', code: 'JS_SYNTAX', message: (r.out || '语法错误').trim().slice(0, 400) })
    }
    const pyFiles = files.filter((f) => f.endsWith('.py')).slice(0, 30)
    if (pyFiles.length) {
      const r = await run('python3', ['-m', 'py_compile', ...pyFiles], { cwd, env: buildEnv(env), timeoutMs: 15_000 })
      if (r.code !== 0) diagnostics.push({ file: pyFiles[0], line: 1, col: 1, severity: 'error', code: 'PY_SYNTAX', message: (r.out || 'Python 语法错误').trim().slice(0, 400) })
    }
  } else if (projectKind === 'script') {
    const files = walkCodeFiles(cwd)
    for (const rel of files.filter((f) => /\.(mjs|cjs|js)$/.test(f)).slice(0, 20)) {
      const r = await run(process.execPath, ['--check', rel], { cwd, env: buildEnv(env), timeoutMs: 10_000 })
      if (r.code !== 0) diagnostics.push({ file: rel, line: 1, col: 1, severity: 'error', code: 'JS_SYNTAX', message: (r.out || '语法错误').trim().slice(0, 400) })
    }
    const pyFiles = files.filter((f) => f.endsWith('.py')).slice(0, 20)
    if (pyFiles.length) {
      const r = await run('python3', ['-m', 'py_compile', ...pyFiles], { cwd, env: buildEnv(env), timeoutMs: 15_000 })
      if (r.code !== 0) diagnostics.push({ file: pyFiles[0], line: 1, col: 1, severity: 'error', code: 'PY_SYNTAX', message: (r.out || 'Python 语法错误').trim().slice(0, 400) })
    }
  } else {
    notes.push('静态 HTML 项目无编译期类型检查')
  }

  const durationMs = Date.now() - t0
  const ok = diagnostics.length === 0
  const lines = ok
    ? [`✓ 增量诊断通过（0 处错误，耗时 ${durationMs}ms）${notes.length ? ' · ' + notes.join('；') : ''}`]
    : [
        `✗ 增量诊断发现 ${diagnostics.length} 处错误（耗时 ${durationMs}ms）：`,
        ...diagnostics.slice(0, 50).map((d) => `- ${d.file}:${d.line}:${d.col} [${d.code}] ${d.message}`),
        ...(diagnostics.length > 50 ? [`… 其余 ${diagnostics.length - 50} 处已省略`] : []),
      ]
  return { ok, durationMs, diagnostics: diagnostics.slice(0, 50), text: lines.join('\n') }
}

// ---------------- 2. AST 符号大纲 & 跨文件引用 ----------------

function extractJsTsSymbols(rel, src) {
  const symbols = []
  const routes = []
  let ast = null
  try {
    ast = babelParse(src, {
      sourceType: 'unambiguous',
      errorRecovery: true,
      plugins: ['typescript', 'jsx', 'decorators-legacy', 'classProperties', 'objectRestSpread', 'topLevelAwait'],
    })
  } catch {}
  if (ast?.program?.body) {
    const pushDecl = (node, exported) => {
      if (!node) return
      const line = node.loc?.start?.line || 1
      if (node.type === 'FunctionDeclaration' && node.id?.name) {
        symbols.push({ file: rel, line, kind: /^[A-Z]/.test(node.id.name) ? 'component/fn' : 'function', name: node.id.name, exported })
      } else if (node.type === 'ClassDeclaration' && node.id?.name) {
        symbols.push({ file: rel, line, kind: 'class', name: node.id.name, exported })
      } else if (node.type === 'TSInterfaceDeclaration' && node.id?.name) {
        symbols.push({ file: rel, line, kind: 'interface', name: node.id.name, exported })
      } else if (node.type === 'TSTypeAliasDeclaration' && node.id?.name) {
        symbols.push({ file: rel, line, kind: 'type', name: node.id.name, exported })
      } else if (node.type === 'VariableDeclaration') {
        for (const d of node.declarations || []) {
          if (d.id?.type === 'Identifier' && (exported || d.init?.type === 'ArrowFunctionExpression' || d.init?.type === 'FunctionExpression')) {
            symbols.push({ file: rel, line: d.loc?.start?.line || line, kind: /^[A-Z]/.test(d.id.name) ? 'component/const' : 'const', name: d.id.name, exported })
          }
        }
      }
    }
    for (const stmt of ast.program.body) {
      if (stmt.type === 'ExportNamedDeclaration') pushDecl(stmt.declaration, true)
      else if (stmt.type === 'ExportDefaultDeclaration') {
        if (stmt.declaration?.id?.name) pushDecl(stmt.declaration, true)
        else symbols.push({ file: rel, line: stmt.loc?.start?.line || 1, kind: 'default', name: 'default', exported: true })
      } else pushDecl(stmt, false)
    }
  }
  // 识别 Express / Fastify / Hono / Node HTTP 路由
  const routeRe = /\.(get|post|put|patch|delete)\s*\(\s*['"`](\/[^'"`\s]+)['"`]/gi
  let m
  while ((m = routeRe.exec(src)) !== null) {
    const line = src.slice(0, m.index).split('\n').length
    routes.push({ file: rel, line, method: m[1].toUpperCase(), path: m[2] })
  }
  const rawRouteRe = /pathname\s*===\s*['"`](\/api\/[^'"`\s]+)['"`]|pathname\.startsWith\s*\(\s*['"`](\/api\/[^'"`\s]+)['"`]/g
  while ((m = rawRouteRe.exec(src)) !== null) {
    const line = src.slice(0, m.index).split('\n').length
    routes.push({ file: rel, line, method: 'HTTP', path: m[1] || m[2] })
  }
  return { symbols, routes }
}

function extractSqlTables(rel, src) {
  const tables = []
  const re = /create\s+table\s+(?:if\s+not\s+exists\s+)?([a-zA-Z0-9_".]+)/gi
  let m
  while ((m = re.exec(src)) !== null) {
    const line = src.slice(0, m.index).split('\n').length
    tables.push({ file: rel, line, name: m[1].replace(/"/g, '') })
  }
  return tables
}

function extractPySymbols(rel, src) {
  const symbols = []
  const routes = []
  const lines = src.split('\n')
  lines.forEach((l, i) => {
    const fn = /^(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/.exec(l)
    if (fn) symbols.push({ file: rel, line: i + 1, kind: 'def', name: fn[1], exported: !fn[1].startsWith('_') })
    const cls = /^class\s+([A-Za-z_][A-Za-z0-9_]*)/.exec(l)
    if (cls) symbols.push({ file: rel, line: i + 1, kind: 'class', name: cls[1], exported: true })
    const rt = /@(?:app|router)\.(get|post|put|patch|delete|route)\s*\(\s*['"]([^'"]+)['"]/i.exec(l)
    if (rt) routes.push({ file: rel, line: i + 1, method: rt[1].toUpperCase(), path: rt[2] })
  })
  return { symbols, routes }
}

function extractJavaSymbols(rel, src) {
  const symbols = []
  const routes = []
  const lines = src.split('\n')
  let classPrefix = ''
  lines.forEach((l, i) => {
    const cls = /\b(public\s+)?(class|interface|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)/.exec(l)
    if (cls) symbols.push({ file: rel, line: i + 1, kind: cls[2], name: cls[3], exported: Boolean(cls[1]) })
    const rm = /@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']+)["']/.exec(l)
    if (rm) classPrefix = rm[1].replace(/\/$/, '')
    const mp = /@(Get|Post|Put|Patch|Delete)Mapping\s*\(\s*(?:value\s*=\s*)?(?:["']([^"']+)["'])?/.exec(l)
    if (mp) routes.push({ file: rel, line: i + 1, method: mp[1].toUpperCase(), path: `${classPrefix}${mp[2] || ''}` || '/' })
  })
  return { symbols, routes }
}

function extractGoSymbols(rel, src) {
  const symbols = []
  const routes = []
  const lines = src.split('\n')
  lines.forEach((l, i) => {
    const fn = /^func\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(/.exec(l)
    if (fn) symbols.push({ file: rel, line: i + 1, kind: 'func', name: fn[1], exported: /^[A-Z]/.test(fn[1]) })
    const tp = /^type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface)\b/.exec(l)
    if (tp) symbols.push({ file: rel, line: i + 1, kind: tp[2], name: tp[1], exported: /^[A-Z]/.test(tp[1]) })
    const rt = /\.(GET|POST|PUT|PATCH|DELETE|HandleFunc)\s*\(\s*["']([^"']+)["']/i.exec(l)
    if (rt) routes.push({ file: rel, line: i + 1, method: rt[1].toUpperCase() === 'HANDLEFUNC' ? 'HTTP' : rt[1].toUpperCase(), path: rt[2] })
  })
  return { symbols, routes }
}

export function analyzeWorkspaceCode(cwd, { dir = '', maxFiles = 120 } = {}) {
  const all = walkCodeFiles(cwd, dir).slice(0, maxFiles)
  const symbols = []
  const routes = []
  const tables = []
  for (const rel of all) {
    const abs = path.join(cwd, rel)
    let src
    try { src = readFileSync(abs, 'utf8') } catch { continue }
    if (/\.(tsx?|jsx?|mjs|cjs)$/.test(rel)) {
      const r = extractJsTsSymbols(rel, src)
      symbols.push(...r.symbols)
      routes.push(...r.routes)
    } else if (rel.endsWith('.py')) {
      const r = extractPySymbols(rel, src)
      symbols.push(...r.symbols)
      routes.push(...r.routes)
    } else if (rel.endsWith('.java')) {
      const r = extractJavaSymbols(rel, src)
      symbols.push(...r.symbols)
      routes.push(...r.routes)
    } else if (rel.endsWith('.go')) {
      const r = extractGoSymbols(rel, src)
      symbols.push(...r.symbols)
      routes.push(...r.routes)
    } else if (rel.endsWith('.sql')) {
      tables.push(...extractSqlTables(rel, src))
    } else if (rel.endsWith('.vue')) {
      const script = /<script[^>]*>([\s\S]*?)<\/script>/i.exec(src)
      if (script) {
        const r = extractJsTsSymbols(rel, script[1])
        symbols.push(...r.symbols)
      }
    }
  }
  return { files: all, symbols, routes, tables }
}

export function findSymbolReferences(cwd, symbolName, { dir = '', maxResults = 80 } = {}) {
  const sym = String(symbolName || '').trim()
  if (!sym || !/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(sym)) return { matches: [], error: 'symbol 必须是合法标识符名称' }
  const re = new RegExp(`\\b${sym}\\b`)
  const files = walkCodeFiles(cwd, dir).filter((f) => /\.(tsx?|jsx?|mjs|cjs|vue|py|go|java|sql)$/.test(f))
  const matches = []
  for (const rel of files) {
    let src
    try { src = readFileSync(path.join(cwd, rel), 'utf8') } catch { continue }
    const lines = src.split('\n')
    for (let i = 0; i < lines.length; i++) {
      if (re.test(lines[i])) {
        matches.push(`${rel}:${i + 1}: ${lines[i].trim().slice(0, 180)}`)
        if (matches.length >= maxResults) return { matches, truncated: true }
      }
    }
  }
  return { matches, truncated: false }
}

/** 自动扫描项目并写入 .zhiye/architecture.md，供后续迭代与独立 QA 子智能体秒级把握全局架构。 */
export function generateArchitectureSummary(cwd, projectKind = 'template') {
  const info = analyzeWorkspaceCode(cwd)
  const exported = info.symbols.filter((s) => s.exported).slice(0, 45)
  const lines = [
    `# 项目架构自动摘要（${projectKind}）`,
    `> 由织页代码语义大脑自动生成，文件总数：${info.files.length}`,
    '',
  ]
  if (info.routes.length) {
    lines.push('## 后端接口路由')
    for (const r of info.routes.slice(0, 30)) lines.push(`- \`${r.method} ${r.path}\` (${r.file}:${r.line})`)
    lines.push('')
  }
  if (info.tables.length) {
    lines.push('## 数据表定义')
    for (const t of info.tables.slice(0, 25)) lines.push(`- \`${t.name}\` (${t.file}:${t.line})`)
    lines.push('')
  }
  if (exported.length) {
    lines.push('## 核心导出组件与符号')
    for (const s of exported) lines.push(`- \`${s.name}\` [${s.kind}] — ${s.file}:${s.line}`)
    lines.push('')
  }
  const md = lines.join('\n').trim() + '\n'
  try {
    mkdirSync(path.join(cwd, '.zhiye'), { recursive: true })
    writeFileSync(path.join(cwd, '.zhiye', 'architecture.md'), md, 'utf8')
  } catch {}
  return md
}

// ---------------- 3. 开发态数据源真实探查（inspect_source） ----------------

export function readSourceInspectSnapshot(agentDir, key = '') {
  const file = path.join(agentDir, 'sources-inspect.json')
  if (!existsSync(file)) return '当前应用尚未绑定任何外部数据源，或暂无数据源探测快照。'
  let data
  try { data = JSON.parse(readFileSync(file, 'utf8')) } catch { return '无法读取数据源快照文件。' }
  const list = Array.isArray(data.sources) ? data.sources : []
  if (!list.length) return '当前应用尚未绑定数据源。'
  const filtered = key ? list.filter((s) => s.key === key || s.name === key) : list
  if (!filtered.length) return `未找到名为「${key}」的数据源。可用调用名：${list.map((s) => s.key).join(', ')}`
  return filtered.map((s) => {
    const params = Array.isArray(s.params) && s.params.length
      ? s.params.map((p) => `  - ${p.name} (${p.type || 'string'}${p.required ? ', 必填' : ''}): ${p.description || ''} ${p.example ? `示例=${p.example}` : ''}`).join('\n')
      : '  - 无参数'
    const live = s.liveProbe
      ? `实测返回（HTTP ${s.liveProbe.status ?? '-'}）：\n\`\`\`json\n${String(s.liveProbe.sample || '').slice(0, 2000)}\n\`\`\``
      : s.sample
        ? `配置返回样例：\n\`\`\`json\n${String(s.sample).slice(0, 2000)}\n\`\`\``
        : '暂无返回样例'
    return `### 数据源 ${s.name} (\`${s.key}\` · ${s.method || 'GET'})\n说明：${s.description || '无'}\n参数：\n${params}\n${live}`
  }).join('\n\n')
}

export function createCodeIntelExtension({ cwd, projectKind, agentDir, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'typecheck_diagnostics',
      label: '增量类型诊断',
      description: '秒级运行增量类型检查与语法诊断（比 verify_app 快 5 倍，不跑全量打包与浏览器），返回精准的 文件:行:列 错误清单。改完关键文件后优先用它快速排错。',
      parameters: Type.Object({}),
      async execute() {
        const r = await runTypecheckDiagnostics(cwd, { projectKind, env })
        return { content: [{ type: 'text', text: r.text }], details: { ok: r.ok, count: r.diagnostics.length, durationMs: r.durationMs } }
      },
    })

    pi.registerTool({
      name: 'code_symbols',
      label: '代码语义大纲',
      description: '基于 AST 解析项目符号大纲（组件、函数、类型、API 路由、SQL 数据表）或精准查找某个符号的全仓跨文件引用位置，修改大型项目时避免漏改引用。',
      parameters: Type.Object({
        action: Type.Union([Type.Literal('outline'), Type.Literal('references')], { description: 'outline（查架构符号大纲）或 references（查符号全仓引用）' }),
        dir: Type.Optional(Type.String({ description: '可选子目录，如 src 或 server' })),
        symbol: Type.Optional(Type.String({ description: 'action=references 时必填：要追踪的符号名' })),
      }),
      async execute(_id, params) {
        if (params?.action === 'references') {
          const r = findSymbolReferences(cwd, params.symbol, { dir: params.dir || '' })
          if (r.error) return { content: [{ type: 'text', text: r.error }], details: { ok: false } }
          if (!r.matches.length) return { content: [{ type: 'text', text: `未找到符号「${params.symbol}」的引用。` }], details: { count: 0 } }
          return {
            content: [{ type: 'text', text: `符号「${params.symbol}」共找到 ${r.matches.length} 处引用${r.truncated ? '（已截断）' : ''}：\n${r.matches.join('\n')}` }],
            details: { count: r.matches.length },
          }
        }
        const info = analyzeWorkspaceCode(cwd, { dir: params?.dir || '' })
        const lines = []
        if (info.routes.length) {
          lines.push('### 后端 API 路由')
          lines.push(...info.routes.slice(0, 40).map((r) => `- ${r.method} ${r.path} (${r.file}:${r.line})`))
        }
        if (info.tables.length) {
          lines.push('### SQL 数据表')
          lines.push(...info.tables.slice(0, 30).map((t) => `- ${t.name} (${t.file}:${t.line})`))
        }
        lines.push('### 导出符号与组件')
        const syms = info.symbols.filter((s) => s.exported).slice(0, 60)
        lines.push(...(syms.length ? syms.map((s) => `- ${s.name} [${s.kind}] — ${s.file}:${s.line}`) : ['（暂无导出符号）']))
        return { content: [{ type: 'text', text: lines.join('\n') }], details: { symbols: syms.length, routes: info.routes.length, tables: info.tables.length } }
      },
    })

    pi.registerTool({
      name: 'inspect_source',
      label: '探测数据源结构',
      description: '查看当前应用已绑定数据源的真实参数定义与服务端实测返回 JSON 结构，严禁凭空猜测外部接口字段名。',
      parameters: Type.Object({
        key: Type.Optional(Type.String({ description: '可选：指定数据源调用名（key）；留空查看全部已绑定数据源' })),
      }),
      async execute(_id, params) {
        const text = readSourceInspectSnapshot(agentDir, params?.key || '')
        return { content: [{ type: 'text', text }], details: {} }
      },
    })
  }
}
