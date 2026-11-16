import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, cpSync, symlinkSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import os from 'node:os'
import { parse } from '@babel/parser'
import { annotateJsx, annotateHtml } from '../../packages/vite-plugin-loc/index.mjs'
import { applyJsxPatches, applyHtmlPatches, MSG } from '../src/visual.mjs'
import { LIST_APP } from './fixtures/list-app.mjs'

const ZHIYE = path.resolve(import.meta.dirname, '../..')


/** 每次改完都重新标注，模拟预览重新构建后前端拿到的新 loc */
function locsOf(code) {
  const out = annotateJsx(code, 'src/App.tsx', { sourceMap: false }).code
  const m = {}
  for (const x of out.matchAll(/<([a-z][a-z0-9]*) data-loc="([^"]+)"/g)) m[x[1]] ??= x[2]
  return m
}
const parses = (code) => parse(code, { sourceType: 'module', plugins: ['jsx', 'typescript'] })
function apply(code, patches) {
  const r = applyJsxPatches(code, patches)
  if (r.code !== code) parses(r.code)
  return { code: r.code, errors: r.results.map((x) => x.error) }
}

test('单项改颜色：补下标参数，style 条件生效', () => {
  const L = locsOf(LIST_APP)
  const { code, errors } = apply(LIST_APP, [{ loc: L.li, index: 1, match: '水果香蕉详情', styles: { color: '#d62424' } }])
  assert.deepEqual(errors, [null])
  assert.match(code, /items\.map\(\(item, __i\) => \(/)
  assert.match(code, /<li key=\{item.id\} className="p-2" style=\{__i === 1 \? \{ color: '#d62424' \} : undefined\}>/)
})

test('两个不同的下标、再次修改同一下标（替换而不是嵌套）、清除', () => {
  let code = LIST_APP
  code = apply(code, [{ loc: locsOf(code).li, index: 1, styles: { color: '#d62424' } }]).code
  code = apply(code, [{ loc: locsOf(code).li, index: 2, styles: { color: 'blue' } }]).code
  assert.match(code, /style=\{\{ \.\.\.\(__i === 1 \? \{ color: '#d62424' \} : \{\}\), \.\.\.\(__i === 2 \? \{ color: 'blue' \} : \{\}\) \}\}/)
  code = apply(code, [{ loc: locsOf(code).li, index: 1, styles: { color: 'green', fontSize: '20px' } }]).code
  assert.match(code, /\.\.\.\(__i === 1 \? \{ color: 'green', fontSize: '20px' \} : \{\}\), \.\.\.\(__i === 2 \? \{ color: 'blue' \} : \{\}\)/)
  assert.equal(code.match(/__i === 1/g).length, 1)
  assert.equal(code.match(/\(item, __i\)/g).length, 1, '下标参数只加一次')
  code = apply(code, [{ loc: locsOf(code).li, index: 1, styles: { color: '', fontSize: '' } }]).code
  assert.match(code, /style=\{__i === 2 \? \{ color: 'blue' \} : undefined\}/)
  code = apply(code, [{ loc: locsOf(code).li, index: 2, styles: { color: '' } }]).code
  assert.match(code, /<li key=\{item.id\} className="p-2">/)
})

test('已有 style 和已有下标参数：合并为展开', () => {
  const L = locsOf(LIST_APP)
  const { code } = apply(LIST_APP, [{ loc: L.p, index: 0, styles: { color: 'red' } }])
  assert.match(code, /\(it, idx\) => <p key=\{it.id\} style=\{\{ \.\.\.\(\{ margin: 0 \}\), \.\.\.\(idx === 0 \? \{ color: 'red' \} : \{\}\) \}\}>/)
  const again = apply(code, [{ loc: locsOf(code).p, index: 0, styles: { color: 'blue' } }]).code
  assert.match(again, /\.\.\.\(\{ margin: 0 \}\), \.\.\.\(idx === 0 \? \{ color: 'blue' \} : \{\}\)/)
})

test('静态文字：条件表达式，重复修改同一项、改回原文', () => {
  let code = LIST_APP
  let r = apply(code, [{ loc: locsOf(code).span, index: 0, text: "新'水果'" }])
  assert.deepEqual(r.errors, [null])
  assert.match(r.code, /<span>\{__i === 0 \? '新\\'水果\\'' : '水果'\}<\/span>/)
  code = apply(r.code, [{ loc: locsOf(r.code).span, index: 2, text: '第三' }]).code
  code = apply(code, [{ loc: locsOf(code).span, index: 0, text: '再改' }]).code
  assert.match(code, /<span>\{__i === 0 \? '再改' : __i === 2 \? '第三' : '水果'\}<\/span>/)
  code = apply(code, [{ loc: locsOf(code).span, index: 0, text: '水果' }]).code
  assert.match(code, /<span>\{__i === 2 \? '第三' : '水果'\}<\/span>/)
})

test('来自数据的文字被拒绝', () => {
  const L = locsOf(LIST_APP)
  assert.deepEqual(apply(LIST_APP, [{ loc: L.b, index: 0, text: 'x' }]).errors, [MSG.dataText])
  assert.deepEqual(apply(LIST_APP, [{ loc: L.p, index: 0, text: 'x' }]).errors, [MSG.dataText])
})

test('删除单项：子元素包成条件，回调根元素返回 null；再删另一项合并条件', () => {
  let code = LIST_APP
  let r = apply(code, [{ loc: locsOf(code).a, index: 1, removed: true }])
  assert.deepEqual(r.errors, [null])
  assert.match(r.code, /\{__i === 1 \? null : <a href="\/detail">详情<\/a>\}/)
  r = apply(LIST_APP, [{ loc: locsOf(LIST_APP).em, index: 0, removed: true }])
  assert.match(r.code, /function \(it, __i\) \{ return \(__i === 0 \? null : <em key=\{it.id\}>强调<\/em>\) \}/)
  code = apply(r.code, [{ loc: locsOf(r.code).em, index: 2, removed: true }]).code
  assert.match(code, /return \(__i === 0 \|\| __i === 2 \? null : <em/)
})

test('href 单项', () => {
  let code = apply(LIST_APP, [{ loc: locsOf(LIST_APP).a, index: 2, href: '/orange' }]).code
  assert.match(code, /<a href=\{__i === 2 \? '\/orange' : '\/detail'\}>/)
  code = apply(code, [{ loc: locsOf(code).a, index: 2, href: '/o2' }]).code
  assert.match(code, /<a href=\{__i === 2 \? '\/o2' : '\/detail'\}>/)
})

test('同一次提交里多个单项补丁叠加', () => {
  const L = locsOf(LIST_APP)
  const r = apply(LIST_APP, [
    { loc: L.li, index: 0, styles: { color: 'red' } },
    { loc: L.span, index: 0, text: '甲' },
    { loc: L.a, index: 1, removed: true },
    { loc: L.h1, text: '清单' },
  ])
  assert.deepEqual(r.errors, [null, null, null, null])
  assert.equal(r.code.match(/\(item, __i\)/g).length, 1)
  assert.match(r.code, /<h1>清单<\/h1>/)
})

test('可复用组件的多个实例、下标名冲突、没有 index 时行为不变', () => {
  const L = locsOf(LIST_APP)
  assert.deepEqual(apply(LIST_APP, [{ loc: L.i, index: 1, styles: { color: 'red' } }]).errors, [MSG.reusable])
  assert.deepEqual(apply(LIST_APP, [{ loc: L.i, index: 0, sameLocCount: 2, styles: { color: 'red' } }]).errors, [MSG.reusable])
  assert.deepEqual(apply(LIST_APP, [{ loc: L.h1, index: 0, sameLocCount: 1, text: '单个' }]).errors, [null])
  const clash = LIST_APP.replace('const [n] = useState(0)', 'const [n] = useState(0)\n  const __i = 1')
  assert.match(apply(clash, [{ loc: locsOf(clash).li, index: 0, styles: { color: 'red' } }]).code, /\(item, __i2\)/)
  const all = apply(LIST_APP, [{ loc: L.span, text: '全部' }]).code
  assert.match(all, /<span>全部<\/span>/)
})

test('HTML：index 不影响（loc 唯一）', () => {
  const html = '<body><ul><li>a</li><li>b</li></ul></body>'
  const loc = [...annotateHtml(html, 'index.html', { injectBridge: false }).matchAll(/data-loc="([^"]+)"/g)][2][1]
  const r = applyHtmlPatches(html, [{ loc, index: 5, text: 'B' }])
  assert.equal(r.code, '<body><ul><li>a</li><li>B</li></ul></body>')
})

test('改写后的代码通过模板的 tsc 类型检查', () => {
  let code = LIST_APP
  const steps = [
    (L) => [{ loc: L.li, index: 1, styles: { color: '#d62424' } }, { loc: L.span, index: 0, text: '新' }],
    (L) => [{ loc: L.li, index: 2, styles: { color: 'blue' } }, { loc: L.a, index: 1, removed: true }, { loc: L.p, index: 0, styles: { color: 'red' } }],
    (L) => [{ loc: L.em, index: 0, removed: true }, { loc: L.a, index: 2, href: '/x' }],
  ]
  for (const s of steps) { const r = apply(code, s(locsOf(code))); assert.ok(r.errors.every((e) => e === null), String(r.errors)); code = r.code }
  const ws = mkdtempSync(path.join(os.tmpdir(), 'inst-tsc-'))
  cpSync(path.join(ZHIYE, 'packages/app-template'), ws, { recursive: true })
  symlinkSync(path.join(ZHIYE, 'runtime/template-cache/node_modules'), path.join(ws, 'node_modules'))
  writeFileSync(path.join(ws, 'src/App.tsx'), code)
  execFileSync(process.execPath, [path.join(ws, 'node_modules/typescript/bin/tsc'), '--noEmit', '-p', 'tsconfig.json'], { cwd: ws, stdio: 'pipe' })
})
