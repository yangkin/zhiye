// 生成过程的「步骤详情」和待办清单：tool 结束事件的 detail、todo_write 工具。
// 所有路径都换成相对工作区的路径，不暴露沙箱里的绝对路径。
import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs'
import path from 'node:path'
import { structuredPatch } from 'diff'
import { Type } from 'typebox'
import { takeScreenshot } from './screenshot.mjs'

const MAX_LINES = 200
const MAX_BYTES = 12 * 1024
const BASH_TAIL = 4 * 1024
const FILE_LIMIT = 2 * 1024 * 1024 // 超过的文件不做预览 / diff

// ---------------- 路径和文字脱敏 ----------------

function roots(cwd) {
  const list = [path.resolve(cwd)]
  try { const r = realpathSync(cwd); if (!list.includes(r)) list.push(r) } catch {}
  // macOS：/var ↔ /private/var
  for (const r of [...list]) {
    if (r.startsWith('/private/')) list.push(r.slice('/private'.length))
    else if (/^\/(var|tmp)\//.test(r)) list.push('/private' + r)
  }
  return [...new Set(list)].sort((a, b) => b.length - a.length)
}

/** 工具参数里的路径 → 相对工作区的路径；工作区以外的路径只保留文件名 */
export function relPath(cwd, p) {
  if (p === undefined || p === null) return ''
  let s = String(p).trim().replace(/^@/, '')
  if (!s) return ''
  if (s === '~' || s.startsWith('~/')) return '（工作区外）/' + path.basename(s)
  const abs = path.isAbsolute(s) ? s : path.resolve(cwd, s)
  for (const r of roots(cwd)) {
    const rel = path.relative(r, abs)
    if (rel === '') return '.'
    if (!rel.startsWith('..') && !path.isAbsolute(rel)) return rel.split(path.sep).join('/')
  }
  return '（工作区外）/' + path.basename(abs)
}

/** 文字里的工作区绝对路径换成 .，其他用户目录下的绝对路径换成 ~ */
export function sanitizeText(cwd, text, { home = process.env.HOME } = {}) {
  let s = String(text ?? '')
  for (const r of roots(cwd)) s = s.split(r + '/').join('./').split(r).join('.')
  if (home && home.length > 1) s = s.split(home).join('~')
  s = s.replace(/\/Users\/[^/\s'"`]+/g, '~').replace(/\/home\/[^/\s'"`]+/g, '~')
  return s.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').replace(/\\/g, '/')
}

const countLines = (s) => (s === '' ? 0 : s.split('\n').length - (s.endsWith('\n') ? 1 : 0))

/** 前 maxLines 行 / maxBytes 字节 */
export function headText(s, maxLines = MAX_LINES, maxBytes = MAX_BYTES) {
  let out = s
  let truncated = false
  const lines = out.split('\n')
  if (lines.length > maxLines) { out = lines.slice(0, maxLines).join('\n'); truncated = true }
  if (Buffer.byteLength(out) > maxBytes) {
    out = Buffer.from(out).subarray(0, maxBytes).toString('utf8').replace(/�$/, '')
    out = out.slice(0, out.lastIndexOf('\n') > 0 ? out.lastIndexOf('\n') : out.length)
    truncated = true
  }
  return { text: out, truncated }
}

function tailText(s, maxBytes) {
  if (Buffer.byteLength(s) <= maxBytes) return { text: s, truncated: false }
  const buf = Buffer.from(s)
  let t = buf.subarray(buf.length - maxBytes).toString('utf8').replace(/^�+/, '')
  const nl = t.indexOf('\n')
  if (nl >= 0 && nl < 200) t = t.slice(nl + 1)
  return { text: t, truncated: true }
}

function readSmall(abs) {
  try {
    const st = statSync(abs)
    if (!st.isFile() || st.size > FILE_LIMIT) return null
    const buf = readFileSync(abs)
    if (buf.includes(0)) return null // 二进制
    return buf.toString('utf8')
  } catch { return null }
}

// ---------------- 统一 diff ----------------

/** 单个文件改动的统一 diff（不含 ---/+++ 以外的路径信息），返回 { diff, added, removed, truncated } */
export function unifiedDiff(relp, before, after) {
  const p = structuredPatch(`a/${relp}`, `b/${relp}`, before, after, '', '', { context: 3 })
  let added = 0, removed = 0
  const out = [`--- a/${relp}`, `+++ b/${relp}`]
  for (const h of p.hunks) {
    out.push(`@@ -${h.oldStart},${h.oldLines} +${h.newStart},${h.newLines} @@`)
    for (const l of h.lines) {
      if (l.startsWith('+')) added++
      else if (l.startsWith('-')) removed++
      if (l.startsWith('\\')) continue // \ No newline at end of file
      out.push(l)
    }
  }
  const { text, truncated } = headText(out.join('\n'))
  return { diff: text + (truncated ? '\n…（diff 太长，已截断）' : ''), added, removed, truncated }
}

// ---------------- 工具开始前的快照 + 结束时的详情 ----------------

/** 在工具执行前记录需要的状态（文件是否存在、修改前的内容） */
export function snapshotBefore(cwd, toolName, args = {}) {
  if (toolName !== 'write' && toolName !== 'edit') return null
  const abs = path.resolve(cwd, String(args.path ?? '').replace(/^@/, ''))
  const existed = existsSync(abs)
  return { abs, existed, before: existed ? readSmall(abs) : '' }
}

function resultText(result) {
  if (!result) return ''
  if (typeof result === 'string') return result
  if (Array.isArray(result.content)) return result.content.filter((c) => c.type === 'text').map((c) => c.text).join('\n')
  return ''
}

function bashExitCode(result, isError) {
  const sc = result?.structuredContent
  if (sc && Number.isInteger(sc.exit_code)) return sc.exit_code
  const m = /Command exited with code (\d+)/.exec(resultText(result))
  if (m) return Number(m[1])
  if (/安全守卫拦截/.test(resultText(result))) return null
  return isError ? null : 0
}

/** tool 结束事件的 detail 和 summary 计数 */
export function stepDetail(cwd, toolName, args = {}, snap, result, isError) {
  const p = relPath(cwd, args.path)
  const blocked = isError && /安全守卫拦截/.test(resultText(result))
  switch (toolName) {
    case 'write': {
      const content = typeof args.content === 'string' ? args.content : ''
      const created = !snap?.existed
      const lines = countLines(content)
      const { text, truncated } = headText(sanitizeText(cwd, content))
      const detail = { kind: 'write', path: p, created, lines, preview: text, ...(truncated ? { truncated: true } : {}) }
      if (isError) detail.error = sanitizeText(cwd, resultText(result)).slice(0, 1000)
      return { detail, counts: isError ? '' : ` +${lines} 行` }
    }
    case 'edit': {
      const before = snap?.before
      const after = !isError && snap ? readSmall(snap.abs) : null
      let d
      if (typeof before === 'string' && typeof after === 'string') d = unifiedDiff(p, before, after)
      else {
        // 文件太大或读取失败：用编辑参数拼一个近似 diff
        const edits = Array.isArray(args.edits) ? args.edits : []
        const lines = [`--- a/${p}`, `+++ b/${p}`]
        let added = 0, removed = 0
        for (const e of edits) {
          const o = String(e.oldText ?? '').split('\n'), n = String(e.newText ?? '').split('\n')
          lines.push('@@', ...o.map((l) => '-' + l), ...n.map((l) => '+' + l))
          removed += o.length; added += n.length
        }
        const h = headText(lines.join('\n'))
        d = { diff: h.text, added: isError ? 0 : added, removed: isError ? 0 : removed, truncated: h.truncated }
      }
      const detail = { kind: 'edit', path: p, added: isError ? 0 : d.added, removed: isError ? 0 : d.removed, diff: isError ? '' : sanitizeText(cwd, d.diff), ...(d.truncated ? { truncated: true } : {}) }
      if (isError) detail.error = sanitizeText(cwd, resultText(result)).slice(0, 1000)
      return { detail, counts: isError ? '' : ` +${detail.added} −${detail.removed}` }
    }
    case 'read': {
      let lines = 0
      if (!isError) {
        const text = resultText(result)
        lines = countLines(text.replace(/\n*\[[^\]]*(?:lines|行)[^\]]*\]\s*$/, ''))
      }
      const detail = { kind: 'read', path: p, lines }
      if (isError) detail.error = sanitizeText(cwd, resultText(result)).slice(0, 1000)
      return { detail, counts: isError ? '' : ` ${lines} 行` }
    }
    case 'bash': {
      const sc = result?.structuredContent
      const raw = typeof sc?.output === 'string' ? sc.output : resultText(result)
      const { text, truncated } = tailText(sanitizeText(cwd, raw), BASH_TAIL)
      const exitCode = blocked ? null : bashExitCode(result, isError)
      const detail = { kind: 'bash', command: sanitizeText(cwd, String(args.command ?? '')).slice(0, 2000), exitCode, output: text, ...(truncated ? { truncated: true } : {}) }
      if (blocked) detail.blocked = true
      return { detail, counts: '' }
    }
    default:
      return { detail: null, counts: '' }
  }
}

// ---------------- 摘要 ----------------

export function toolSummary(cwd, name, args = {}, { created } = {}) {
  switch (name) {
    case 'read': {
      const skill = /(?:^|\/)skills\/([a-z0-9][a-z0-9-]{0,63})\/SKILL\.md$/.exec(String(args.path ?? ''))
      const inside = path.resolve(cwd, String(args.path ?? '').replace(/^@/, '')).startsWith(path.resolve(cwd) + path.sep)
      return skill && !inside ? `加载技能 · ${skill[1]}` : `读取 ${relPath(cwd, args.path)}`
    }
    case 'write': {
      const isNew = created ?? !existsSync(path.resolve(cwd, String(args.path ?? '').replace(/^@/, '')))
      return `${isNew ? '创建' : '改写'} ${relPath(cwd, args.path)}`
    }
    case 'edit': return `修改 ${relPath(cwd, args.path)}`
    case 'bash': {
      // 不向用户暴露服务器路径：工作区路径换成「.」，其他绝对路径只保留文件名
      let c = String(args.command || '').replace(/\s+/g, ' ').trim()
      for (const r of roots(cwd)) c = c.split(r + '/').join('').split(r).join('.')
      c = c.replace(/(^|[\s'"=])\/(?:[^\s'"/]+\/)+([^\s'"/]*)/g, (_, pre, last) => pre + '…/' + last)
      return `执行命令：${c.length > 60 ? c.slice(0, 60) + '…' : c}`
    }
    case 'todo_write': return '更新待办清单'
    case 'request_confirm': return args.kind === 'cloud' ? '启用 织页云服务' : '请求确认'
    case 'screenshot': return `截图自查 · ${args.device === 'mobile' ? '手机' : args.device === 'wide' ? '宽屏 1920' : '桌面'}${args.route ? ' ' + String(args.route).slice(0, 40) : ''}`
    default: return `调用工具 ${name}`
  }
}

// ---------------- todo_write ----------------

export const TODO_STATUSES = ['pending', 'in_progress', 'completed']

/** 去控制字符、合并空白、路径脱敏（不截断）；长度校验用这个结果，避免长绝对路径误报。 */
export function normalizeTodoContent(cwd, s) {
  return sanitizeText(cwd, String(s ?? '')).replace(/[\u0000-\u001f\u007f\u2028\u2029]/g, ' ').replace(/\s+/g, ' ').trim()
}

/** 清洗一条待办文字：去控制字符、合并空白、路径脱敏、截到 80 字 */
export function sanitizeTodoContent(cwd, s) {
  let t = normalizeTodoContent(cwd, s)
  if ([...t].length > 80) t = [...t].slice(0, 79).join('') + '…'
  return t
}

/** 校验并清洗 todo_write 参数；返回 { items } 或 { error } */
export function validateTodos(cwd, params) {
  const items = params?.items
  if (!Array.isArray(items)) return { error: 'items 必须是数组' }
  if (items.length < 1 || items.length > 8) return { error: `待办清单需要 1～8 项，现在是 ${items.length} 项` }
  const out = []
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    if (!it || typeof it !== 'object') return { error: `第 ${i + 1} 项格式不正确` }
    if (typeof it.content !== 'string' || !it.content.trim()) return { error: `第 ${i + 1} 项缺少 content` }
    const normalized = normalizeTodoContent(cwd, it.content)
    if (!normalized) return { error: `第 ${i + 1} 项缺少 content` }
    if ([...normalized].length > 80) return { error: `第 ${i + 1} 项太长（不超过 80 字），请写成一个简短的步骤` }
    if (!TODO_STATUSES.includes(it.status)) return { error: `第 ${i + 1} 项的 status 只能是 pending / in_progress / completed` }
    out.push({ content: sanitizeTodoContent(cwd, it.content), status: it.status })
  }
  const active = out.filter((x) => x.status === 'in_progress').length
  if (active > 1) return { error: `同一时间只能有一项 in_progress，现在有 ${active} 项` }
  return { items: out }
}

export const TODO_DESCRIPTION = `维护本次任务的待办清单（用户在界面上实时看到）。每次调用都传入完整清单（1～8 项，每项不超过 80 字）。
开始工作时先列出 3～6 个具体步骤；开始做某一步前把它标为 in_progress（同一时间最多一项），做完立刻标为 completed；结束前所有步骤都应是 completed。`

/** 注册 todo_write 工具的 Pi 内联扩展。onTodo(items) 在每次合法调用时触发。 */
export function createTodoExtension({ cwd, onTodo }) {
  return (pi) => {
    pi.registerTool({
      name: 'todo_write',
      label: '待办清单',
      description: TODO_DESCRIPTION,
      parameters: Type.Object({
        items: Type.Array(Type.Object({
          content: Type.String({ description: '步骤内容，简短的中文，不超过 80 字' }),
          status: Type.Union([Type.Literal('pending'), Type.Literal('in_progress'), Type.Literal('completed')]),
        }), { description: '完整的待办清单（1～8 项）' }),
      }),
      async execute(_id, params) {
        const r = validateTodos(cwd, params)
        if (r.error) throw new Error(`待办清单无效：${r.error}`)
        onTodo(r.items)
        const done = r.items.filter((x) => x.status === 'completed').length
        const cur = r.items.find((x) => x.status === 'in_progress')
        return { content: [{ type: 'text', text: `已更新待办清单：${done}/${r.items.length} 完成${cur ? `；进行中：${cur.content}` : ''}` }], details: {} }
      },
    })
  }
}

// ---------------- 需要用户确认的操作 ----------------

export const CONFIRM_KINDS = ['cloud']
export const CONFIRM_TIMEOUT_MS = 10 * 60 * 1000

/**
 * 请求用户确认的工具：发 { type:'confirm', id, kind, reason, state:'pending' }，等平台转来的 confirm 命令；
 * 结束时再发一次 state = accepted / declined / timeout。onAccepted(kind) 返回确认后要告诉模型的用法说明。
 */
export function createConfirmExtension({ emit, waiters, onAccepted = () => '', timeoutMs = CONFIRM_TIMEOUT_MS }) {
  return (pi) => {
    pi.registerTool({
      name: 'request_confirm',
      label: '请求确认',
      description: '需要用户确认才能做的操作（目前只有 kind=cloud：为这个应用启用云服务——数据库和文件存储）。调用后界面会弹出确认卡，等用户选择后返回结果。',
      parameters: Type.Object({
        kind: Type.Union(CONFIRM_KINDS.map((k) => Type.Literal(k)), { description: '操作类型：cloud = 启用云服务' }),
        reason: Type.String({ description: '一句话说明为什么需要（显示给用户），不超过 60 字' }),
      }),
      async execute(toolCallId, params, signal) {
        const kind = params?.kind
        if (!CONFIRM_KINDS.includes(kind)) throw new Error(`不支持的确认类型：${kind}`)
        const reason = String(params?.reason || '').trim().slice(0, 120)
        const id = 'cf_' + String(toolCallId || Date.now()).replace(/[^a-zA-Z0-9_-]/g, '').slice(-24)
        emit({ type: 'confirm', id, kind, reason, state: 'pending' })
        const answer = await new Promise((resolve) => {
          const done = (v) => { clearTimeout(timer); waiters.delete(id); signal?.removeEventListener?.('abort', onAbort); resolve(v) }
          const onAbort = () => done({ state: 'aborted' })
          const timer = setTimeout(() => done({ state: 'timeout' }), timeoutMs)
          waiters.set(id, (r) => done({ state: r?.accept ? 'accepted' : 'declined', note: r?.note }))
          if (signal?.aborted) onAbort()
          else signal?.addEventListener?.('abort', onAbort, { once: true })
        })
        emit({ type: 'confirm', id, kind, reason, state: answer.state === 'aborted' ? 'declined' : answer.state })
        if (answer.state === 'aborted') throw new Error('任务已停止')
        let text
        if (answer.state === 'accepted') text = `用户已确认，云服务已启用。${answer.note ? answer.note + '\n' : ''}\n${onAccepted(kind) || ''}`.trim()
        else if (answer.state === 'timeout') text = '用户一直没有回应，按「暂不启用」处理：不要使用云服务，改用 localStorage 保存数据，并在总结里说明。'
        else text = '用户选择暂不启用云服务：不要使用云服务，改用 localStorage 保存数据，并在总结里说明数据只存在当前浏览器、以后可以再启用。'
        return { content: [{ type: 'text', text }], details: { state: answer.state } }
      },
    })
  }
}

// ---------------- 思考过程 ----------------

/** 摘要同款脱敏：工作区路径换成 .，其他绝对路径只保留最后一段 */
export function sanitizeInline(cwd, text) {
  let s = String(text ?? '')
  for (const r of roots(cwd)) s = s.split(r + '/').join('').split(r).join('.')
  return s.replace(/(^|[\s'"=(（:：`])\/(?:[^\s'"/`()（）]+\/)+([^\s'"/`()（）]*)/g, (_, pre, last) => pre + '…/' + last)
}

export const THINKING_LIMIT = 20 * 1024
export const THINKING_INTERVAL = 300

/**
 * 思考过程的事件流：增量合并后最多每 300ms 发一条 { type:'thinking', text }，
 * 块结束时发 { type:'thinking_end', durationMs }。整个任务最多发 20 KB 文字，超过后只发 thinking_end。
 * 末尾可能是被截断的路径，先留在缓冲里等下一段一起脱敏。
 */
export class ThinkingStream {
  constructor({ cwd, emit, now = () => Date.now(), setTimer = setTimeout, clearTimer = clearTimeout, limit = THINKING_LIMIT, interval = THINKING_INTERVAL }) {
    Object.assign(this, { cwd, emit, now, setTimer, clearTimer, limit, interval })
    this.sent = 0
    this.buf = ''
    this.startedAt = null
    this.timer = null
    this.lastEmit = -Infinity
  }
  start() {
    if (this.startedAt === null) this.startedAt = this.now()
  }
  delta(text) {
    if (!text) return
    this.start()
    if (this.sent >= this.limit) return
    this.buf += text
    if (this.timer) return
    const wait = Math.max(0, this.lastEmit + this.interval - this.now())
    this.timer = this.setTimer(() => { this.timer = null; this.flush(false) }, wait)
  }
  flush(final) {
    if (this.timer) { this.clearTimer(this.timer); this.timer = null }
    if (!this.buf) return
    let take = this.buf
    if (!final) {
      // 末尾不含空白的一段可能是没写完的路径，留到下次
      const m = /\S{1,300}$/.exec(take)
      if (m && m.index > 0 && /\//.test(m[0])) take = take.slice(0, m.index)
      if (!take) return
    }
    this.buf = this.buf.slice(take.length)
    let text = sanitizeInline(this.cwd, take)
    const room = this.limit - this.sent
    if (room <= 0) { this.buf = ''; return }
    if (Buffer.byteLength(text) > room) text = Buffer.from(text).subarray(0, room).toString('utf8').replace(/�$/, '')
    this.sent += Buffer.byteLength(text)
    if (this.sent >= this.limit) this.buf = ''
    this.lastEmit = this.now()
    if (text) this.emit({ type: 'thinking', text })
  }
  end() {
    if (this.startedAt === null) return
    this.flush(true)
    this.emit({ type: 'thinking_end', durationMs: Math.max(0, this.now() - this.startedAt) })
    this.startedAt = null
  }
}

// ---------------- 截图自查 ----------------

/** screenshot 工具：构建后用无头浏览器打开，返回截图（模型能看图时）和页面文字摘要 */
export function createScreenshotExtension({ cwd, projectKind, env = process.env, shoot = takeScreenshot }) {
  return (pi) => {
    pi.registerTool({
      name: 'screenshot',
      label: '截图自查',
      description: '构建当前代码并在无头浏览器里打开，返回页面截图和文字摘要（标题、按钮、可见文字、横向溢出 / 文字截断等布局问题、控制台报错）。改完界面后用它检查效果；需要看手机效果时 device 填 mobile；多页面应用用 route 指定 hash 路由（如 #/orders）。',
      parameters: Type.Object({
        route: Type.Optional(Type.String({ description: '要打开的页面：hash 路由（如 #/orders）；不填打开首页' })),
        device: Type.Optional(Type.Union([Type.Literal('desktop'), Type.Literal('wide'), Type.Literal('mobile')], { description: 'desktop = 1280×800（默认），wide = 1920×1080（宽屏，检查大屏留白），mobile = 390×844' })),
        fullPage: Type.Optional(Type.Boolean({ description: '截整页（最长 2400px），默认只截首屏' })),
      }),
      async execute(_id, params, _signal, _onUpdate, ctx) {
        const r = await shoot(cwd, { projectKind, route: params?.route, device: params?.device, fullPage: !!params?.fullPage }, env)
        const vision = Array.isArray(ctx?.model?.input) ? ctx.model.input.includes('image') : true
        const content = [{ type: 'text', text: r.text + (r.image && !vision ? '\n（当前模型不能看图，请根据上面的文字摘要判断）' : '') }]
        if (r.image && vision) content.push({ type: 'image', data: r.image, mimeType: r.mimeType })
        return { content, details: { ok: r.ok } }
      },
    })
  }
}
