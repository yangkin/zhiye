// 纯净确定性评分器：检查项目目录与构建产物的结构、契约和冒烟表现。
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'

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

  let corpus = ''
  for (const rel of files) {
    if (!/\.(tsx?|jsx?|mjs|vue|html|css|sql|json|md|py)$/i.test(rel)) continue
    try { corpus += '\n' + readFileSync(path.join(dir, rel), 'utf8') } catch {}
  }

  if (Array.isArray(ex.mustContainCode) && ex.mustContainCode.length) {
    const anyMatch = ex.mustContainCode.some((kw) => corpus.includes(kw))
    if (!anyMatch) errors.push(`代码缺少关键调用或语法：${ex.mustContainCode.join(' 或 ')}`)
  }
  for (const forb of ex.forbidCode || []) {
    if (corpus.includes(forb)) errors.push(`出现禁止写法：${forb}`)
  }
  for (const txt of ex.mustContainText || []) {
    if (!corpus.includes(txt)) errors.push(`缺少预期业务关键词：${txt}`)
  }

  const verifyOk = verifyResult ? (verifyResult.checks || []).every((c) => c.ok) : true
  if (verifyResult && !verifyOk) {
    const failed = (verifyResult.checks || []).filter((c) => !c.ok).map((c) => `${c.name}: ${c.detail}`).join('; ')
    errors.push(`平台校验未通过：${failed}`)
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
