// 声明式项目工作流引擎（zhiye.yaml）与动态项目结构自探测：
// 让任何技术栈（Node / Python / Java / Go / Rust / 自定义脚本）都能通过项目自己的 zhiye.yaml
// 或自动结构探测驱动诊断、构建、测试、接口实测与启动，彻底解耦固定模板与写死流程。
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'

const FRONTEND_DIR_CANDIDATES = ['frontend', 'web', 'client', 'ui', 'portal', 'admin']
const BACKEND_DIR_CANDIDATES = ['backend', 'server', 'api', 'service', 'app']

function stripQuotes(s) {
  const t = String(s ?? '').trim()
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
    return t.slice(1, -1)
  }
  return t
}

function parseScalar(raw) {
  const s = stripQuotes(raw)
  if (s === 'true') return true
  if (s === 'false') return false
  if (/^-?\d+$/.test(s)) return Number(s)
  return s
}

/**
 * 轻量零依赖 YAML 子集解析器（专用于解析 zhiye.yaml 中的 workflow / verify / build / start / dev / diagnostics）。
 * 支持缩进嵌套映射与对象数组（`- name: ...` / `- command`），自动跳过注释与空行。
 */
export function parseSimpleYaml(text) {
  const rawLines = String(text || '').split(/\r?\n/)
  const lines = []
  for (const raw of rawLines) {
    // 去掉行尾注释（不在引号内）
    let clean = ''
    let q = null
    for (let i = 0; i < raw.length; i++) {
      const ch = raw[i]
      if (q) {
        clean += ch
        if (ch === q && raw[i - 1] !== '\\') q = null
      } else if (ch === '"' || ch === "'") {
        q = ch
        clean += ch
      } else if (ch === '#' && (i === 0 || /\s/.test(raw[i - 1]))) {
        break
      } else {
        clean += ch
      }
    }
    if (!clean.trim()) continue
    const indent = clean.match(/^ */)[0].length
    lines.push({ indent, text: clean.trim() })
  }

  let idx = 0
  function parseBlock(minIndent) {
    if (idx >= lines.length || lines[idx].indent < minIndent) return {}
    if (lines[idx].text.startsWith('- ') || lines[idx].text === '-') {
      return parseList(lines[idx].indent)
    }
    const obj = {}
    const baseIndent = lines[idx].indent
    while (idx < lines.length && lines[idx].indent === baseIndent) {
      const line = lines[idx].text
      if (line.startsWith('- ')) break
      const m = /^([A-Za-z0-9_.-]+)\s*:(.*)$/.exec(line)
      if (!m) { idx++; continue }
      const key = m[1]
      const rest = m[2].trim()
      idx++
      if (rest) {
        obj[key] = parseScalar(rest)
      } else if (idx < lines.length && lines[idx].indent > baseIndent) {
        obj[key] = parseBlock(lines[idx].indent)
      } else {
        obj[key] = null
      }
    }
    return obj
  }

  function parseList(listIndent) {
    const arr = []
    while (idx < lines.length && lines[idx].indent === listIndent && (lines[idx].text.startsWith('- ') || lines[idx].text === '-')) {
      const after = lines[idx].text.slice(1).trim()
      idx++
      if (!after) {
        arr.push(idx < lines.length && lines[idx].indent > listIndent ? parseBlock(lines[idx].indent) : null)
        continue
      }
      const kv = /^([A-Za-z0-9_.-]+)\s*:(.*)$/.exec(after)
      if (kv) {
        const item = {}
        const k = kv[1]
        const v = kv[2].trim()
        if (v) item[k] = parseScalar(v)
        else if (idx < lines.length && lines[idx].indent > listIndent + 2) item[k] = parseBlock(lines[idx].indent)
        else item[k] = null
        while (idx < lines.length && lines[idx].indent > listIndent) {
          const subIndent = lines[idx].indent
          const sub = lines[idx].text
          const sm = /^([A-Za-z0-9_.-]+)\s*:(.*)$/.exec(sub)
          if (!sm) { idx++; continue }
          const sk = sm[1]
          const sv = sm[2].trim()
          idx++
          if (sv) item[sk] = parseScalar(sv)
          else if (idx < lines.length && lines[idx].indent > subIndent) item[sk] = parseBlock(lines[idx].indent)
          else item[sk] = null
        }
        arr.push(item)
      } else {
        arr.push(parseScalar(after))
      }
    }
    return arr
  }

  return parseBlock(0)
}

function normalizeStep(item, fallbackName) {
  if (!item) return null
  if (typeof item === 'string') {
    return { name: fallbackName || item.slice(0, 40), command: item.trim(), cwd: '' }
  }
  if (typeof item === 'object' && item.command) {
    return {
      name: String(item.name || fallbackName || item.command).trim(),
      command: String(item.command).trim(),
      cwd: String(item.cwd || item.dir || '').trim(),
      timeoutMs: Number(item.timeoutMs || item.timeout_ms || 0) || undefined,
    }
  }
  return null
}

function normalizeStepList(node, defaultPrefix) {
  if (!node) return []
  const raw = Array.isArray(node) ? node : Array.isArray(node.steps) ? node.steps : node.command ? [node] : []
  return raw.map((it, i) => normalizeStep(it, `${defaultPrefix} ${i + 1}`)).filter(Boolean)
}

/** 读取并规范化工作区根目录下的 zhiye.yaml（或 .zhiye.yaml）中的开发/校验/启动配置。 */
export function loadProjectWorkflow(cwd) {
  for (const name of ['zhiye.yaml', 'zhiye.yml', '.zhiye.yaml', '.zhiye.yml']) {
    const file = path.join(cwd, name)
    if (!existsSync(file)) continue
    try {
      const raw = readFileSync(file, 'utf8')
      const doc = parseSimpleYaml(raw)
      if (!doc || typeof doc !== 'object') continue
      const wf = (doc.workflow && typeof doc.workflow === 'object') ? doc.workflow : doc
      const diagnostics = normalizeStepList(wf.diagnostics || doc.diagnostics, '诊断')
      const verify = normalizeStepList(wf.verify || doc.verify, '校验')
      const build = normalizeStepList(wf.build || doc.build, '构建')
      const test = normalizeStepList(wf.test || doc.test, '测试')
      const startNode = wf.start || doc.start
      let start = null
      if (typeof startNode === 'string' && startNode.trim()) {
        start = { command: startNode.trim(), cwd: '', health: '/api/health', timeoutMs: 30_000 }
      } else if (startNode && typeof startNode === 'object' && startNode.command) {
        start = {
          command: String(startNode.command).trim(),
          cwd: String(startNode.cwd || startNode.dir || '').trim(),
          health: String(startNode.health || startNode.healthPath || '/api/health').trim(),
          timeoutMs: Number(startNode.timeoutMs || startNode.timeout_ms || 30_000) || 30_000,
        }
      }
      const devNode = wf.dev || doc.dev
      const dev = typeof devNode === 'string'
        ? { command: devNode.trim(), cwd: '' }
        : (devNode && typeof devNode === 'object' && devNode.command)
          ? { command: String(devNode.command).trim(), cwd: String(devNode.cwd || devNode.dir || '').trim() }
          : null

      return {
        file: name,
        runtime: String(wf.runtime || doc.runtime || '').trim() || null,
        frontendDir: String(wf.frontendDir || wf.frontend_dir || doc.frontendDir || '').trim() || null,
        backendDir: String(wf.backendDir || wf.backend_dir || doc.backendDir || '').trim() || null,
        diagnostics,
        verify,
        build,
        test,
        start,
        dev,
        raw: doc,
      }
    } catch {
      return null
    }
  }
  return null
}

function readJsonSafe(file) {
  try { return JSON.parse(readFileSync(file, 'utf8')) } catch { return null }
}

function listFirstLevelDirs(cwd) {
  try {
    return readdirSync(cwd, { withFileTypes: true })
      .filter((d) => d.isDirectory() && !d.name.startsWith('.') && !['node_modules', 'dist', 'build', 'target', '__pycache__', 'venv', '.venv'].includes(d.name))
      .map((d) => d.name)
  } catch {
    return []
  }
}

function isFrontendPkg(pkg) {
  if (!pkg || typeof pkg !== 'object') return false
  const deps = { ...(pkg.dependencies || {}), ...(pkg.devDependencies || {}) }
  return Boolean(deps.vite || deps.vue || deps.react || deps.next || deps.nuxt || deps.svelte || deps['@angular/core'])
}

function isNodeBackendDir(dirAbs) {
  for (const f of ['server/index.mjs', 'server/index.js', 'server.mjs', 'server.js', 'index.mjs', 'app.mjs']) {
    if (existsSync(path.join(dirAbs, f))) return true
  }
  const pkg = readJsonSafe(path.join(dirAbs, 'package.json'))
  if (!pkg) return false
  const deps = { ...(pkg.dependencies || {}), ...(pkg.devDependencies || {}) }
  if (deps.express || deps.fastify || deps.koa || deps.hono || deps['@nestjs/core']) return true
  return Boolean(pkg.scripts?.start && !isFrontendPkg(pkg))
}

function hasPythonBackend(dirAbs) {
  if (existsSync(path.join(dirAbs, 'requirements.txt')) || existsSync(path.join(dirAbs, 'pyproject.toml')) || existsSync(path.join(dirAbs, 'manage.py'))) return true
  return ['main.py', 'app.py', 'server.py'].some((f) => existsSync(path.join(dirAbs, f)))
}

/**
 * 动态探测工作区实际包含的前端目录、后端语言/目录与测试脚本（不绑定任何固定模板名）。
 */
export function discoverWorkspaceLayout(cwd) {
  const wf = loadProjectWorkflow(cwd)
  const topDirs = listFirstLevelDirs(cwd)
  const orderedFront = [...new Set([...(wf?.frontendDir ? [wf.frontendDir] : []), ...FRONTEND_DIR_CANDIDATES, ...topDirs])]
  const orderedBack = [...new Set([...(wf?.backendDir ? [wf.backendDir] : []), ...BACKEND_DIR_CANDIDATES, ...topDirs])]

  let frontendDir = null
  for (const d of orderedFront) {
    const abs = path.join(cwd, d)
    if (!existsSync(path.join(abs, 'package.json'))) continue
    const pkg = readJsonSafe(path.join(abs, 'package.json'))
    if (isFrontendPkg(pkg)) { frontendDir = d; break }
  }
  if (!frontendDir && existsSync(path.join(cwd, 'package.json')) && isFrontendPkg(readJsonSafe(path.join(cwd, 'package.json')))) {
    frontendDir = ''
  }

  let backend = null
  const probeBack = (rel) => {
    const abs = rel ? path.join(cwd, rel) : cwd
    if (existsSync(path.join(abs, 'pom.xml'))) return { runtime: 'java-maven', dir: rel }
    if (existsSync(path.join(abs, 'build.gradle')) || existsSync(path.join(abs, 'build.gradle.kts'))) return { runtime: 'java-gradle', dir: rel }
    if (existsSync(path.join(abs, 'go.mod'))) return { runtime: 'go', dir: rel }
    if (existsSync(path.join(abs, 'Cargo.toml'))) return { runtime: 'rust', dir: rel }
    if (hasPythonBackend(abs)) return { runtime: 'python', dir: rel }
    if (isNodeBackendDir(abs)) return { runtime: 'node', dir: rel }
    return null
  }

  // 优先探测显式后端子目录，再探测根目录
  for (const d of orderedBack) {
    if (d === frontendDir) continue
    const hit = probeBack(d)
    if (hit) { backend = hit; break }
  }
  if (!backend) {
    backend = probeBack('')
  }

  // 探测所有需要静态类型/编译诊断的目录
  const diagDirs = new Set([''])
  if (frontendDir) diagDirs.add(frontendDir)
  if (backend?.dir) diagDirs.add(backend.dir)
  for (const d of topDirs) {
    const abs = path.join(cwd, d)
    if (existsSync(path.join(abs, 'tsconfig.json')) || existsSync(path.join(abs, 'pom.xml')) || existsSync(path.join(abs, 'go.mod')) || hasPythonBackend(abs)) {
      diagDirs.add(d)
    }
  }

  return {
    workflow: wf,
    frontendDir,
    backend,
    diagDirs: [...diagDirs],
  }
}
