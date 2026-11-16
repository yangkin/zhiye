// @zhiye/vite-plugin-loc
// 预览构建专用：给每个原生 JSX 元素加上 data-loc="文件:行:列" 和 data-zt（文字类型），
// 并把织页可视化编辑桥内联进 index.html。发布构建不使用本插件。
import { parse } from '@babel/parser'
import MagicString from 'magic-string'
import { parse as parseHtml } from 'parse5'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const BRIDGE_PATH = process.env.ZHIYE_BRIDGE_PATH || path.resolve(here, '../editor-bridge/bridge.js')

export function bridgeSource() {
  return readFileSync(BRIDGE_PATH, 'utf8')
}

/** 文字类型：s=纯文字；e=自身文字+子元素（如 label 内含 input）；d=含表达式（动态）；n=无文字 */
function jsxTextKind(children) {
  let text = false, elements = false, dynamic = false
  for (const c of children || []) {
    if (c.type === 'JSXText') { if (c.value.trim()) text = true }
    else if (c.type === 'JSXExpressionContainer') {
      const e = c.expression
      if (e.type === 'JSXEmptyExpression') continue
      if (e.type === 'StringLiteral' || (e.type === 'TemplateLiteral' && e.expressions.length === 0)) text = true
      else dynamic = true
    } else if (c.type === 'JSXElement' || c.type === 'JSXFragment') elements = true
  }
  if (dynamic) return 'd'
  if (text && elements) return 'e'
  if (text) return 's'
  return 'n'
}

function walk(node, visit) {
  if (!node || typeof node.type !== 'string') return
  visit(node)
  for (const key of Object.keys(node)) {
    if (key === 'loc' || key === 'start' || key === 'end' || key === 'extra' || key === 'leadingComments' || key === 'trailingComments') continue
    const v = node[key]
    if (Array.isArray(v)) { for (const x of v) if (x && typeof x.type === 'string') walk(x, visit) }
    else if (v && typeof v.type === 'string') walk(v, visit)
  }
}

/** 给 JSX/TSX 源码中的原生元素加 data-loc。返回 { code, map } 或 null。 */
export function annotateJsx(code, relPath, { sourceMap = true } = {}) {
  let ast
  try {
    ast = parse(code, { sourceType: 'module', plugins: ['jsx', 'typescript'], errorRecovery: false })
  } catch {
    return null
  }
  const s = new MagicString(code)
  let changed = false
  walk(ast.program, (node) => {
    if (node.type !== 'JSXElement') return
    const open = node.openingElement
    if (open.name.type !== 'JSXIdentifier') return
    const name = open.name.name
    if (!/^[a-z]/.test(name)) return // 组件不加，只加原生元素
    if (open.attributes.some((a) => a.type === 'JSXAttribute' && a.name?.name === 'data-loc')) return
    const loc = `${relPath}:${open.loc.start.line}:${open.loc.start.column}`
    const kind = jsxTextKind(node.children)
    s.appendLeft(open.name.end, ` data-loc="${loc}" data-zt="${kind}"`)
    changed = true
  })
  if (!changed) return null
  return { code: s.toString(), map: sourceMap ? s.generateMap({ hires: true, source: relPath }) : null }
}

const SKIP_HTML = new Set(['script', 'style', 'link', 'meta', 'noscript', 'template', 'head', 'html', 'title', 'base', 'br', 'wbr'])

function htmlTextKind(node) {
  let text = false, elements = false
  for (const c of node.childNodes || []) {
    if (c.nodeName === '#text') { if (c.value.trim()) text = true }
    else if (c.tagName) elements = true
  }
  if (text && elements) return 'e'
  if (text) return 's'
  return 'n'
}

/** 给静态 HTML 中 body 内的元素加 data-loc，并内联编辑桥。 */
export function annotateHtml(html, relPath, { injectBridge = true } = {}) {
  const doc = parseHtml(html, { sourceCodeLocationInfo: true })
  const inserts = []
  const visit = (node, inBody) => {
    for (const c of node.childNodes || []) {
      if (!c.tagName) continue
      const body = inBody || c.tagName === 'body'
      if (body && c.tagName !== 'body' && !SKIP_HTML.has(c.tagName) && c.sourceCodeLocation?.startTag) {
        const st = c.sourceCodeLocation.startTag
        const loc = `${relPath}:${st.startLine}:${st.startCol - 1}`
        inserts.push({ at: st.startOffset + 1 + c.tagName.length, text: ` data-loc="${loc}" data-zt="${htmlTextKind(c)}"` })
      }
      if (c.content) visit(c.content, body)
      visit(c, body)
    }
  }
  visit(doc, false)
  inserts.sort((a, b) => b.at - a.at)
  let out = html
  for (const i of inserts) out = out.slice(0, i.at) + i.text + out.slice(i.at)
  if (injectBridge) {
    const tag = `<script data-zhiye-editor-ui="1">${bridgeSource()}</script>`
    out = /<\/body>/i.test(out) ? out.replace(/<\/body>(?![\s\S]*<\/body>)/i, tag + '</body>') : out + tag
  }
  return out
}

/** Vite 插件 */
export default function zhiyeLoc() {
  let root = process.cwd()
  return {
    name: 'zhiye-loc',
    enforce: 'pre',
    configResolved(cfg) { root = cfg.root },
    transform(code, id) {
      const file = id.split('?')[0]
      if (!/\.(t|j)sx$/.test(file) || file.includes('/node_modules/')) return null
      const rel = path.relative(root, file).split(path.sep).join('/')
      return annotateJsx(code, rel)
    },
    transformIndexHtml: {
      order: 'post',
      handler() {
        return [{ tag: 'script', attrs: { 'data-zhiye-editor-ui': '1' }, children: bridgeSource(), injectTo: 'body' }]
      },
    },
  }
}
