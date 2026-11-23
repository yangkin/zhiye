import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, cpSync, existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { verify } from '../src/verify.mjs'
import { parseFrontmatter, findReferences } from '../src/skill.mjs'
import { checkToolCall } from '../src/guard.mjs'

const TEMPLATE = path.resolve(import.meta.dirname, '../../packages/skill-template')

const GOOD_MD = `---
name: weekly-report
description: "根据本周工作记录生成四段式周报。用户需要写周报、整理本周工作时使用。"
---

# 周报生成

## 输入
本周的工作记录（任意格式的文字）。

## 步骤
1. 按 [周报模板](references/template.md) 整理成四段。
2. 运行 \`python3 scripts/count_words.py report.md\` 统计字数（见 scripts/count_words.py 的用法说明）。

## 输出
Markdown 周报。参考 https://example.com/guide 。
`

function pkg(files) {
  const w = mkdtempSync(path.join(os.tmpdir(), 'skill-'))
  mkdirSync(path.join(w, '.git'))
  for (const [f, c] of Object.entries(files)) { mkdirSync(path.dirname(path.join(w, f)), { recursive: true }); writeFileSync(path.join(w, f), c) }
  return w
}
const GOOD = {
  'SKILL.md': GOOD_MD,
  'references/template.md': '# 模板',
  'scripts/count_words.py': '# 用法：python3 scripts/count_words.py 文件\nimport sys\nprint(len(open(sys.argv[1]).read()))\n',
  'scripts/helper.mjs': '// 用法：node scripts/helper.mjs\nconsole.log(1)\n',
  'scripts/run.sh': '#!/bin/sh\necho hi\n',
}
const byName = (r) => Object.fromEntries(r.checks.map((c) => [c.name, c]))

test('frontmatter 解析', () => {
  assert.deepEqual(parseFrontmatter('---\nname: a-b\ndescription: >\n  第一行\n  第二行\n---\n正文').data, { name: 'a-b', description: '第一行 第二行' })
  assert.equal(parseFrontmatter("---\nname: 'x'\ndescription: a # 注释\n---\n").data.description, 'a')
  assert.throws(() => parseFrontmatter('# 没有 frontmatter'))
  assert.throws(() => parseFrontmatter('---\nname: "x\n---\n'))
})

test('找引用：markdown 链接和 references/、scripts/ 路径，忽略外链', () => {
  assert.deepEqual(findReferences(GOOD_MD).sort(), ['references/template.md', 'scripts/count_words.py'])
})

test('合格的 skill 包通过，结果格式正确，不留 __pycache__', async () => {
  const w = pkg(GOOD)
  const r = await verify(w, { projectKind: 'skill' })
  assert.equal(r.ok, true, JSON.stringify(r.checks))
  assert.deepEqual(r.checks.map((c) => c.name), ['SKILL.md 格式', '引用文件', '脚本语法', '安全与文件清理'])
  assert.equal(r.previewDir, null)
  assert.equal(r.publishDir, null)
  assert.equal(r.editable, false)
  assert.equal(existsSync(path.join(w, 'scripts/__pycache__')), false)
  assert.equal(existsSync(path.join(w, '.zhiye')), false)
})

test('skill-template 原样通过（没有脚本时跳过脚本检查）', async () => {
  const w = mkdtempSync(path.join(os.tmpdir(), 'skill-tpl-'))
  cpSync(TEMPLATE, w, { recursive: true })
  const r = await verify(w, { projectKind: 'skill' })
  assert.equal(r.ok, true, JSON.stringify(r.checks))
  assert.equal(byName(r)['脚本语法'].skipped, true)
})

test('缺少 name', async () => {
  const r = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace('name: weekly-report\n', '') }), { projectKind: 'skill' })
  assert.equal(r.ok, false)
  assert.match(byName(r)['SKILL.md 格式'].detail, /缺少 name/)
})

test('name 格式不正确（含版本号/非法字符）、description 超过 120 字、超过 500 行或笼统 compatibility', async () => {
  for (const bad of ['Weekly_Report', '-abc', 'abc-', 'a--b', 'a'.repeat(65), '周报', 'weekly-report-v1', 'stk-alarm-v2-0', 'stk-alarm-1-0']) {
    const r = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace('weekly-report', bad) }), { projectKind: 'skill' })
    assert.equal(byName(r)['SKILL.md 格式'].ok, false, bad)
    assert.match(byName(r)['SKILL.md 格式'].detail, /name 格式不正确/)
  }
  const empty = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace(/description: .*\n/, 'description: ""\n') }), { projectKind: 'skill' })
  assert.match(byName(empty)['SKILL.md 格式'].detail, /缺少 description/)
  const long = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace(/description: .*\n/, `description: ${'长'.repeat(121)}\n`) }), { projectKind: 'skill' })
  assert.match(byName(long)['SKILL.md 格式'].detail, /太长.*120/)
  const tooLongMd = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD + '\n'.repeat(500) + 'end' }), { projectKind: 'skill' })
  assert.match(byName(tooLongMd)['SKILL.md 格式'].detail, /超过 500 行/)
  const vagueCompat = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace('---\n\n# 周报生成', 'compatibility: 兼容飞书\n---\n\n# 周报生成') }), { projectKind: 'skill' })
  assert.match(byName(vagueCompat)['SKILL.md 格式'].detail, /compatibility 描述过于笼统/)
  const goodCompat = await verify(pkg({ ...GOOD, 'SKILL.md': GOOD_MD.replace('---\n\n# 周报生成', 'compatibility: Python 3.10+（仅标准库），无外部接口依赖\n---\n\n# 周报生成') }), { projectKind: 'skill' })
  assert.equal(byName(goodCompat)['SKILL.md 格式'].ok, true)
  const none = await verify(pkg({ 'references/a.md': 'x' }), { projectKind: 'skill' })
  assert.equal(none.ok, false)
  assert.match(byName(none)['SKILL.md 格式'].detail, /没有 SKILL.md/)
})

test('安全与文件清理：拦截飞书用户 ID、密钥令牌、明文密码及临时日志文件', async () => {
  const feishuUid = await verify(pkg({ ...GOOD, 'references/template.md': '联系人：ou_a1b2c3d4e5f678901234' }), { projectKind: 'skill' })
  assert.equal(feishuUid.ok, false)
  assert.match(byName(feishuUid)['安全与文件清理'].detail, /飞书用户 ID/)

  const secretKey = await verify(pkg({ ...GOOD, 'scripts/count_words.py': 'api_key = "real_secret_key_987654321"\n' }), { projectKind: 'skill' })
  assert.equal(secretKey.ok, false)
  assert.match(byName(secretKey)['安全与文件清理'].detail, /明文凭证/)

  const placeholderOk = await verify(pkg({ ...GOOD, 'scripts/count_words.py': 'api_key = "<YOUR_API_KEY>"\n' }), { projectKind: 'skill' })
  assert.equal(byName(placeholderOk)['安全与文件清理'].ok, true)

  const junkLog = await verify(pkg({ ...GOOD, 'debug.log': 'trace...', 'tmp/out.txt': '1' }), { projectKind: 'skill' })
  assert.equal(junkLog.ok, false)
  assert.match(byName(junkLog)['安全与文件清理'].detail, /debug\.log/)
  assert.match(byName(junkLog)['安全与文件清理'].detail, /tmp\//)
})

test('引用的文件不存在', async () => {
  const { 'references/template.md': _, ...rest } = GOOD
  const r = await verify(pkg(rest), { projectKind: 'skill' })
  assert.equal(r.ok, false)
  assert.equal(byName(r)['SKILL.md 格式'].ok, true)
  assert.match(byName(r)['引用文件'].detail, /references\/template\.md/)
})

test('Python 语法错误', async () => {
  const w = pkg({ ...GOOD, 'scripts/count_words.py': 'def f(:\n  pass\n' })
  const r = await verify(w, { projectKind: 'skill' })
  assert.equal(r.ok, false)
  assert.match(byName(r)['脚本语法'].detail, /scripts\/count_words\.py/)
  assert.match(byName(r)['脚本语法'].detail, /SyntaxError|invalid syntax/)
  assert.deepEqual(readdirSync(path.join(w, 'scripts')).sort(), ['count_words.py', 'helper.mjs', 'run.sh'])
})

test('JS 与 shell 语法错误', async () => {
  const r = await verify(pkg({ ...GOOD, 'scripts/helper.mjs': 'const = 1\n', 'scripts/run.sh': 'if then\n' }), { projectKind: 'skill' })
  const d = byName(r)['脚本语法'].detail
  assert.match(d, /helper\.mjs/)
  assert.match(d, /run\.sh/)
})

test('守卫：skill 项目可写工作区内任意位置（受保护目录除外），可运行脚本自测', () => {
  const w = pkg(GOOD)
  const o = { cwd: w, projectKind: 'skill' }
  for (const p of ['SKILL.md', 'references/x.md', 'scripts/a.py', 'examples/a.txt', 'README.md']) assert.equal(checkToolCall('write', { path: p, content: '' }, o), null, p)
  for (const p of ['.git/config', '.zhiye/x', 'node_modules/a.js', '.env', '../x']) assert.ok(checkToolCall('write', { path: p, content: '' }, o), p)
  for (const c of ['python3 scripts/count_words.py references/template.md', 'node scripts/helper.mjs', 'bash scripts/run.sh']) assert.equal(checkToolCall('bash', { command: c }, o), null, c)
  for (const c of ['pip install requests', 'python3 -m pip install x', 'npm install x', 'curl http://x', 'python3 -m http.server']) assert.ok(checkToolCall('bash', { command: c }, o), c)
})
