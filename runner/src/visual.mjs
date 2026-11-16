// 可视化编辑写回源码：JSX/TSX（@babel/parser 定位 + 按位置替换）和 HTML（parse5 源码位置 + 字符串替换）。
import { readFileSync, writeFileSync, existsSync, lstatSync } from 'node:fs'
import path from 'node:path'
import { parse as babelParse } from '@babel/parser'
import { parse as parseHtml } from 'parse5'
import { annotateJsx, annotateHtml } from '../../packages/vite-plugin-loc/index.mjs'


export const MSG = {
  notFound: '元素已变化，请刷新后重试',
  dynamic: '这段文字是动态生成的，不能直接修改',
  root: '不能删除页面根元素',
  exprRemove: '这个元素由表达式渲染（如列表循环或条件显示），不能直接删除，请交给 AI 修改',
  notLink: '只有链接元素可以设置链接地址',
  noText: '这个元素没有可以直接修改的文字',
  badLoc: '元素位置格式不正确',
  badFile: '找不到元素所在的源文件',
  parse: '源文件解析失败，请交给 AI 修改',
  unsupported: '这个文件类型不支持可视化编辑',
  removedParent: '元素所在的父元素已被删除',
  dataText: '这段文字来自数据，手动微调不能只改其中一项，请用对话修改',
  reusable: '这个元素来自可复用组件，只改其中一处需要用对话修改',
  nested: '这个元素在嵌套的列表里，手动微调不能只改其中一项，请用对话修改',
  listForm: '这个列表的写法不支持只改其中一项，请用对话修改',
  badIndex: '列表项序号不正确',
}

const VOID_HTML = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr'])

export function parseLoc(loc) {
  const m = /^(.+):(\d+):(\d+)$/.exec(String(loc || ''))
  if (!m) return null
  return { file: m[1], line: Number(m[2]), col: Number(m[3]) }
}

const camelToKebab = (k) => (k.startsWith('--') ? k : k.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()).replace(/^ms-/, '-ms-'))

// ====================== JSX ======================
// 用 @babel/parser 定位节点（与 vite-plugin-loc 同样的解析选项，loc 完全一致），
// 再按源码位置做最小的字符串替换，原有格式（缩进、引号、换行）全部保留。

const babelOptions = { sourceType: 'module', plugins: ['jsx', 'typescript'], errorRecovery: false }

function walkJsx(node, visit, ancestors = []) {
  if (!node || typeof node.type !== 'string') return
  if (visit(node, ancestors) === false) return
  ancestors.push(node)
  for (const key of Object.keys(node)) {
    if (key === 'loc' || key === 'start' || key === 'end' || key === 'extra' || key === 'leadingComments' || key === 'trailingComments' || key === 'innerComments') continue
    const v = node[key]
    if (Array.isArray(v)) { for (const x of v) if (x && typeof x.type === 'string') walkJsx(x, visit, ancestors) }
    else if (v && typeof v.type === 'string') walkJsx(v, visit, ancestors)
  }
  ancestors.pop()
}

function findJsx(ast, line, col) {
  let found = null
  walkJsx(ast.program, (node, anc) => {
    if (found) return false
    if (node.type === 'JSXElement') {
      const o = node.openingElement
      if (o.loc.start.line === line && o.loc.start.column === col) { found = { node, ancestors: [...anc] }; return false }
    }
  })
  return found
}

function escapeJsxText(t) {
  return t.replace(/&/g, '&amp;').replace(/\{/g, '&#123;').replace(/\}/g, '&#125;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 按文件里 JS 字符串（不含 JSX 属性）多数使用的引号风格生成字符串字面量。 */
function makeQuote(ast) {
  let sq = 0, dq = 0
  walkJsx(ast.program, (node, anc) => {
    if (node.type === 'StringLiteral' && node.extra?.raw && anc[anc.length - 1]?.type !== 'JSXAttribute') {
      if (node.extra.raw[0] === "'") sq++; else if (node.extra.raw[0] === '"') dq++
    }
  })
  const single = sq > dq
  return (s) => (single ? "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n') + "'" : JSON.stringify(String(s)))
}

/** 文字在 JSX 子节点中的写法：普通文字转义后直接写；首尾有空白或含换行时用字符串表达式保留原样。 */
function jsxTextRepr(text, q) {
  if (text === '' || text !== text.trim() || /\n/.test(text)) return '{' + q(text) + '}'
  return escapeJsxText(text)
}

function isStaticString(expr) {
  return expr.type === 'StringLiteral' || (expr.type === 'TemplateLiteral' && expr.expressions.length === 0)
}

function classifyChildren(children) {
  let text = false, elements = false, dynamic = false
  for (const c of children) {
    if (c.type === 'JSXText') { if (c.value.trim()) text = true }
    else if (c.type === 'JSXExpressionContainer') {
      const e = c.expression
      if (e.type === 'JSXEmptyExpression') continue
      if (isStaticString(e)) text = true
      else dynamic = true
    } else if (c.type === 'JSXSpreadChild') dynamic = true
    else if (c.type === 'JSXElement' || c.type === 'JSXFragment') elements = true
  }
  return { text, elements, dynamic }
}

const isTextChild = (c) => (c.type === 'JSXText' && c.value.trim()) || (c.type === 'JSXExpressionContainer' && isStaticString(c.expression))
const tagOf = (node) => (node.openingElement.name.type === 'JSXIdentifier' ? node.openingElement.name.name : '')

function jsxTextEdits(code, node, text, q) {
  const children = node.children || []
  const k = classifyChildren(children)
  if (k.dynamic) return { error: MSG.dynamic }
  const tag = tagOf(node)
  if (!k.text && k.elements) return { error: MSG.noText }
  if (!k.text) {
    if (VOID_HTML.has(tag)) return { error: MSG.noText }
    const o = node.openingElement
    if (o.selfClosing) {
      // <p /> → <p>文字</p>
      const src = code.slice(o.start, o.end)
      const m = /\s*\/>$/.exec(src)
      return { edits: [{ s: o.end - m[0].length, e: o.end, t: `>${jsxTextRepr(text, q)}</${code.slice(o.name.start, o.name.end)}>` }] }
    }
    // 只有空白：整体替换
    return { edits: [{ s: o.end, e: node.closingElement.start, t: jsxTextRepr(text, q) }] }
  }
  const edits = []
  if (!k.elements) {
    // 只有文字：整体替换，保留首尾空白
    const s = children[0].start, e = children[children.length - 1].end
    const raw = code.slice(s, e)
    const lead = /^\s*/.exec(raw)[0], trail = /\s*$/.exec(raw)[0]
    const repr = jsxTextRepr(text, q)
    edits.push({ s: s + lead.length, e: e - trail.length, t: repr })
    return { edits }
  }
  // 文字 + 子元素：替换第一段文字，删掉其他文字段，保留子元素
  let first = true
  for (const c of children) {
    if (!isTextChild(c)) continue
    let s = c.start, e = c.end
    if (c.type === 'JSXText') {
      const raw = code.slice(s, e)
      s += /^\s*/.exec(raw)[0].length
      e -= /\s*$/.exec(raw)[0].length
    }
    edits.push({ s, e, t: first ? jsxTextRepr(text, q) : '' })
    first = false
  }
  return { edits }
}

const IDENT = /^[A-Za-z_$][A-Za-z0-9_$]*$/
function keyName(p) {
  if (!p.key || p.computed) return null
  if (p.key.type === 'Identifier') return p.key.name
  if (p.key.type === 'StringLiteral') return String(p.key.value)
  return null
}

/** 新属性插入位置：最后一个属性之后，没有属性就在标签名之后。 */
function attrInsertPos(node) {
  const o = node.openingElement
  const attrs = o.attributes
  return attrs.length ? attrs[attrs.length - 1].end : (o.typeParameters ? o.typeParameters.end : o.name.end)
}

function jsxStyleEdits(code, node, styles, q) {
  const entries = Object.entries(styles).filter(([k]) => k && typeof k === 'string')
  const isRemove = (v) => v === '' || v === null || v === undefined
  const setProps = entries.filter(([, v]) => !isRemove(v))
  const prop = ([k, v]) => `${IDENT.test(k) ? k : q(k)}: ${q(v)}`
  const objSrc = (extra = []) => `{ ${[...extra, ...setProps.map(prop)].join(', ')} }`
  const attr = node.openingElement.attributes.find((a) => a.type === 'JSXAttribute' && a.name.name === 'style')
  if (!attr) {
    if (!setProps.length) return { edits: [] }
    const at = attrInsertPos(node)
    return { edits: [{ s: at, e: at, t: ` style={${objSrc()}}` }] }
  }
  const v = attr.value
  const expr = v && v.type === 'JSXExpressionContainer' ? v.expression : null
  if (expr && expr.type === 'ObjectExpression') {
    const edits = []
    const props = expr.properties
    const handled = new Set()
    for (const [k, val] of entries) {
      const idx = props.findIndex((p) => p.type === 'ObjectProperty' && keyName(p) === k)
      if (idx < 0) continue
      handled.add(k)
      const p = props[idx]
      if (isRemove(val)) {
        // 连同逗号一起删
        if (idx < props.length - 1) edits.push({ s: p.start, e: props[idx + 1].start, t: '' })
        else if (idx > 0) edits.push({ s: props[idx - 1].end, e: p.end, t: '' })
        else edits.push({ s: p.start, e: p.end, t: '' })
      } else if (p.shorthand) edits.push({ s: p.start, e: p.end, t: prop([k, val]) })
      else edits.push({ s: p.value.start, e: p.value.end, t: q(val) })
    }
    const added = setProps.filter(([k]) => !handled.has(k)).map(prop)
    if (added.length) {
      if (props.length) {
        const last = props[props.length - 1]
        edits.push({ s: last.end, e: last.end, t: ', ' + added.join(', ') })
      } else edits.push({ s: expr.start + 1, e: expr.end - 1, t: ` ${added.join(', ')} ` })
    }
    return { edits }
  }
  if (!expr || expr.type === 'JSXEmptyExpression') {
    return { edits: [{ s: v ? v.start : attr.end, e: v ? v.end : attr.end, t: `${v ? '' : '='}{${objSrc()}}` }] }
  }
  return { edits: [{ s: expr.start, e: expr.end, t: objSrc([`...${code.slice(expr.start, expr.end)}`]) }] }
}

function jsxHrefEdits(code, node, href, q) {
  if (tagOf(node) !== 'a') return { error: MSG.notLink }
  const val = /["\n\\]/.test(href) ? `{${q(href)}}` : `"${href}"`
  const attr = node.openingElement.attributes.find((a) => a.type === 'JSXAttribute' && a.name.name === 'href')
  if (attr) return { edits: [{ s: attr.name.end, e: attr.end, t: '=' + val }] }
  const at = attrInsertPos(node)
  return { edits: [{ s: at, e: at, t: ` href=${val}` }] }
}

/** 整行只有 [s,e) 时，连同缩进和换行一起删。 */
function wholeLine(code, s, e) {
  const ls = code.lastIndexOf('\n', s - 1) + 1
  if (/^[ \t]*$/.test(code.slice(ls, s))) {
    const m = /^[ \t]*\r?\n/.exec(code.slice(e))
    if (m) return { s: ls, e: e + m[0].length }
  }
  return { s, e }
}

function jsxRemoveEdits(code, found) {
  const { node, ancestors } = found
  const parent = ancestors[ancestors.length - 1]
  if (parent && (parent.type === 'JSXElement' || parent.type === 'JSXFragment')) {
    const r = wholeLine(code, node.start, node.end)
    return { edits: [{ s: r.s, e: r.e, t: '', removal: true }] }
  }
  // 是函数返回的元素：在 .map(...) 等调用里 → 表达式渲染；否则是组件根元素
  for (let i = ancestors.length - 1; i >= 0; i--) {
    const a = ancestors[i]
    if (/Function/.test(a.type)) {
      const up = ancestors[i - 1]
      if (up && (up.type === 'CallExpression' || up.type === 'OptionalCallExpression') && up.arguments.includes(a)) return { error: MSG.exprRemove }
      return { error: MSG.root }
    }
    if (a.type === 'JSXElement' || a.type === 'JSXFragment' || a.type === 'JSXExpressionContainer') return { error: MSG.exprRemove }
  }
  return { error: MSG.root }
}

// ---------------- 列表项（.map 回调）逐项写回 ----------------
// 有 index 的补丁只作用于列表里的第 index 项：给 map 回调补上下标参数（默认 __i），
// 再把修改写成以下标为条件的表达式。生成的形式可以被再次识别，同一项重复修改时替换而不是嵌套。

const isFn = (n) => n && (n.type === 'ArrowFunctionExpression' || n.type === 'FunctionExpression')
function isMapCall(n) {
  if (!n || (n.type !== 'CallExpression' && n.type !== 'OptionalCallExpression')) return false
  const c = n.callee
  return (c.type === 'MemberExpression' || c.type === 'OptionalMemberExpression') && !c.computed && c.property.type === 'Identifier' && c.property.name === 'map'
}

/** 元素所在的 map 回调：{} 表示不在列表里；{ fn, idxName? }；{ error } */
function mapContext(ancestors) {
  const fns = []
  for (let i = ancestors.length - 1; i > 0; i--) {
    const a = ancestors[i]
    if (isFn(a) && isMapCall(ancestors[i - 1]) && ancestors[i - 1].arguments[0] === a) fns.push(a)
  }
  if (!fns.length) return {}
  if (fns.length > 1) return { error: MSG.nested }
  const fn = fns[0]
  if (fn.params.length >= 2) {
    if (fn.params[1].type !== 'Identifier') return { error: MSG.listForm }
    return { fn, idxName: fn.params[1].name }
  }
  if (fn.params.some((p) => p.type === 'RestElement')) return { error: MSG.listForm }
  return { fn }
}

function freshName(ast) {
  const used = new Set()
  walkJsx(ast.program, (n) => { if (n.type === 'Identifier' || n.type === 'JSXIdentifier') used.add(n.name) })
  let name = '__i'
  for (let k = 2; used.has(name); k++) name = `__i${k}`
  return name
}

/** 给 map 回调加上下标参数 */
function paramEdits(code, fn, name) {
  const p = fn.params[0]
  if (!p) {
    const at = code.indexOf('(', fn.start)
    return [{ s: at + 1, e: at + 1, t: `_item, ${name}` }]
  }
  if (fn.type === 'ArrowFunctionExpression' && !code.slice(fn.start, p.start).includes('(')) {
    return [{ s: p.start, e: p.end, t: `(${code.slice(p.start, p.end)}, ${name})` }]
  }
  return [{ s: p.end, e: p.end, t: `, ${name}` }]
}

/** `__i === 1` 或 `__i === 1 || __i === 3` → [1, 3]；不是这种形式返回 null */
function parseIdxTest(t, name) {
  if (!t) return null
  if (t.type === 'LogicalExpression' && t.operator === '||') {
    const a = parseIdxTest(t.left, name), b = parseIdxTest(t.right, name)
    return a && b ? [...a, ...b] : null
  }
  if (t.type === 'BinaryExpression' && t.operator === '===' && t.left.type === 'Identifier' && t.left.name === name && t.right.type === 'NumericLiteral') return [t.right.value]
  return null
}
const idxTestSrc = (name, ns) => ns.map((n) => `${name} === ${n}`).join(' || ')

/** 识别 `__i === 1 ? A : __i === 3 ? B : 默认`。pred 限制各分支的节点类型。 */
function parseChain(code, expr, name, pred) {
  const entries = new Map()
  let e = expr
  while (e.type === 'ConditionalExpression') {
    const ns = parseIdxTest(e.test, name)
    if (!ns || ns.length !== 1) break
    if (pred && !pred(e.consequent)) return null
    entries.set(ns[0], code.slice(e.consequent.start, e.consequent.end))
    e = e.alternate
  }
  if (!entries.size) return null
  if (pred && !pred(e)) return null
  return { entries, def: code.slice(e.start, e.end), defNode: e }
}
function chainSrc(name, entries, def) {
  return [...entries.keys()].sort((a, b) => a - b).reduceRight((acc, k) => `${name} === ${k} ? ${entries.get(k)} : ${acc}`, def)
}

const staticVal = (n) => (n.type === 'StringLiteral' ? n.value : n.type === 'TemplateLiteral' && !n.expressions.length ? n.quasis[0].value.cooked : null)
function decodeJsxEntities(s) {
  return s.replace(/&(#\d+|#x[0-9a-f]+|amp|lt|gt|quot|apos|nbsp);/gi, (m, e) => {
    const l = e.toLowerCase()
    if (l[0] === '#') return String.fromCodePoint(l[1] === 'x' ? parseInt(l.slice(2), 16) : parseInt(l.slice(1), 10))
    return { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' }[l]
  })
}
/** JSX 文本的实际值：按行去掉首尾空白、丢掉空行、用空格连接 */
const jsxTextValue = (raw) => decodeJsxEntities(raw.split(/\r?\n/).map((l) => l.trim()).filter(Boolean).join(' '))

function instText(code, node, name, N, text, q) {
  const segs = []
  let hasEl = false
  for (const c of node.children) {
    if (c.type === 'JSXText') {
      if (!c.value.trim()) continue
      const lead = /^\s*/.exec(c.value)[0].length, trail = /\s*$/.exec(c.value)[0].length
      const v = jsxTextValue(c.value)
      segs.push({ s: c.start + lead, e: c.end - trail, entries: new Map(), def: q(v), defVal: v })
    } else if (c.type === 'JSXExpressionContainer') {
      const ex = c.expression
      if (ex.type === 'JSXEmptyExpression') continue
      const sv = staticVal(ex)
      if (sv !== null) { segs.push({ s: c.start, e: c.end, entries: new Map(), def: code.slice(ex.start, ex.end), defVal: sv }); continue }
      const ch = parseChain(code, ex, name, (n) => staticVal(n) !== null)
      if (ch) { segs.push({ s: c.start, e: c.end, entries: ch.entries, def: ch.def, defVal: staticVal(ch.defNode) }); continue }
      return { error: MSG.dataText }
    } else if (c.type === 'JSXSpreadChild') return { error: MSG.dataText }
    else hasEl = true
  }
  if (!segs.length) {
    if (hasEl || VOID_HTML.has(tagOf(node)) || node.openingElement.selfClosing) return { error: MSG.noText }
    const at = node.openingElement.end
    return { edits: [{ s: at, e: node.closingElement.start, t: `{${chainSrc(name, new Map([[N, q(text)]]), "''")}}` }] }
  }
  return {
    edits: segs.map((g, i) => {
      const val = i === 0 ? text : ''
      if (val === g.defVal) g.entries.delete(N); else g.entries.set(N, q(val))
      return { s: g.s, e: g.e, t: g.entries.size ? `{${chainSrc(name, g.entries, g.def)}}` : jsxTextRepr(g.defVal, q) }
    }),
  }
}

function instHref(code, node, name, N, href, q) {
  if (tagOf(node) !== 'a') return { error: MSG.notLink }
  const attr = node.openingElement.attributes.find((a) => a.type === 'JSXAttribute' && a.name.name === 'href')
  let entries = new Map(), def = 'undefined'
  if (attr?.value?.type === 'StringLiteral') def = q(attr.value.value)
  else if (attr?.value?.type === 'JSXExpressionContainer' && attr.value.expression.type !== 'JSXEmptyExpression') {
    const ex = attr.value.expression
    const ch = parseChain(code, ex, name)
    if (ch) { entries = ch.entries; def = ch.def } else def = code.slice(ex.start, ex.end)
  }
  entries.set(N, q(href))
  const t = `href={${chainSrc(name, entries, def)}}`
  if (attr) return { edits: [{ s: attr.start, e: attr.end, t }] }
  const at = attrInsertPos(node)
  return { edits: [{ s: at, e: at, t: ' ' + t }] }
}

function objToMap(code, obj) {
  const m = new Map()
  obj.properties.forEach((p, i) => {
    const k = p.type === 'ObjectProperty' ? keyName(p) : null
    if (k !== null && !p.shorthand) m.set(k, code.slice(p.value.start, p.value.end))
    else m.set(`\0raw${i}`, code.slice(p.start, p.end))
  })
  return m
}
const isEmptyObj = (n) => n.type === 'ObjectExpression' && !n.properties.length
const isUndef = (n) => n.type === 'Identifier' && n.name === 'undefined'
/** `__i === N ? { ... } : ALT`，alt 由 altPred 判断 */
function condObj(e, name, altPred) {
  if (e?.type !== 'ConditionalExpression' || e.consequent.type !== 'ObjectExpression' || !altPred(e.alternate)) return null
  const ns = parseIdxTest(e.test, name)
  return ns && ns.length === 1 ? { n: ns[0], obj: e.consequent } : null
}

function instStyle(code, node, name, N, styles, q) {
  const attr = node.openingElement.attributes.find((a) => a.type === 'JSXAttribute' && a.name.name === 'style')
  const base = []
  const entries = new Map()
  if (attr) {
    const v = attr.value
    if (!v || v.type !== 'JSXExpressionContainer' || v.expression.type === 'JSXEmptyExpression') return { error: MSG.listForm }
    const ex = v.expression
    const single = condObj(ex, name, isUndef)
    if (single) entries.set(single.n, objToMap(code, single.obj))
    else if (ex.type === 'ObjectExpression' && ex.properties.length && ex.properties.every((p) => p.type === 'SpreadElement') && ex.properties.some((p) => condObj(p.argument, name, isEmptyObj))) {
      for (const p of ex.properties) {
        const c = condObj(p.argument, name, isEmptyObj)
        if (c) entries.set(c.n, objToMap(code, c.obj))
        else base.push(code.slice(p.argument.start, p.argument.end))
      }
    } else base.push(code.slice(ex.start, ex.end))
  }
  const m = entries.get(N) || new Map()
  for (const [k, val] of Object.entries(styles)) {
    if (!k) continue
    if (val === '' || val === null || val === undefined) m.delete(k); else m.set(k, q(String(val)))
  }
  if (m.size) entries.set(N, m); else entries.delete(N)
  const objSrc = (mm) => `{ ${[...mm].map(([k, v]) => (k.startsWith('\0raw') ? v : `${IDENT.test(k) ? k : q(k)}: ${v}`)).join(', ')} }`
  const keys = [...entries.keys()].sort((a, b) => a - b)
  let val
  if (!base.length && !keys.length) {
    if (!attr) return { edits: [] }
    let s = attr.start
    while (s > 0 && /\s/.test(code[s - 1])) s--
    return { edits: [{ s, e: attr.end, t: '' }] }
  }
  if (!base.length && keys.length === 1) val = `${name} === ${keys[0]} ? ${objSrc(entries.get(keys[0]))} : undefined`
  else if (base.length === 1 && !keys.length) val = base[0]
  else val = `{ ${[...base.map((b) => `...(${b})`), ...keys.map((k) => `...(${name} === ${k} ? ${objSrc(entries.get(k))} : {})`)].join(', ')} }`
  if (attr) return { edits: [{ s: attr.value.start, e: attr.value.end, t: `{${val}}` }] }
  const at = attrInsertPos(node)
  return { edits: [{ s: at, e: at, t: ` style={${val}}` }] }
}

function instRemove(found, name, N) {
  const { node, ancestors } = found
  const parent = ancestors[ancestors.length - 1]
  if (parent?.type === 'ConditionalExpression' && parent.alternate === node && parent.consequent.type === 'NullLiteral') {
    const ns = parseIdxTest(parent.test, name)
    if (ns) return { edits: [{ s: parent.test.start, e: parent.test.end, t: idxTestSrc(name, [...new Set([...ns, N])].sort((a, b) => a - b)) }] }
  }
  const inJsx = parent && (parent.type === 'JSXElement' || parent.type === 'JSXFragment')
  return { edits: [{ s: node.start, e: node.start, t: `${inJsx ? '{' : '('}${name} === ${N} ? null : ` }, { s: node.end, e: node.end, t: inJsx ? '}' : ')' }] }
}

function instanceEdits(code, ast, found, ctx, patch, q) {
  const N = patch.index
  const edits = []
  let name = ctx.idxName
  if (!name) { name = freshName(ast); edits.push(...paramEdits(code, ctx.fn, name)) }
  const steps = []
  if (patch.removed) steps.push(() => instRemove(found, name, N))
  else {
    if (patch.text !== undefined && patch.text !== null) steps.push(() => instText(code, found.node, name, N, String(patch.text), q))
    if (patch.styles && typeof patch.styles === 'object') steps.push(() => instStyle(code, found.node, name, N, patch.styles, q))
    if (patch.href !== undefined && patch.href !== null) steps.push(() => instHref(code, found.node, name, N, String(patch.href), q))
  }
  for (const st of steps) { const x = st(); if (x.error) return x; edits.push(...x.edits) }
  return { edits }
}

/** 原始坐标经过若干批编辑后的新位置；落在被替换的区间里返回 null */
function mapPos(pos, batches) {
  for (const b of batches) {
    let d = 0
    for (const e of b) {
      if (e.s === e.e) { if (e.s <= pos) d += e.t.length }
      else if (e.e <= pos) d += e.t.length - (e.e - e.s)
      else if (e.s <= pos) return null
    }
    pos += d
  }
  return pos
}

function findJsxAt(ast, pos) {
  let found = null
  walkJsx(ast.program, (node, anc) => {
    if (found) return false
    if (node.type === 'JSXElement' && node.start === pos) { found = { node, ancestors: [...anc] }; return false }
  })
  return found
}

const isIndex = (v) => Number.isInteger(v) && v >= 0

function applyEdits(code, results, applied = []) {
  // 被删除元素内部的修改作废
  const removals = results.filter((r) => r.edits && r.patch.removed).map((r) => r.edits[0])
  for (const r of results) {
    if (!r.edits || r.patch.removed) continue
    if (r.edits.some((e) => removals.some((x) => e.s >= x.s && e.e <= x.e))) { r.error = MSG.removedParent; r.edits = null }
  }
  const all = []
  for (const r of results) if (r.edits) all.push(...r.edits)
  all.sort((a, b) => b.s - a.s || b.e - a.e)
  let out = code
  let lastStart = Infinity
  for (const e of all) {
    if (e.e > lastStart) continue
    let s = e.s
    if (e.trimBefore) while (s > 0 && /[ \t]/.test(out[s - 1])) s--
    out = out.slice(0, s) + e.t + out.slice(e.e)
    applied.push({ s, e: e.e, t: e.t })
    lastStart = s
  }
  return out
}

function applyJsxPatches(code, patches) {
  let ast
  try { ast = babelParse(code, babelOptions) } catch { return { code, results: patches.map((p) => ({ patch: p, error: MSG.parse })) } }
  const q = makeQuote(ast)
  const results = []
  const indexed = []
  for (const patch of patches) {
    const { line, col } = parseLoc(patch.loc)
    const found = findJsx(ast, line, col)
    if (!found) { results.push({ patch, error: MSG.notFound }); continue }
    if (patch.index !== undefined && patch.index !== null) {
      if (!isIndex(patch.index)) { results.push({ patch, error: MSG.badIndex }); continue }
      const ctx = mapContext(found.ancestors)
      if (ctx.error) { results.push({ patch, error: ctx.error }); continue }
      if (ctx.fn) { const r = { patch, error: null }; results.push(r); indexed.push({ r, start: found.node.start }); continue }
      if (patch.index > 0 || Number(patch.sameLocCount) > 1) { results.push({ patch, error: MSG.reusable }); continue }
      // 不在列表里且只有一个实例：按普通补丁处理
    }
    let r
    if (patch.removed) r = jsxRemoveEdits(code, found)
    else {
      const edits = []
      r = { edits }
      const steps = []
      if (patch.text !== undefined && patch.text !== null) steps.push(() => jsxTextEdits(code, found.node, String(patch.text), q))
      if (patch.styles && typeof patch.styles === 'object') steps.push(() => jsxStyleEdits(code, found.node, patch.styles, q))
      if (patch.href !== undefined && patch.href !== null) steps.push(() => jsxHrefEdits(code, found.node, String(patch.href), q))
      for (const st of steps) { const x = st(); if (x.error) { r = x; break } edits.push(...x.edits) }
    }
    results.push(r.error ? { patch, error: r.error } : { patch, error: null, edits: r.edits })
  }
  const first = []
  let out = applyEdits(code, results, first)
  // 列表项补丁逐个应用：每次重新解析，用位置映射找回元素（同一回调的多个补丁可以叠加）
  const batches = [first]
  for (const { r, start } of indexed) {
    const pos = mapPos(start, batches)
    if (pos === null) { r.error = MSG.removedParent; continue }
    let cur
    try { cur = babelParse(out, babelOptions) } catch { r.error = MSG.parse; continue }
    const found = findJsxAt(cur, pos)
    if (!found) { r.error = MSG.notFound; continue }
    const ctx = mapContext(found.ancestors)
    if (!ctx.fn) { r.error = ctx.error || MSG.notFound; continue }
    const x = instanceEdits(out, cur, found, ctx, r.patch, q)
    if (x.error) { r.error = x.error; continue }
    const applied = []
    out = applyEdits(out, [{ patch: {}, edits: x.edits }], applied)
    batches.push(applied)
  }
  // 自检：改完必须还能解析
  if (out !== code) {
    try { babelParse(out, babelOptions) } catch (e) {
      return { code, results: results.map((r) => ({ patch: r.patch, error: r.error || `${MSG.parse}（${e.message}）` })), batches: [] }
    }
  }
  return { code: out, results, batches }
}

// ====================== HTML ======================

function findHtml(doc, line, col) {
  let found = null
  const visit = (node, parent) => {
    for (const c of node.childNodes || []) {
      if (found) return
      if (c.tagName && c.sourceCodeLocation?.startTag) {
        const st = c.sourceCodeLocation.startTag
        if (st.startLine === line && st.startCol - 1 === col) { found = { node: c, parent }; return }
      }
      if (c.content) visit(c.content, c)
      visit(c, c)
    }
  }
  visit(doc, null)
  return found
}

const escHtmlText = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const escAttr = (t) => t.replace(/&/g, '&amp;').replace(/"/g, '&quot;')

function parseStyleDecls(s) {
  const out = []
  let cur = '', depth = 0, quote = null
  for (const ch of s) {
    if (quote) { cur += ch; if (ch === quote) quote = null; continue }
    if (ch === '"' || ch === "'") { quote = ch; cur += ch; continue }
    if (ch === '(') depth++
    if (ch === ')') depth--
    if (ch === ';' && depth <= 0) { out.push(cur); cur = ''; continue }
    cur += ch
  }
  out.push(cur)
  return out.map((d) => { const i = d.indexOf(':'); return i < 0 ? null : [d.slice(0, i).trim(), d.slice(i + 1).trim()] }).filter((d) => d && d[0])
}

function decodeAttr(v) { return v.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&') }

function htmlPatchEdits(html, found, patch) {
  const el = found.node
  const loc = el.sourceCodeLocation
  const st = loc.startTag
  const edits = []
  const tagEnd = st.startOffset + 1 + el.tagName.length // 标签名之后
  if (patch.removed) {
    if (!found.parent || el.tagName === 'body' || el.tagName === 'html' || found.parent.tagName === 'html' || found.parent.nodeName === '#document') return { error: MSG.root }
    const r = wholeLine(html, loc.startOffset, loc.endOffset)
    edits.push({ s: r.s, e: r.e, t: '' })
    return { edits }
  }
  if (patch.text !== undefined && patch.text !== null) {
    const text = String(patch.text)
    const kids = el.childNodes || []
    const texts = kids.filter((c) => c.nodeName === '#text' && c.value.trim())
    const elems = kids.filter((c) => c.tagName)
    if (VOID_HTML.has(el.tagName)) return { error: MSG.noText }
    if (!texts.length && elems.length) return { error: MSG.noText }
    if (!texts.length) {
      if (!loc.endTag) return { error: MSG.noText }
      edits.push({ s: st.endOffset, e: loc.endTag.startOffset, t: escHtmlText(text) })
    } else {
      texts.forEach((n, i) => {
        const tl = n.sourceCodeLocation
        const raw = html.slice(tl.startOffset, tl.endOffset)
        if (i === 0) {
          const lead = /^\s*/.exec(raw)[0], trail = /\s*$/.exec(raw)[0]
          edits.push({ s: tl.startOffset, e: tl.endOffset, t: lead + escHtmlText(text) + trail })
        } else {
          edits.push({ s: tl.startOffset, e: tl.endOffset, t: /\n/.test(raw) ? raw.replace(/\S[\s\S]*\S|\S/, '') : '' })
        }
      })
    }
  }
  if (patch.styles && typeof patch.styles === 'object') {
    const attrLoc = loc.startTag.attrs?.style
    const cur = el.attrs.find((a) => a.name === 'style')
    const decls = cur ? parseStyleDecls(cur.value) : []
    for (const [k, v] of Object.entries(patch.styles)) {
      const name = camelToKebab(k)
      const idx = decls.findIndex((d) => d[0].toLowerCase() === name)
      const remove = v === '' || v === null || v === undefined
      if (idx >= 0) { if (remove) decls.splice(idx, 1); else decls[idx][1] = String(v) }
      else if (!remove) decls.push([name, String(v)])
    }
    const value = decls.map(([k, v]) => `${k}: ${v}`).join('; ')
    if (attrLoc) edits.push({ s: attrLoc.startOffset, e: attrLoc.endOffset, t: value ? `style="${escAttr(value)}"` : '' , trimBefore: !value })
    else if (value) edits.push({ s: tagEnd, e: tagEnd, t: ` style="${escAttr(value)}"` })
  }
  if (patch.href !== undefined && patch.href !== null) {
    if (el.tagName !== 'a') return { error: MSG.notLink }
    const attrLoc = loc.startTag.attrs?.href
    if (attrLoc) edits.push({ s: attrLoc.startOffset, e: attrLoc.endOffset, t: `href="${escAttr(String(patch.href))}"` })
    else edits.push({ s: tagEnd, e: tagEnd, t: ` href="${escAttr(String(patch.href))}"` })
  }
  return { edits }
}

function applyHtmlPatches(html, patches) {
  let doc
  try { doc = parseHtml(html, { sourceCodeLocationInfo: true }) } catch { return { code: html, results: patches.map((p) => ({ patch: p, error: MSG.parse })) } }
  const results = []
  for (const patch of patches) {
    const { line, col } = parseLoc(patch.loc)
    const found = findHtml(doc, line, col)
    if (!found) { results.push({ patch, error: MSG.notFound }); continue }
    const r = htmlPatchEdits(html, found, patch)
    if (r.error) { results.push({ patch, error: r.error }); continue }
    results.push({ patch, error: null, edits: r.edits })
  }
  const applied = []
  const out = applyEdits(html, results, applied)
  return { code: out, results, batches: [applied] }
}


// ---------------- locMap：写回后其他元素的位置变化 ----------------
// 用写回时实际执行的编辑记录把每个元素的旧位置映射到新位置（被编辑区间覆盖的元素 = 已删除），
// 再用新代码的标注结果核对，保证映射出的 loc 确实是新代码里同一个元素的开始标签。

function lineStarts(code) {
  const st = [0]
  for (let i = 0; i < code.length; i++) if (code.charCodeAt(i) === 10) st.push(i + 1)
  return st
}
const toOffset = (starts, line, col) => starts[line - 1] + col
function toLineCol(starts, off) {
  let lo = 0, hi = starts.length - 1
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (starts[mid] <= off) lo = mid; else hi = mid - 1 }
  return [lo + 1, off - starts[lo]]
}

/** 标注结果里的全部 loc（与预览里 data-loc 完全一致） */
export function annotatedLocs(code, rel) {
  const out = /\.html?$/i.test(rel) ? annotateHtml(code, rel, { injectBridge: false }) : annotateJsx(code, rel, { sourceMap: false })?.code
  if (!out) return []
  return [...out.matchAll(/data-loc="([^"]+)"/g)].map((m) => m[1])
}

export function computeLocMap(rel, before, after, batches) {
  const map = {}
  if (before === after) return map
  const oldStarts = lineStarts(before), newStarts = lineStarts(after)
  const afterSet = new Set(annotatedLocs(after, rel))
  for (const loc of annotatedLocs(before, rel)) {
    const { line, col } = parseLoc(loc)
    const pos = mapPos(toOffset(oldStarts, line, col), batches)
    if (pos === null) { map[loc] = null; continue }
    const [l2, c2] = toLineCol(newStarts, pos)
    const next = `${rel}:${l2}:${c2}`
    if (!afterSet.has(next)) { map[loc] = null; continue } // 防御：映射不到新元素时按已失效处理
    if (next !== loc) map[loc] = next
  }
  return map
}

// ====================== 入口 ======================

/**
 * 把补丁写回源码。全部成功才写文件（失败时 Java 让任务失败，不提交部分结果）。
 * 返回 { applied, failures: [{loc, reason}] }
 */
export function applyVisualPatches(cwd, patches, { write = true } = {}) {
  if (!Array.isArray(patches)) throw Object.assign(new Error('patches 必须是数组'), { code: 'BAD_REQUEST' })
  const failures = []
  const byFile = new Map()
  for (const patch of patches) {
    const loc = patch && parseLoc(patch.loc)
    if (!loc) { failures.push({ loc: String(patch?.loc ?? ''), reason: MSG.badLoc }); continue }
    const rel = loc.file
    const abs = path.resolve(cwd, rel)
    const r = path.relative(path.resolve(cwd), abs)
    if (!r || r.startsWith('..') || path.isAbsolute(r) || r.split(path.sep).some((s) => s === 'node_modules' || s === '.git' || s === '.zhiye')) { failures.push({ loc: patch.loc, reason: MSG.badFile }); continue }
    if (!existsSync(abs) || !lstatSync(abs).isFile()) { failures.push({ loc: patch.loc, reason: MSG.badFile }); continue }
    if (!/\.(jsx|tsx|html?)$/i.test(rel)) { failures.push({ loc: patch.loc, reason: MSG.unsupported }); continue }
    if (!byFile.has(abs)) byFile.set(abs, [])
    byFile.get(abs).push(patch)
  }
  let applied = 0
  const outputs = []
  for (const [abs, list] of byFile) {
    const code = readFileSync(abs, 'utf8')
    const r = /\.html?$/i.test(abs) ? applyHtmlPatches(code, list) : applyJsxPatches(code, list)
    for (const x of r.results) {
      if (x.error) failures.push({ loc: x.patch.loc, reason: x.error })
      else applied++
    }
    if (r.code !== code) outputs.push([abs, r.code, code, r.batches])
  }
  const locMap = {}
  if (failures.length === 0) {
    for (const [abs, code, before, batches] of outputs) {
      if (write) writeFileSync(abs, code)
      Object.assign(locMap, computeLocMap(path.relative(cwd, abs).split(path.sep).join('/'), before, code, batches))
    }
  }
  return { applied, failures, locMap, changedFiles: outputs.map(([abs]) => path.relative(cwd, abs).split(path.sep).join('/')) }
}

export { applyJsxPatches, applyHtmlPatches }
