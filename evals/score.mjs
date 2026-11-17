// 纯净确定性评分器：检查项目目录与构建产物的结构、可执行代码契约与实机浏览表现。
// 注意：对 Web/UI 应用（template/vue/static），只检查可执行源码（排除 README/md 等文档），防止文档关键词绕过评分。
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'

const CODE_FILE_RE = /\.(tsx?|jsx?|mjs|vue|html|css|sql|py)$/i
const DOC_FILE_RE = /\.(md|txt)$/i

function walkFiles(dir, prefix = '', out = []) {
  if (!existsSync(dir)) return out
  for (const name of readdirSync(dir)) {
    if (name === '.git' || name === 'node_modules' || name === '.zhiye') continue
    const abs = path.join(dir, name)
    const rel = prefix ? `${prefix}/${name}` : name
    const st = statSync(abs)
    if (st.isDirectory()) walkFiles(abs, rel, out)
    else if (st.isFile()) out.push(rel)
  }
  return out
}

function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:\\])\/\/[^\n]*/g, '$1 ')
}

export function scoreWorkspace(dir, spec, verifyResult = null) {
  const files = walkFiles(dir)
  const errors = []
  const ex = spec.expect || {}

  if (ex.minFiles && files.length < ex.minFiles) {
    errors.push(`文件数过少：期望 >= ${ex.minFiles}，实际 ${files.length}`)
  }
  for (const req of ex.requireFiles || []) {
    if (!files.includes(req)) errors.push(`缺少必需文件：${req}`)
  }
  for (const forb of ex.forbidFiles || []) {
    if (files.includes(forb)) errors.push(`不应存在文件：${forb}`)
  }
  for (const pfx of ex.requireGlobPrefix || []) {
    if (!files.some((f) => f.startsWith(pfx))) errors.push(`缺少前缀 ${pfx} 下的文件`)
  }

  let codeCorpus = ''
  let docCorpus = ''
  for (const rel of files) {
    if (CODE_FILE_RE.test(rel)) {
      try { codeCorpus += '\n' + readFileSync(path.join(dir, rel), 'utf8') } catch {}
    } else if (DOC_FILE_RE.test(rel)) {
      try { docCorpus += '\n' + readFileSync(path.join(dir, rel), 'utf8') } catch {}
    }
  }
  const cleanCode = stripComments(codeCorpus)

  if (Array.isArray(ex.mustContainCode) && ex.mustContainCode.length) {
    const anyMatch = ex.mustContainCode.some((kw) => cleanCode.includes(kw))
    if (!anyMatch) errors.push(`源码缺少关键调用或语法：${ex.mustContainCode.join(' 或 ')}`)
  }
  if (Array.isArray(ex.mustContainAllCode) && ex.mustContainAllCode.length) {
    for (const kw of ex.mustContainAllCode) {
      if (!cleanCode.includes(kw)) errors.push(`源码缺少必需实现机制：${kw}`)
    }
  }
  for (const forb of ex.forbidCode || []) {
    if (cleanCode.includes(forb)) errors.push(`出现禁止写法：${forb}`)
  }

  // Web/UI 应用只核查源码文本，不允许靠 README/Markdown 文档混过业务关键词检查
  const isDocProject = ex.projectKind === 'skill' || ex.projectKind === 'script'
  const textTarget = isDocProject ? `${codeCorpus}\n${docCorpus}` : cleanCode
  for (const txt of ex.mustContainText || []) {
    if (!textTarget.includes(txt)) errors.push(`可执行源码缺少预期业务关键词：${txt}`)
  }

  if (ex.requireInteractive) {
    const hasState = /\b(useState|useReducer|ref|reactive)\s*\(|document\.(getElementById|querySelector)/.test(cleanCode)
    const hasHandler = /\b(onClick|onChange|onSubmit|onInput|addEventListener)\b|@(click|change|submit|input)|v-model/.test(cleanCode)
    if (!hasState || !hasHandler) {
      errors.push('源码缺少真实的交互状态与事件绑定（需要状态管理 + 交互事件处理）')
    }
  }

  const verifyOk = verifyResult ? (verifyResult.checks || []).every((c) => c.ok) : true
  if (verifyResult && !verifyOk) {
    const failed = (verifyResult.checks || []).filter((c) => !c.ok).map((c) => `${c.name}: ${c.detail}`).join('; ')
    errors.push(`平台校验未通过：${failed}`)
  }
  if (verifyResult?.liveFlow && !verifyResult.liveFlow.ok) {
    errors.push(`浏览器实机交互未通过：${verifyResult.liveFlow.detail}`)
  }

  return {
    id: spec.id,
    name: spec.name,
    passed: errors.length === 0,
    fileCount: files.length,
    verifyOk,
    errors,
  }
}

export function summarizeResults(results) {
  const total = results.length
  const passed = results.filter((r) => r.passed).length
  return {
    total,
    passed,
    passRate: total ? Number((passed / total).toFixed(4)) : 0,
    results,
  }
}
