// skill 项目的校验：SKILL.md 格式、引用文件、脚本语法。不构建、不做冒烟测试。
import { existsSync, readFileSync, readdirSync, lstatSync, mkdtempSync, rmSync, statSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { run } from './util.mjs'

export const NAME_RE = /^[a-z0-9][a-z0-9-]{0,63}$/

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
  let fm
  try { fm = parseFrontmatter(readFileSync(file, 'utf8')) } catch (e) { return { ok: false, detail: e.message } }
  const errs = []
  const { name, description } = fm.data
  if (!name) errs.push('frontmatter 缺少 name')
  else if (!NAME_RE.test(String(name))) errs.push(`name 格式不正确：「${name}」。只能用小写字母、数字和连字符，以字母或数字开头，不超过 64 个字符`)
  if (!description || !String(description).trim()) errs.push('frontmatter 缺少 description（说明这个 skill 做什么、什么时候使用）')
  else if (String(description).length > 1024) errs.push(`description 太长：${String(description).length} 个字符，最多 1024 个`)
  if (!fm.body.trim()) errs.push('SKILL.md 正文是空的：请写明用途、输入、步骤和输出格式')
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
