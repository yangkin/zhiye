// skill 项目的校验：SKILL.md 格式、引用文件、脚本语法。不构建、不做冒烟测试。
import { existsSync, readFileSync, readdirSync, lstatSync, mkdtempSync, rmSync, statSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { run } from './util.mjs'

export const NAME_RE = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/
export const VERSION_IN_NAME_RE = /(?:^|-)v\d+(?:-\d+)*$|-\d+-\d+(?:-\d+)*$/
export const MAX_DESCRIPTION_LEN = 120
export const MAX_SKILL_MD_LINES = 500
const VAGUE_COMPATIBILITY_RE = /^(?:仅?兼容飞书|兼容飞书|飞书|通用|全平台通用|无|无特殊要求|无依赖|不限|all|any|general|none|n\/a)[。.!！\s]*$/i

export function isValidSkillName(name) {
  if (!name || typeof name !== 'string') return false
  return NAME_RE.test(name) && !name.includes('--') && !VERSION_IN_NAME_RE.test(name)
}

export function isVagueCompatibility(value) {
  if (value === undefined || value === null) return false
  const s = String(value).trim()
  return !s || VAGUE_COMPATIBILITY_RE.test(s)
}

/** 极简 YAML frontmatter 解析：顶层 key: value，支持引号和 | / > 块。返回 { data, body } 或抛错。 */
export function parseFrontmatter(text) {
  const src = text.replace(/^\uFEFF/, '')
  const m = /^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(src)
  if (!m) throw new Error('SKILL.md 开头没有 YAML frontmatter（第一行应为 ---，并以另一行 --- 结束）')
  const lines = m[1].split(/\r?\n/)
  const data = {}
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (!line.trim() || /^\s*#/.test(line)) continue
    if (/^\s/.test(line)) throw new Error(`frontmatter 第 ${i + 2} 行缩进不正确：${line.trim()}`)
    const kv = /^([A-Za-z0-9_-]+)\s*:(.*)$/.exec(line)
    if (!kv) throw new Error(`frontmatter 第 ${i + 2} 行不是 key: value 格式：${line}`)
    const key = kv[1]
    let v = kv[2].trim()
    if (/^[|>][+-]?$/.test(v)) {
      const folded = v[0] === '>'
      const block = []
      while (i + 1 < lines.length && (/^\s/.test(lines[i + 1]) || !lines[i + 1].trim())) block.push(lines[++i])
      const indent = Math.min(...block.filter((l) => l.trim()).map((l) => /^\s*/.exec(l)[0].length), Infinity)
      const body = block.map((l) => l.slice(indent === Infinity ? 0 : indent))
      v = (folded ? body.join(' ').replace(/\s+/g, ' ') : body.join('\n')).trim()
    } else {
      // 普通值可以折行到后续缩进行
      while (i + 1 < lines.length && /^\s+\S/.test(lines[i + 1]) && !/^["']/.test(v)) v += ' ' + lines[++i].trim()
      if (/^"/.test(v)) {
        if (!/"$/.test(v) || v.length < 2) throw new Error(`frontmatter 里 ${key} 的引号没有闭合`)
        try { v = JSON.parse(v) } catch { v = v.slice(1, -1) }
      } else if (/^'/.test(v)) {
        if (!/'$/.test(v) || v.length < 2) throw new Error(`frontmatter 里 ${key} 的引号没有闭合`)
        v = v.slice(1, -1).replace(/''/g, "'")
      } else v = v.replace(/\s+#.*$/, '')
    }
    if (Object.prototype.hasOwnProperty.call(data, key)) throw new Error(`frontmatter 里 ${key} 重复出现`)
    data[key] = v
  }
  return { data, body: src.slice(m[0].length) }
}

export function checkSkillMd(cwd) {
  const file = path.join(cwd, 'SKILL.md')
  if (!existsSync(file)) return { ok: false, detail: '工作区根目录没有 SKILL.md' }
  const raw = readFileSync(file, 'utf8')
  const lineCount = raw.replace(/\r\n/g, '\n').split('\n').length
  let fm
  try { fm = parseFrontmatter(raw) } catch (e) { return { ok: false, detail: e.message } }
  const errs = []
  const { name, description, compatibility } = fm.data
  if (lineCount > MAX_SKILL_MD_LINES) {
    errs.push(`SKILL.md 超过 ${MAX_SKILL_MD_LINES} 行上限（当前 ${lineCount} 行），请将长规范、模板或示例拆到 references/ 目录`)
  }
  if (!name) errs.push('frontmatter 缺少 name')
  else {
    const n = String(name)
    if (!NAME_RE.test(n) || n.includes('--')) {
      errs.push(`name 格式不正确：「${n}」。仅允许小写英文字母、数字和连字符 "-"，不能以 "-" 开头/结尾或连续 "--"，不超过 64 个字符`)
    } else if (VERSION_IN_NAME_RE.test(n)) {
      errs.push(`name 格式不正确：「${n}」。标识不能包含版本号（如 -v1、-1-0），建议由 2~4 个英文词段组成`)
    }
  }
  if (!description || !String(description).trim()) {
    errs.push('frontmatter 缺少 description（说明这个 skill 做什么、何时使用）')
  } else {
    const descLen = [...String(description).trim()].length
    if (descLen > MAX_DESCRIPTION_LEN) {
      errs.push(`description 太长：当前 ${descLen} 字，SkillHub 规范要求不超过 ${MAX_DESCRIPTION_LEN} 字`)
    }
  }
  if (Object.prototype.hasOwnProperty.call(fm.data, 'compatibility') && isVagueCompatibility(compatibility)) {
    errs.push(`compatibility 描述过于笼统（「${String(compatibility ?? '').trim()}」）：请写明所需运行环境、工具或接口依赖，不得只写「兼容飞书」等泛称`)
  }
  if (!fm.body.trim()) errs.push('SKILL.md 正文是空的：请写明功能、适用场景、使用步骤及依赖的权限或其它技能')
  return errs.length ? { ok: false, detail: errs.join('\n') } : { ok: true, detail: '' }
}

/** 从 SKILL.md 里找出引用的相对路径。 */
export function findReferences(md) {
  const refs = new Set()
  const add = (raw) => {
    let p = String(raw).trim().replace(/^<|>$/g, '')
    p = p.split('#')[0].split('?')[0]
    p = p.replace(/[.,，。；;:：、)）\]】'"`]+$/, '')
    if (!p || /^[a-z][a-z0-9+.-]*:/i.test(p) || p.startsWith('/') || p.startsWith('#')) return
    if (/[<>*{}$]/.test(p)) return // 占位符 / 通配符
    try { p = decodeURIComponent(p) } catch {}
    refs.add(p.replace(/^\.\//, ''))
  }
  const noFrontmatter = md.replace(/^---[\s\S]*?\n---[ \t]*\r?\n/, '')
  for (const m of noFrontmatter.matchAll(/!?\[[^\]]*\]\(\s*([^)\s]+)(?:\s+["'][^)]*["'])?\s*\)/g)) add(m[1])
  for (const m of noFrontmatter.matchAll(/(?:^|[^\w./-])((?:\.\/)?(?:references|scripts)\/[^\s`'"()（）<>\[\]，。；、]+)/gm)) add(m[1])
  return [...refs]
}

export function checkReferences(cwd) {
  const file = path.join(cwd, 'SKILL.md')
  if (!existsSync(file)) return { ok: false, skipped: true, detail: '没有 SKILL.md，已跳过' }
  const refs = findReferences(readFileSync(file, 'utf8'))
  const root = path.resolve(cwd)
  const missing = []
  for (const r of refs) {
    const abs = path.resolve(root, r)
    const rel = path.relative(root, abs)
    if (rel.startsWith('..') || path.isAbsolute(rel)) { missing.push(`${r}（在工作区以外）`); continue }
    if (!existsSync(abs)) missing.push(r)
  }
  if (missing.length) return { ok: false, detail: `SKILL.md 引用了不存在的文件：\n${missing.map((m) => `- ${m}`).join('\n')}` }
  return { ok: true, detail: refs.length ? `已检查 ${refs.length} 个引用` : '' }
}

function listFiles(dir, sub = '') {
  const out = []
  for (const name of readdirSync(path.join(dir, sub))) {
    if (['.git', '.zhiye', 'node_modules', '__pycache__'].includes(name)) continue
    const rel = sub ? `${sub}/${name}` : name
    const st = lstatSync(path.join(dir, rel))
    if (st.isDirectory()) out.push(...listFiles(dir, rel))
    else if (st.isFile()) out.push(rel)
  }
  return out
}

const PY_COMPILE = 'import py_compile,sys\ntry:\n  py_compile.compile(sys.argv[1], cfile=sys.argv[2], doraise=True)\nexcept py_compile.PyCompileError as e:\n  print(e.msg); sys.exit(1)\n'

export async function checkScripts(cwd, env = process.env) {
  const files = listFiles(cwd).filter((f) => /\.(m?js|cjs|py|sh)$/.test(f))
  if (!files.length) return { ok: true, skipped: true, detail: '没有脚本文件，已跳过' }
  const tmp = mkdtempSync(path.join(os.tmpdir(), 'zhiye-pyc-'))
  const errs = []
  try {
    for (const f of files) {
      let r
      if (/\.py$/.test(f)) r = await run('python3', ['-c', PY_COMPILE, f, path.join(tmp, 'x.pyc')], { cwd, env: { ...env, PYTHONDONTWRITEBYTECODE: '1', PYTHONPYCACHEPREFIX: tmp }, timeoutMs: 30_000 })
      else if (/\.sh$/.test(f)) r = await run('bash', ['-n', f], { cwd, env, timeoutMs: 30_000 })
      else r = await run(process.execPath, ['--check', f], { cwd, env, timeoutMs: 30_000 })
      if (r.code !== 0) errs.push(`【${f}】\n${r.out.trim() || `退出码 ${r.code}`}`)
    }
  } finally { rmSync(tmp, { recursive: true, force: true }) }
  // 防御：不要在工作区留下 __pycache__
  for (const f of listDirs(cwd)) if (path.basename(f) === '__pycache__') rmSync(path.join(cwd, f), { recursive: true, force: true })
  return errs.length ? { ok: false, detail: `脚本有语法错误：\n${errs.join('\n\n')}` } : { ok: true, detail: `已检查 ${files.length} 个脚本` }
}

function listDirs(dir, sub = '') {
  const out = []
  for (const name of readdirSync(path.join(dir, sub))) {
    if (['.git', '.zhiye', 'node_modules'].includes(name)) continue
    const rel = sub ? `${sub}/${name}` : name
    if (statSync(path.join(dir, rel)).isDirectory() && !lstatSync(path.join(dir, rel)).isSymbolicLink()) { out.push(rel); out.push(...listDirs(dir, rel)) }
  }
  return out
}

const JUNK_DIRS = new Set(['tmp', 'temp', 'logs', 'cache', '.cache', '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', 'coverage', 'output'])
const JUNK_FILE_RE = /^(?:\.DS_Store|Thumbs\.db|\.env(?:\..+)?|(?:debug|test[_-]?output).*)$/i
const JUNK_EXT_RE = /\.(?:log|tmp|temp|bak|swp|swo|orig|pyc|pyo)$/i
const PLACEHOLDER_RE = /^(?:your[_-]|xxx+|placeholder|example|sample|dummy|test|changeme|replace|demo|\*+|<[^>]+>|\$\{[^}]+\}|%[a-z_]+%)/i

const SECRET_RULES = [
  { label: '飞书用户 ID（ou_xxx）', re: /\bou_[a-zA-Z0-9]{16,}\b/g },
  { label: '飞书应用 ID（cli_xxx）', re: /\bcli_[a-f0-9]{16,}\b/g },
  { label: '私钥内容', re: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/g },
  { label: 'JWT 令牌', re: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g },
  { label: 'API Key / Token', re: /\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b/g },
  { label: 'Bearer 令牌', re: /\bBearer\s+[A-Za-z0-9._-]{20,}\b/g },
]
const ASSIGN_SECRET_RE = /(?:^|[\s,{])["']?(password|passwd|pwd|app_?secret|api_?key|access_?token|auth_?token|secret_?key|private_?key)["']?\s*[:=]\s*["']([^"'\r\n\s]{8,})["']/gi

function listAllPaths(dir, sub = '') {
  const out = []
  for (const name of readdirSync(path.join(dir, sub))) {
    if (['.git', '.zhiye', 'node_modules'].includes(name)) continue
    const rel = sub ? `${sub}/${name}` : name
    const st = lstatSync(path.join(dir, rel))
    if (st.isDirectory()) {
      out.push({ rel, isDir: true })
      out.push(...listAllPaths(dir, rel))
    } else if (st.isFile()) {
      out.push({ rel, isDir: false, size: st.size })
    }
  }
  return out
}

/** 安全与文件清理检查：拦截敏感凭证/飞书用户 ID 以及缓存/调试日志/临时输出等无关文件 */
export function checkSecurityAndCleanliness(cwd) {
  const entries = listAllPaths(cwd)
  const junk = []
  const secrets = []

  for (const e of entries) {
    const segs = e.rel.split('/')
    const base = segs[segs.length - 1]
    if (e.isDir) {
      if (JUNK_DIRS.has(base.toLowerCase())) junk.push(`${e.rel}/（无关缓存或临时目录）`)
      continue
    }
    if (segs.slice(0, -1).some((s) => JUNK_DIRS.has(s.toLowerCase()))) continue
    if (JUNK_FILE_RE.test(base) || JUNK_EXT_RE.test(base) || base.endsWith('~')) {
      junk.push(e.rel)
      continue
    }
    if (e.size > 512 * 1024) continue
    const buf = readFileSync(path.join(cwd, e.rel))
    if (buf.includes(0)) continue // 跳过二进制文件
    const text = buf.toString('utf8')
    const hitLabels = new Set()
    for (const rule of SECRET_RULES) {
      rule.re.lastIndex = 0
      if (rule.re.test(text)) hitLabels.add(rule.label)
    }
    ASSIGN_SECRET_RE.lastIndex = 0
    for (const m of text.matchAll(ASSIGN_SECRET_RE)) {
      const val = m[2]
      if (!PLACEHOLDER_RE.test(val) && !val.includes('<') && !val.includes('${')) {
        hitLabels.add(`明文凭证字段 ${m[1]}`)
      }
    }
    if (hitLabels.size) secrets.push(`${e.rel}：检测到 ${[...hitLabels].join('、')}`)
  }

  const errs = []
  if (secrets.length) {
    errs.push(`检测到敏感信息（SkillHub 禁止包含凭证、密码、token、飞书用户 ID 等）：\n${secrets.map((s) => `- ${s}`).join('\n')}`)
  }
  if (junk.length) {
    errs.push(`存在无关的缓存、调试日志或临时文件，请清理后再提交：\n${junk.map((j) => `- ${j}`).join('\n')}`)
  }
  return errs.length ? { ok: false, detail: errs.join('\n\n') } : { ok: true, detail: '' }
}
