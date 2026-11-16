import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { parse } from '@babel/parser'
import { annotateJsx, annotateHtml } from '../../packages/vite-plugin-loc/index.mjs'
import { applyJsxPatches, applyHtmlPatches, applyVisualPatches, MSG } from '../src/visual.mjs'

/** 用 vite-plugin-loc 得到真实 loc：按标签名 + 出现顺序取 */
function jsxLocs(code, file = 'src/App.tsx') {
  const out = annotateJsx(code, file, { sourceMap: false }).code
  return [...out.matchAll(/<([a-z][\w-]*) data-loc="([^"]+)" data-zt="(.)"/g)].map((m) => ({ tag: m[1], loc: m[2], kind: m[3] }))
}
function htmlLocs(html, file = 'index.html') {
  const out = annotateHtml(html, file, { injectBridge: false })
  return [...out.matchAll(/<([a-z][\w-]*) data-loc="([^"]+)" data-zt="(.)"/g)].map((m) => ({ tag: m[1], loc: m[2], kind: m[3] }))
}
const pick = (locs, tag, i = 0) => locs.filter((l) => l.tag === tag)[i]
const parses = (code) => parse(code, { sourceType: 'module', plugins: ['jsx', 'typescript'] })

const APP = `import { useState } from 'react'

export default function App() {
  const [n, setN] = useState(0)
  const items = ['a', 'b']
  return (
    <main className="min-h-screen p-4">
      <h1 className="text-2xl font-bold">
        点击计数器
      </h1>
      <p className="text-gray-500">当前次数：{n}</p>
      <span>{'字符串字面量'}</span>
      <label className="flex">
        记住我
        <input type="checkbox" />
        （可选）
      </label>
      <div style={{ color: 'red', fontSize: 12 }}>样式</div>
      <div style={base}>扩展</div>
      <div style={{}}>空样式</div>
      <a className="link">链接</a>
      <a href="/old">旧链接</a>
      <button onClick={() => setN(n + 1)}>加一</button>
      <ul>
        {items.map((i) => <li key={i}>{i}</li>)}
      </ul>
      {n > 0 && <em>大于零</em>}
      <section>
        <b>删除我</b>
        <i>保留</i>
      </section>
      <p />
    </main>
  )
}

const base = { margin: 0 }
`
const L = jsxLocs(APP)

test('vite-plugin-loc 的文字类型与写回规则一致', () => {
  assert.equal(pick(L, 'h1').kind, 's')
  assert.equal(pick(L, 'p').kind, 'd')
  assert.equal(pick(L, 'label').kind, 'e')
  assert.equal(pick(L, 'span').kind, 's')
})

test('JSX 文字 s：整体替换并转义，保留格式', () => {
  const { code, results } = applyJsxPatches(APP, [{ loc: pick(L, 'h1').loc, text: '新的 {标题} <x> & y' }])
  assert.equal(results[0].error, null)
  assert.match(code, /<h1 className="text-2xl font-bold">\n {8}新的 &#123;标题&#125; &lt;x&gt; &amp; y\n {6}<\/h1>/)
  parses(code)
  // 其他代码原样保留
  assert.equal(code.replace(/新的.*y/, '点击计数器'), APP)
})

test('JSX 文字 s：字符串字面量表达式也可替换', () => {
  const { code, results } = applyJsxPatches(APP, [{ loc: pick(L, 'span').loc, text: '换掉' }])
  assert.equal(results[0].error, null)
  assert.match(code, /<span>换掉<\/span>/)
})

test('JSX 文字 s：首尾空格用字符串表达式保留', () => {
  const { code } = applyJsxPatches(APP, [{ loc: pick(L, 'span').loc, text: ' 两边有空格 ' }])
  assert.match(code, /<span>\{' 两边有空格 '\}<\/span>/)
  parses(code)
})

test('JSX 文字 e：只替换第一段文字，删掉其他文字，保留子元素', () => {
  const { code, results } = applyJsxPatches(APP, [{ loc: pick(L, 'label').loc, text: '保持登录' }])
  assert.equal(results[0].error, null)
  const label = code.slice(code.indexOf('<label'), code.indexOf('</label>'))
  assert.match(label, /保持登录/)
  assert.doesNotMatch(label, /记住我|可选/)
  assert.match(label, /<input type="checkbox" \/>/)
  parses(code)
})

test('JSX 文字 d：动态文字拒绝', () => {
  const { results } = applyJsxPatches(APP, [{ loc: pick(L, 'p').loc, text: 'x' }])
  assert.equal(results[0].error, MSG.dynamic)
  const li = applyJsxPatches(APP, [{ loc: pick(L, 'li').loc, text: 'x' }])
  assert.equal(li.results[0].error, MSG.dynamic)
})

test('JSX 文字：空元素可以加文字（含自闭合）', () => {
  const { code, results } = applyJsxPatches(APP, [{ loc: pick(L, 'p', 1).loc, text: '新段落' }])
  assert.equal(results[0].error, null)
  assert.match(code, /<p>新段落<\/p>/)
  const inp = applyJsxPatches(APP, [{ loc: pick(L, 'input').loc, text: 'x' }])
  assert.equal(inp.results[0].error, MSG.noText)
})

test('JSX 样式：新增 style', () => {
  const { code } = applyJsxPatches(APP, [{ loc: pick(L, 'h1').loc, styles: { color: '#4f78eb', fontSize: '20px' } }])
  assert.match(code, /<h1 className="text-2xl font-bold" style=\{\{ color: '#4f78eb', fontSize: '20px' \}\}>/)
  parses(code)
})

test('JSX 样式：合并已有对象字面量（覆盖 + 新增 + 删除）', () => {
  const { code } = applyJsxPatches(APP, [{ loc: pick(L, 'div', 0).loc, styles: { color: 'blue', backgroundColor: '#fff', fontSize: '' } }])
  assert.match(code, /<div style=\{\{ color: 'blue', backgroundColor: '#fff' \}\}>样式<\/div>/)
  const empty = applyJsxPatches(APP, [{ loc: pick(L, 'div', 2).loc, styles: { color: 'blue' } }])
  assert.match(empty.code, /<div style=\{\{ color: 'blue' \}\}>空样式<\/div>/)
})

test('JSX 样式：其他表达式改成展开', () => {
  const { code } = applyJsxPatches(APP, [{ loc: pick(L, 'div', 1).loc, styles: { color: 'blue' } }])
  assert.match(code, /<div style=\{\{ \.\.\.base, color: 'blue' \}\}>扩展<\/div>/)
  parses(code)
})

test('JSX 样式：非标识符键名加引号', () => {
  const { code } = applyJsxPatches(APP, [{ loc: pick(L, 'h1').loc, styles: { '--accent': 'red' } }])
  assert.match(code, /style=\{\{ '--accent': 'red' \}\}/)
})

test('JSX href：只对 a 元素，新增或替换', () => {
  const a1 = applyJsxPatches(APP, [{ loc: pick(L, 'a', 0).loc, href: 'https://zhiye.example' }])
  assert.match(a1.code, /<a className="link" href="https:\/\/zhiye.example">链接<\/a>/)
  const a2 = applyJsxPatches(APP, [{ loc: pick(L, 'a', 1).loc, href: '/new' }])
  assert.match(a2.code, /<a href="\/new">旧链接<\/a>/)
  const bad = applyJsxPatches(APP, [{ loc: pick(L, 'h1').loc, href: '/x' }])
  assert.equal(bad.results[0].error, MSG.notLink)
})

test('JSX 删除：从父元素中删除，整行移除', () => {
  const { code, results } = applyJsxPatches(APP, [{ loc: pick(L, 'b').loc, removed: true }])
  assert.equal(results[0].error, null)
  assert.match(code, /<section>\n {8}<i>保留<\/i>\n {6}<\/section>/)
  parses(code)
})

test('JSX 删除：根元素、循环、条件渲染拒绝', () => {
  assert.equal(applyJsxPatches(APP, [{ loc: pick(L, 'main').loc, removed: true }]).results[0].error, MSG.root)
  assert.equal(applyJsxPatches(APP, [{ loc: pick(L, 'li').loc, removed: true }]).results[0].error, MSG.exprRemove)
  assert.equal(applyJsxPatches(APP, [{ loc: pick(L, 'em').loc, removed: true }]).results[0].error, MSG.exprRemove)
})

test('JSX：找不到 loc / 元素已变化', () => {
  assert.equal(applyJsxPatches(APP, [{ loc: 'src/App.tsx:999:0', text: 'x' }]).results[0].error, MSG.notFound)
  assert.equal(applyJsxPatches(APP, [{ loc: 'src/App.tsx:9:7', text: 'x' }]).results[0].error, MSG.notFound)
})

test('JSX：多个补丁一起写，删除的元素内部补丁作废', () => {
  const { code, results } = applyJsxPatches(APP, [
    { loc: pick(L, 'h1').loc, text: '计数器', styles: { color: '#4f78eb' } },
    { loc: pick(L, 'button').loc, text: '+1' },
    { loc: pick(L, 'section').loc, removed: true },
    { loc: pick(L, 'i').loc, text: '内部' },
  ])
  assert.deepEqual(results.map((r) => r.error), [null, null, null, MSG.removedParent])
  assert.match(code, /style=\{\{ color: '#4f78eb' \}\}>\n {8}计数器/)
  assert.match(code, /<button onClick=\{\(\) => setN\(n \+ 1\)\}>\+1<\/button>/)
  assert.doesNotMatch(code, /<section>/)
  parses(code)
  // 改完后再标注，元素数量减少 3（section、b、i）
  assert.equal(jsxLocs(code).length, L.length - 3)
})

const HTML = `<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>花园</title><style>h1{color:green}</style></head>
<body>
  <small>A LITTLE GARDEN</small>
  <h1 class="title">让灵感，自然生长。</h1>
  <p style="color: red; margin: 0">说明 <b>重点</b> 结尾</p>
  <label>同意 <input type="checkbox"> 条款</label>
  <button onclick="this.textContent='已记录一份灵感'">种下一份灵感</button>
  <a href="/old" class="l">链接</a>
  <span>文字</span>
  <div></div>
</body>
</html>
`
const H = htmlLocs(HTML)

test('HTML 文字：替换并转义，保留其他属性', () => {
  const { code, results } = applyHtmlPatches(HTML, [{ loc: pick(H, 'h1').loc, text: '让想法，<自然>生长。' }])
  assert.equal(results[0].error, null)
  assert.match(code, /<h1 class="title">让想法，&lt;自然&gt;生长。<\/h1>/)
  assert.equal(code.replace('让想法，&lt;自然&gt;生长。', '让灵感，自然生长。'), HTML)
})

test('HTML 文字 e：替换第一段，删掉其他文字，保留子元素', () => {
  const { code } = applyHtmlPatches(HTML, [{ loc: pick(H, 'label').loc, text: '我同意' }])
  assert.match(code, /<label>我同意 <input type="checkbox"><\/label>/)
})

test('HTML 文字：空元素加文字、void 元素拒绝', () => {
  assert.match(applyHtmlPatches(HTML, [{ loc: pick(H, 'div').loc, text: '新' }]).code, /<div>新<\/div>/)
  assert.equal(applyHtmlPatches(HTML, [{ loc: pick(H, 'input').loc, text: 'x' }]).results[0].error, MSG.noText)
})

test('HTML 样式：新增、合并（驼峰转短横线）、删除', () => {
  const a = applyHtmlPatches(HTML, [{ loc: pick(H, 'h1').loc, styles: { color: '#4f78eb', fontSize: '40px' } }])
  assert.match(a.code, /<h1 style="color: #4f78eb; font-size: 40px" class="title">/)
  const b = applyHtmlPatches(HTML, [{ loc: pick(H, 'p').loc, styles: { color: 'blue', backgroundColor: '#fff', margin: '' } }])
  assert.match(b.code, /<p style="color: blue; background-color: #fff">/)
  const c = applyHtmlPatches(HTML, [{ loc: pick(H, 'p').loc, styles: { color: '', margin: '' } }])
  assert.match(c.code, /<p>说明/)
})

test('HTML href 与属性转义', () => {
  const a = applyHtmlPatches(HTML, [{ loc: pick(H, 'a').loc, href: '/new?a=1&b="2"' }])
  assert.match(a.code, /<a href="\/new\?a=1&amp;b=&quot;2&quot;" class="l">/)
  const b = applyHtmlPatches(HTML, [{ loc: pick(H, 'span').loc, href: '/x' }])
  assert.equal(b.results[0].error, MSG.notLink)
})

test('HTML 删除：整行删除；保留 onclick', () => {
  const { code } = applyHtmlPatches(HTML, [{ loc: pick(H, 'small').loc, removed: true }])
  assert.doesNotMatch(code, /LITTLE/)
  assert.match(code, /<body>\n {2}<h1/)
  assert.match(code, /onclick="this.textContent='已记录一份灵感'"/)
})

test('HTML：找不到 loc', () => {
  assert.equal(applyHtmlPatches(HTML, [{ loc: 'index.html:1:0', text: 'x' }]).results[0].error, MSG.notFound)
})

test('applyVisualPatches：写文件、源码不带 data-loc、有失败时不写', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'visual-'))
  mkdirSync(path.join(ws, 'src'))
  writeFileSync(path.join(ws, 'src/App.tsx'), APP)
  writeFileSync(path.join(ws, 'index.html'), HTML)
  const ok = applyVisualPatches(ws, [
    { loc: pick(L, 'h1').loc, text: '计数器', styles: { color: '#4f78eb' } },
    { loc: pick(H, 'h1').loc, text: '让想法，自然生长。', styles: { color: '#4f78eb' } },
  ])
  assert.deepEqual({ ...ok, locMap: undefined }, { applied: 2, failures: [], locMap: undefined, changedFiles: ['src/App.tsx', 'index.html'] })
  const html = readFileSync(path.join(ws, 'index.html'), 'utf8')
  assert.match(html, /让想法，自然生长。/)
  assert.doesNotMatch(html, /data-loc/)
  assert.match(html, /onclick=/)

  const before = readFileSync(path.join(ws, 'src/App.tsx'), 'utf8')
  const bad = applyVisualPatches(ws, [{ loc: pick(L, 'button').loc, text: '加 1' }, { loc: pick(L, 'p').loc, text: 'x' }])
  assert.equal(bad.applied, 1)
  assert.deepEqual(bad.failures, [{ loc: pick(L, 'p').loc, reason: MSG.dynamic }])
  assert.equal(readFileSync(path.join(ws, 'src/App.tsx'), 'utf8'), before)

  const paths = applyVisualPatches(ws, [{ loc: '../x.tsx:1:0', text: 'x' }, { loc: 'src/Nope.tsx:1:0', text: 'x' }, { loc: 'bad', text: 'x' }, { loc: 'node_modules/a.tsx:1:0', text: 'x' }])
  assert.deepEqual(paths.failures.map((f) => f.reason), [MSG.badFile, MSG.badFile, MSG.badLoc, MSG.badFile])
})
