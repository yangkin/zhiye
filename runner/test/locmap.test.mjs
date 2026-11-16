import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { parse } from '@babel/parser'
import { parse as parseHtml } from 'parse5'
import { annotateJsx, annotateHtml } from '../../packages/vite-plugin-loc/index.mjs'
import { applyVisualPatches, parseLoc } from '../src/visual.mjs'
import { LIST_APP } from './fixtures/list-app.mjs'

/** loc → { tag, text }：在源码里找到开始标签在该位置的元素 */
function elementAt(code, loc) {
  const { file, line, col } = parseLoc(loc)
  if (/\.html?$/.test(file)) {
    let hit = null
    const visit = (n) => {
      for (const c of n.childNodes || []) {
        const st = c.sourceCodeLocation?.startTag
        if (st && st.startLine === line && st.startCol - 1 === col) hit = { tag: c.tagName, text: textOf(c) }
        visit(c.content || c)
      }
    }
    const textOf = (n) => (n.nodeName === '#text' ? n.value : (n.childNodes || []).map(textOf).join('')).replace(/\s+/g, ' ').trim()
    visit(parseHtml(code, { sourceCodeLocationInfo: true }))
    return hit
  }
  const ast = parse(code, { sourceType: 'module', plugins: ['jsx', 'typescript'] })
  let hit = null
  const walk = (n) => {
    if (!n || typeof n.type !== 'string' || hit) return
    if (n.type === 'JSXElement' && n.loc.start.line === line && n.loc.start.column === col) {
      hit = { tag: n.openingElement.name.name, text: code.slice(n.start, n.end).replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim() }
      return
    }
    for (const k of Object.keys(n)) {
      if (k === 'loc' || k === 'extra') continue
      const v = n[k]
      if (Array.isArray(v)) v.forEach(walk); else if (v && typeof v.type === 'string') walk(v)
    }
  }
  walk(ast.program)
  return hit
}
const jsxLocs = (code, file = 'src/App.tsx') => [...annotateJsx(code, file, { sourceMap: false }).code.matchAll(/<([a-z][a-z0-9]*) data-loc="([^"]+)"/g)].map((m) => ({ tag: m[1], loc: m[2] }))
const pick = (locs, tag, i = 0) => locs.filter((l) => l.tag === tag)[i].loc

function ws(files) {
  const w = mkdtempSync(path.join(os.tmpdir(), 'locmap-'))
  for (const [f, c] of Object.entries(files)) { mkdirSync(path.dirname(path.join(w, f)), { recursive: true }); writeFileSync(path.join(w, f), c) }
  return w
}

/** 每个旧 loc：映射到的新 loc 指向同一个元素（标签相同；文字没被改写的元素文字也相同）。
 *  不在 locMap 里的 loc 在新代码里必须仍是同一个元素。edited：被改写（或包含被改写后代）的旧 loc。 */
function checkConsistent(before, after, locMap, file, edited = new Set()) {
  const all = /\.html?$/.test(file)
    ? [...annotateHtml(before, file, { injectBridge: false }).matchAll(/data-loc="([^"]+)"/g)].map((m) => m[1])
    : jsxLocs(before, file).map((x) => x.loc)
  let checked = 0
  for (const loc of all) {
    const old = elementAt(before, loc)
    const next = loc in locMap ? locMap[loc] : loc
    if (next === null) continue
    const now = elementAt(after, next)
    assert.ok(now, `${loc} → ${next} 在新代码里找不到元素`)
    assert.equal(now.tag, old.tag, `${loc} → ${next} 标签不同`)
    if (!edited.has(loc) && !/__i/.test(now.text)) assert.equal(now.text, old.text, `${loc} → ${next} 文字不同`)
    checked++
  }
  assert.ok(checked > 0)
}

test('下标条件包裹让后面的兄弟元素移位 → 正确映射', () => {
  const w = ws({ 'src/App.tsx': LIST_APP })
  const L = jsxLocs(LIST_APP)
  const r = applyVisualPatches(w, [{ loc: pick(L, 'a'), index: 1, removed: true }])
  assert.deepEqual(r.failures, [])
  const after = readFileSync(path.join(w, 'src/App.tsx'), 'utf8')
  // 被包裹的 a 还在，只是位置变了（同一行里列号后移）
  const aOld = pick(L, 'a')
  assert.ok(r.locMap[aOld], 'a 应该被映射到新位置')
  assert.deepEqual(elementAt(after, r.locMap[aOld]), { tag: 'a', text: '详情' })
  // map 回调补了下标参数，li 之前的元素不变；li 所在行没变
  assert.equal(r.locMap[pick(L, 'h1')], undefined)
  assert.equal(r.locMap[pick(L, 'li')], undefined)
  checkConsistent(LIST_APP, after, r.locMap, 'src/App.tsx', new Set([pick(L, 'main'), pick(L, 'ul'), pick(L, 'li')]))
})

test('同一行里的移位：下标参数和样式条件让同行的后续元素列号变化', () => {
  const w = ws({ 'src/App.tsx': LIST_APP })
  const L = jsxLocs(LIST_APP)
  const r = applyVisualPatches(w, [{ loc: pick(L, 'em'), index: 0, removed: true }, { loc: pick(L, 'h1'), text: '一个更长一些的水果清单标题' }])
  assert.deepEqual(r.failures, [])
  const after = readFileSync(path.join(w, 'src/App.tsx'), 'utf8')
  const emNew = r.locMap[pick(L, 'em')]
  assert.ok(emNew && emNew !== pick(L, 'em'))
  assert.deepEqual(elementAt(after, emNew), { tag: 'em', text: '强调' })
  checkConsistent(LIST_APP, after, r.locMap, 'src/App.tsx', new Set([pick(L, 'h1'), pick(L, 'main')]))
})

test('删除的元素 → null，后面行的元素上移', () => {
  const src = `export default function App() {
  return (
    <main>
      <section>
        <b>删除我</b>
        <i>保留</i>
      </section>
      <p>后面</p>
    </main>
  )
}
`
  const w = ws({ 'src/App.tsx': src })
  const L = jsxLocs(src)
  const r = applyVisualPatches(w, [{ loc: pick(L, 'section'), removed: true }])
  assert.deepEqual(r.locMap, {
    [pick(L, 'section')]: null,
    [pick(L, 'b')]: null,
    [pick(L, 'i')]: null,
    [pick(L, 'p')]: 'src/App.tsx:4:6',
  })
  assert.deepEqual(elementAt(readFileSync(path.join(w, 'src/App.tsx'), 'utf8'), 'src/App.tsx:4:6'), { tag: 'p', text: '后面' })
})

test('没有改动的文件不出现在 locMap 里；位置不变的元素也不出现', () => {
  const other = `export function Other() {\n  return <div><span>别的文件</span></div>\n}\n`
  const w = ws({ 'src/App.tsx': LIST_APP, 'src/Other.tsx': other })
  const L = jsxLocs(LIST_APP)
  const r = applyVisualPatches(w, [{ loc: pick(L, 'li'), index: 2, styles: { color: 'red' } }])
  assert.deepEqual(r.failures, [])
  assert.ok(Object.keys(r.locMap).every((k) => k.startsWith('src/App.tsx:')))
  for (const [k, v] of Object.entries(r.locMap)) assert.notEqual(k, v)
  const none = applyVisualPatches(w, [])
  assert.deepEqual(none.locMap, {})
})

test('多行文字改写让后面的元素换行号', () => {
  const src = `export default function App() {
  return (
    <main>
      <h1>标题</h1>
      <p>正文</p>
    </main>
  )
}
`
  const w = ws({ 'src/App.tsx': src })
  const L = jsxLocs(src)
  // 首尾带空格的文字会写成字符串表达式；删除 h1 让 p 上移一行
  const r = applyVisualPatches(w, [{ loc: pick(L, 'h1'), removed: true }, { loc: pick(L, 'p'), text: '新正文' }])
  assert.deepEqual(r.failures, [])
  const after = readFileSync(path.join(w, 'src/App.tsx'), 'utf8')
  assert.deepEqual(r.locMap, { [pick(L, 'h1')]: null, [pick(L, 'p')]: 'src/App.tsx:4:6' })
  assert.deepEqual(elementAt(after, 'src/App.tsx:4:6'), { tag: 'p', text: '新正文' })
  checkConsistent(src, after, r.locMap, 'src/App.tsx', new Set([pick(L, 'main'), pick(L, 'p')]))
})

test('HTML：删除和文字改动后的映射', () => {
  const html = `<!doctype html>
<html><body>
  <small>小标题</small>
  <h1>让灵感，自然生长。</h1> <p>同一行</p>
  <ul><li>一</li><li>二</li></ul>
</body></html>
`
  const w = ws({ 'index.html': html })
  const locs = [...annotateHtml(html, 'index.html', { injectBridge: false }).matchAll(/<([a-z0-9]+) data-loc="([^"]+)"/g)].map((m) => ({ tag: m[1], loc: m[2] }))
  const r = applyVisualPatches(w, [{ loc: pick(locs, 'small'), removed: true }, { loc: pick(locs, 'h1'), text: '短', styles: { color: 'blue' } }])
  assert.deepEqual(r.failures, [])
  const after = readFileSync(path.join(w, 'index.html'), 'utf8')
  assert.equal(r.locMap[pick(locs, 'small')], null)
  assert.deepEqual(elementAt(after, r.locMap[pick(locs, 'p')]), { tag: 'p', text: '同一行' })
  assert.deepEqual(elementAt(after, r.locMap[pick(locs, 'li', 1)]), { tag: 'li', text: '二' })
  checkConsistent(html, after, r.locMap, 'index.html', new Set([pick(locs, 'h1')]))
})

test('有失败时不写文件，locMap 为空', () => {
  const w = ws({ 'src/App.tsx': LIST_APP })
  const L = jsxLocs(LIST_APP)
  const r = applyVisualPatches(w, [{ loc: pick(L, 'h1'), text: 'x' }, { loc: pick(L, 'b'), index: 0, text: 'y' }])
  assert.equal(r.failures.length, 1)
  assert.deepEqual(r.locMap, {})
  assert.equal(readFileSync(path.join(w, 'src/App.tsx'), 'utf8'), LIST_APP)
})
