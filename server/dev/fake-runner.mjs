#!/usr/bin/env node
// 织页「假 runner」：按 docs/runner-protocol.md 实现（HTTP/SSE 控制通道），结果是确定的，不调用 Pi。
// 用于 Java 端到端测试与本地演示（配置 zhiye.runner.script=server/dev/fake-runner.mjs）。
// - prompt：写一个计数器应用（或按指令做简单修改），并通过网关发一次很小的模型请求（验证令牌、故障注入、用量统计）
//   指令里含 [slow] 时每轮耗时 20 秒（可被 abort 打断），用于测试停止/继续
// - verify：把 src/App.tsx 的 JSX 逐行「渲染」成静态 HTML，写入 .zhiye/dist-preview（带 data-loc）和 .zhiye/dist-publish
// - visual：按 loc 改写源码中对应行的文字和样式
// - import：安全解包 zip / html 并识别项目类型
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { ControlServer } from '../../runner/src/control-server.mjs'

const cwd = process.cwd()
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let aborted = false
const steers = []
const confirmWaiters = new Map()
let promptRunning = false
let sessionFile = null
let turn = 0
let projectKind = 'template'

const token = process.env.ZHIYE_RUNNER_TOKEN
if (!token) {
  process.stderr.write('[fake-runner] 缺少 ZHIYE_RUNNER_TOKEN，拒绝启动\n')
  process.exit(2)
}

let server
const event = (e) => server.publish({ type: 'event', event: e })
const log = (...a) => process.stderr.write('[fake-runner] ' + a.join(' ') + '\n')

const handlers = {
  init: async (cmd) => {
    projectKind = cmd.projectKind || 'template'
    sessionFile = cmd.sessionFile || path.join(cmd.agentDir, 'sessions', 'fake-' + Date.now() + '.jsonl')
    fs.mkdirSync(path.dirname(sessionFile), { recursive: true })
    fs.appendFileSync(sessionFile, JSON.stringify({ type: 'init', thinking: cmd.thinking, projectKind: cmd.projectKind }) + '\n')
    return { sessionFile }
  },
  prompt: (cmd) => prompt(cmd.text || ''),
  verify: (cmd) => slowIfMarked().then(() => verify(cmd.projectKind || 'template')),
  visual: (cmd) => visual(cmd.patches || []),
  import: (cmd) => doImport(cmd),
  install: () => ({ ok: true }),
  shutdown: () => ({}),
  // 可以在 prompt 执行期间插入
  steer: (cmd) => { steers.push(String(cmd.text || '')); return {} },
  confirm: (cmd) => {
    const w = confirmWaiters.get(cmd.confirmId)
    if (!w) throw fail('BAD_STATE', '没有等待中的确认：' + cmd.confirmId)
    w(cmd)
    return {}
  },
  abort: () => { aborted = true; return {} },
}

server = new ControlServer({
  token,
  host: process.env.ZHIYE_RUNNER_HOST || '127.0.0.1',
  port: Number(process.env.ZHIYE_RUNNER_PORT || 0),
  handlers,
  bypass: ['steer', 'abort', 'confirm'],
  onShutdown: () => process.exit(0),
})

function fail(code, message) { const e = new Error(message); e.code = code; return e }

// ---------------- prompt ----------------
async function callGateway() {
  const url = process.env.ZHIYE_GATEWAY_URL
  if (!url) return
  const res = await fetch(url.replace(/\/+$/, '') + '/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + process.env.ZHIYE_TASK_TOKEN },
    body: JSON.stringify({ model: 'kimi-for-coding', max_tokens: 16, messages: [{ role: 'user', content: '只回复：好' }] }),
  }).catch((e) => ({ ok: false, status: 0, text: async () => String(e) }))
  if (!res.ok) {
    const body = await res.text()
    // 与 runner/src/agent.mjs classifyError 一致：429 且错误体是额度类 → PROVIDER_QUOTA，带上错误体（里面有重置时间）
    if (res.status === 429 && /quota|rate.?limit/i.test(body)) throw fail('PROVIDER_QUOTA', '模型调用失败（HTTP 429）：' + body.slice(0, 300))
    throw fail('MODEL_ERROR', '模型调用失败（HTTP ' + res.status + '）')
  }
}

async function prompt(text) {
  aborted = false
  promptRunning = true
  try {
    turn++
    event({ type: 'turn', n: turn })
    await callGateway()
    if (sessionFile) fs.appendFileSync(sessionFile, JSON.stringify({ type: 'prompt', text: text.slice(0, 200) }) + '\n')
    for (const part of ['先看看', '项目结构，', '再动手。']) { event({ type: 'thinking', text: part }); await sleep(30) }
    event({ type: 'thinking_end', durationMs: 1200 })
    event({ type: 'todo', items: [{ content: '编写页面', status: 'in_progress' }, { content: '自检', status: 'pending' }] })
    event({ type: 'tool', id: 't' + turn + 'a', name: 'read', summary: '读取 src/App.tsx', state: 'start' })
    const big = text.includes('[bigdetail]') ? 'x'.repeat(20000) : 'export default function App() {}'
    event({ type: 'tool', id: 't' + turn + 'a', name: 'read', summary: '读取 src/App.tsx', state: 'end', ok: true,
      detail: { kind: 'read', path: 'src/App.tsx', content: big } })
    if (text.includes('[many]')) for (let i = 0; i < 210; i++) event({ type: 'log', line: '日志 ' + i })
    const slow = text.includes('[slow]') && !text.startsWith('上次的工作被中断了') && !text.startsWith('上一轮的任务被用户中途停止了')
    const total = slow ? 20000 : 200
    let seen = 0, end = total
    for (let t = 0; t < end; t += 100) {
      if (aborted) return { text: '', turns: turn, aborted: true, error: null }
      while (seen < steers.length) { event({ type: 'agent_text', delta: `收到补充：${steers[seen++]}。` }); end = Math.min(end, t + 500) }
      await sleep(100)
    }
    // 测试用：像真 agent 一样做到一半撞上步数上限
    if (text.includes('[turnlimit]') && !text.startsWith('上次的工作被中断了')) return { text: '', turns: 61, aborted: false, error: { code: 'TURN_LIMIT', message: '超过最大轮数 60，已停止' } }
    if (text.includes('[confirm]')) { // 测试用：像真 agent 一样调用 request_confirm 并等用户选择
      const id = 'cf_t' + turn
      event({ type: 'tool', id: 'tc' + turn, name: 'request_confirm', summary: '启用 织页云服务', state: 'start' })
      event({ type: 'confirm', id, kind: 'cloud', reason: '工单数据要多人共用', state: 'pending' })
      const answer = await new Promise((resolve) => {
        const timer = setInterval(() => { if (aborted) { clearInterval(timer); confirmWaiters.delete(id); resolve(null) } }, 50)
        confirmWaiters.set(id, (cmd) => { clearInterval(timer); confirmWaiters.delete(id); resolve(cmd) })
      })
      const state = answer === null ? 'declined' : answer.accept ? 'accepted' : 'declined'
      event({ type: 'confirm', id, kind: 'cloud', reason: '工单数据要多人共用', state })
      event({ type: 'tool', id: 'tc' + turn, name: 'request_confirm', summary: '启用 织页云服务', state: 'end', ok: true })
      if (answer === null) return { text: '', turns: turn, aborted: true, error: null }
      event({ type: 'agent_text', delta: state === 'accepted' ? `云服务已启用（${answer.note || ''}）。` : '用户暂不启用云服务，改用 localStorage。' })
    }
    event({ type: 'todo', items: [{ content: '编写页面', status: 'completed' }, { content: '自检', status: 'completed' }] })
    // 引用的技能：确认 SKILL.md 确实放在了 agent 目录里（测试用）
    for (const m of text.matchAll(/([^\s（）；：]+)（说明见 ([^）]+\/SKILL\.md)）/g)) {
      if (fs.existsSync(m[2])) event({ type: 'agent_text', delta: `已读取技能 ${m[1]}（${fs.readFileSync(m[2], 'utf8').length} 字）。` })
    }
    for (const m of text.matchAll(/（调用名 `([a-z0-9_]+)`）/g)) event({ type: 'agent_text', delta: `已知数据源 ${m[1]}。` })
    for (const m of text.matchAll(/^- (public\/assets\/\S+|references\/assets\/\S+)/gm)) {
      if (fs.existsSync(path.join(cwd, m[1].replace(/（.*$/, '')))) event({ type: 'agent_text', delta: `素材已就绪 ${m[1].replace(/（.*$/, '')}。` })
    }
    { // 测试用：回显收到的元素上下文
      const loc = text.match(/源码位置：(\S+) 第 (\d+) 行第 (\d+) 列/)
      if (loc) event({ type: 'agent_text', delta: `收到元素位置 ${loc[1]}:${loc[2]}:${loc[3]}。` })
      if (text.includes('行号可能因手动修改有偏移，请以元素内容为准')) event({ type: 'agent_text', delta: '已知行号可能偏移。' })
      if (text.includes('已经被用户的手动修改删除')) event({ type: 'agent_text', delta: '元素已被删除。' })
    }
    event({ type: 'agent_text', delta: '正在' })
    event({ type: 'agent_text', delta: '编写页面。' })
    if (projectKind === 'skill') {
      const md = path.join(cwd, 'SKILL.md')
      if (text.includes('请按用户的要求修改')) fs.appendFileSync(md, '\n## 补充\n已按要求修改。\n')
      else writeSkill()
      event({ type: 'tool', id: 't' + turn + 'b', name: 'write', summary: '修改 SKILL.md', state: 'end', ok: true })
      return { text: '已编写周报生成 skill：SKILL.md、references/format.md、scripts/weekly.py。', turns: turn, aborted: false, error: null }
    }
    const isStatic = fs.existsSync(path.join(cwd, 'index.html')) && !fs.existsSync(path.join(cwd, 'src/App.tsx'))
    let summary
    if (text.includes('请按用户的要求修改')) summary = modify(text, isStatic)
    else if (text.startsWith('上次的工作被中断了')) { writeCounter(); summary = '已继续完成计数器应用。' }
    else { writeCounter(); summary = '已生成点击计数器：显示次数，带「加一」和「重置」按钮。' }
    event({ type: 'tool', id: 't' + turn + 'b', name: 'write', summary: '修改 src/App.tsx', state: 'end', ok: true })
    return { text: summary, turns: turn, aborted: false, error: null }
  } finally {
    promptRunning = false
  }
}

const APP = `import { useState } from 'react'

export default function App() {
  const [n, setN] = useState(0)
  return (
    <main className="min-h-screen p-8 text-center">
      <h1 className="text-3xl font-bold">点击计数器</h1>
      <p className="text-gray-500">点一下按钮，数字就加一。</p>
      <div className="text-6xl" id="count">{n}</div>
      <button className="rounded-xl px-4 py-2" onClick={() => setN(n + 1)}>加一</button>
      <button className="rounded-xl px-4 py-2" disabled={n === 0} onClick={() => setN(0)}>重置</button>
    </main>
  )
}
`

function writeSkill() {
  fs.writeFileSync(path.join(cwd, 'SKILL.md'), '---\nname: weekly-report\ndescription: 根据本周完成事项、问题和下周计划生成周报。需要写周报时使用。\n---\n\n# 周报生成\n\n按 [格式](references/format.md) 输出，可运行 `python3 scripts/weekly.py`。\n')
  fs.mkdirSync(path.join(cwd, 'references'), { recursive: true })
  fs.mkdirSync(path.join(cwd, 'scripts'), { recursive: true })
  fs.writeFileSync(path.join(cwd, 'references/format.md'), '# 周报格式\n')
  fs.writeFileSync(path.join(cwd, 'scripts/weekly.py'), '# 用法：python3 weekly.py\nprint("周报")\n')
}

function writeCounter() {
  fs.mkdirSync(path.join(cwd, 'src'), { recursive: true })
  fs.writeFileSync(path.join(cwd, 'src/App.tsx'), APP)
}

function modify(text, isStatic) {
  const file = isStatic ? 'index.html' : 'src/App.tsx'
  const p = path.join(cwd, file)
  if (!fs.existsSync(p)) return '没有找到要修改的文件。'
  const lines = fs.readFileSync(p, 'utf8').split('\n')
  const m = text.match(/源码位置：(\S+) 第 (\d+) 行/)
  const title = text.match(/标题改成[“"]([^”"]+)[”"]/)
  if (/蓝色/.test(text) && m) {
    const i = Number(m[2]) - 1
    lines[i] = isStatic ? addHtmlStyle(lines[i], { color: '#2563eb' }) : addJsxStyle(lines[i], { color: '#2563eb' })
  } else if (title) {
    const i = lines.findIndex((l) => /<h1[\s>]/.test(l))
    if (i >= 0) lines[i] = lines[i].replace(/(<h1[^>]*>)[^<]*(<\/h1>)/, `$1${title[1]}$2`)
  } else {
    const i = lines.findIndex((l) => /<p[\s>]/.test(l))
    if (i >= 0) lines[i] = lines[i].replace(/(<p[^>]*>)[^<]*(<\/p>)/, '$1' + '已按要求修改。' + '$2')
  }
  fs.writeFileSync(p, lines.join('\n'))
  return '已修改 ' + file + '。'
}

const kebab = (k) => k.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())

function addJsxStyle(line, styles) {
  const entries = Object.entries(styles).map(([k, v]) => `${k}: '${v}'`).join(', ')
  if (/style=\{\{([^}]*)\}\}/.test(line)) {
    return line.replace(/style=\{\{([^}]*)\}\}/, (_, inner) => {
      const map = {}
      for (const part of inner.split(',')) { const [k, v] = part.split(':').map((s) => s && s.trim()); if (k) map[k] = v }
      for (const [k, v] of Object.entries(styles)) map[k] = `'${v}'`
      return 'style={{ ' + Object.entries(map).map(([k, v]) => `${k}: ${v}`).join(', ') + ' }}'
    })
  }
  return line.replace(/<(\w+)/, `<$1 style={{ ${entries} }}`)
}

function addHtmlStyle(line, styles, col = 0) {
  const css = Object.entries(styles).map(([k, v]) => `${kebab(k)}:${v}`).join(';')
  const before = line.slice(0, col), rest = line.slice(col)
  if (/^<\w+[^>]*style="/.test(rest)) return before + rest.replace(/style="([^"]*)"/, (_, s) => `style="${s ? s.replace(/;?$/, ';') : ''}${css}"`)
  return before + rest.replace(/^<(\w+)/, `<$1 style="${css}"`)
}

// ---------------- verify ----------------
function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }) }

function copyDir(src, dst, skip = new Set(['.git', '.zhiye', 'node_modules'])) {
  fs.mkdirSync(dst, { recursive: true })
  for (const name of fs.readdirSync(src)) {
    if (skip.has(name)) continue
    const s = path.join(src, name), d = path.join(dst, name)
    const st = fs.lstatSync(s)
    if (st.isDirectory()) copyDir(s, d, new Set(['node_modules', '.git']))
    else if (st.isFile()) fs.copyFileSync(s, d)
  }
}

function jsxToHtml(src, withLoc) {
  const lines = src.split('\n')
  const body = []
  lines.forEach((line, i) => {
    const m = line.match(/^(\s*)<(\w+)([^>]*)>(.*?)<\/\2>\s*$/) || line.match(/^(\s*)<(main|div|section|header)([^>]*)>\s*$/)
    const close = line.match(/^\s*<\/(main|div|section|header)>\s*$/)
    if (close) { body.push(`</${close[1]}>`); return }
    if (!m) return
    const [, indent, tag, attrsRaw, inner] = m
    const attrs = []
    const cls = attrsRaw.match(/className="([^"]*)"/)
    if (cls) attrs.push(`class="${cls[1]}"`)
    const id = attrsRaw.match(/\bid="([^"]*)"/)
    if (id) attrs.push(`id="${id[1]}"`)
    const st = attrsRaw.match(/style=\{\{([^}]*)\}\}/)
    if (st) {
      const css = st[1].split(',').map((p) => p.split(':').map((s) => s.trim())).filter(([k]) => k)
        .map(([k, v]) => `${kebab(k)}:${v.replace(/^'|'$/g, '')}`).join(';')
      attrs.push(`style="${css}"`)
    }
    if (/disabled=/.test(attrsRaw)) attrs.push('data-reset="1" disabled')
    if (/setN\(n \+ 1\)/.test(attrsRaw)) attrs.push('data-plus="1"')
    if (withLoc) attrs.push(`data-loc="src/App.tsx:${i + 1}:${indent.length}"`)
    const text = inner === undefined ? null : inner.replace('{n}', '0')
    body.push(`<${tag}${attrs.length ? ' ' + attrs.join(' ') : ''}>` + (text ?? ''))
    if (text !== null && inner !== undefined) body.push(`</${tag}>`)
  })
  const script = `<script>(function(){var n=0,c=document.getElementById('count');var p=document.querySelector('[data-plus]'),r=document.querySelector('[data-reset]');function u(){if(c)c.textContent=String(n);if(r)r.disabled=n===0}if(p)p.addEventListener('click',function(){n++;u()});if(r)r.addEventListener('click',function(){n=0;u()});u()})()</script>`
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>织页应用</title><link rel="stylesheet" href="./assets/app.css"></head><body><div id="root">${body.join('')}</div>${script}<script type="module" src="./assets/app.js"></script></body></html>`
}

function annotateHtml(src, file) {
  return src.split('\n').map((line, i) =>
    line.replace(/<(h1|h2|h3|p|button|a|li|small|span|section|main|header|footer|label)(?=[\s>])/g, (m, tag, col) => `<${tag} data-loc="${file}:${i + 1}:${col}"`)
  ).join('\n')
}

function verify(kind) {
  const prev = path.join(cwd, '.zhiye/dist-preview'), pub = path.join(cwd, '.zhiye/dist-publish')
  rmrf(prev); rmrf(pub)
  if (kind === 'skill') {
    const ok = fs.existsSync(path.join(cwd, 'SKILL.md')) && /^---[\s\S]*name:[\s\S]*description:[\s\S]*---/.test(fs.readFileSync(path.join(cwd, 'SKILL.md'), 'utf8'))
    return { ok, checks: [{ name: 'SKILL.md 检查', ok, detail: ok ? '' : 'SKILL.md 缺少 name 或 description' }], previewDir: null, publishDir: null, editable: false }
  }
  if (kind === 'template') {
    const app = path.join(cwd, 'src/App.tsx')
    if (!fs.existsSync(app)) return { ok: false, checks: [{ name: '类型检查', ok: false, detail: '找不到 src/App.tsx' }], previewDir: '.zhiye/dist-preview', publishDir: '.zhiye/dist-publish', editable: true }
    const src = fs.readFileSync(app, 'utf8')
    // 模拟打包：src/ 里用到 zhiye.ai 的代码会进入 assets/app.js（平台据此判断是否开放应用内 AI）
    const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]))
    const usesAi = walk(path.join(cwd, 'src')).some((f) => /\.(t|j)sx?$/.test(f) && fs.readFileSync(f, 'utf8').includes('zhiye.ai'))
    for (const [dir, withLoc] of [[prev, true], [pub, false]]) {
      fs.mkdirSync(path.join(dir, 'assets'), { recursive: true })
      fs.writeFileSync(path.join(dir, 'index.html'), jsxToHtml(src, withLoc))
      fs.writeFileSync(path.join(dir, 'assets/app.css'), 'body{font-family:system-ui;margin:0}main{padding:32px}')
      fs.writeFileSync(path.join(dir, 'assets/app.js'), 'console.log("织页")' + (usesAi ? ';window.__ai=()=>window.zhiye.ai.chat("x")' : ''))
    }
  } else {
    if (!fs.existsSync(path.join(cwd, 'index.html'))) return { ok: false, checks: [{ name: '入口检查', ok: false, detail: '缺少 index.html' }] }
    copyDir(cwd, pub)
    copyDir(cwd, prev)
    if (kind === 'static') {
      for (const f of fs.readdirSync(prev).filter((f) => /\.html?$/i.test(f))) {
        const p = path.join(prev, f)
        fs.writeFileSync(p, annotateHtml(fs.readFileSync(p, 'utf8'), f))
      }
    }
  }
  const names = kind === 'template' ? ['类型检查', '构建', '冒烟测试'] : kind === 'static' ? ['入口检查', '构建', '冒烟测试'] : ['构建', '冒烟测试']
  return { ok: true, checks: names.map((name) => ({ name, ok: true, detail: '' })), previewDir: '.zhiye/dist-preview', publishDir: '.zhiye/dist-publish', editable: kind !== 'vite' }
}

// 测试用：工作区文件里含 [slow] 时校验耗时 3 秒（便于观察进行中的任务）
async function slowIfMarked() {
  for (const f of ['src/App.tsx', 'index.html', 'SKILL.md']) {
    const p = path.join(cwd, f)
    if (fs.existsSync(p) && fs.readFileSync(p, 'utf8').includes('[slow]')) { await sleep(3000); return }
  }
}

// ---------------- visual ----------------
function visual(patches) {
  let applied = 0
  const failures = []
  for (const p of patches) {
    if (p.match === '__reject__') { failures.push({ loc: p.loc, reason: `列表第 ${p.index} 项是动态生成的，不能直接修改` }); continue }
    const m = String(p.loc).match(/^(.+):(\d+):(\d+)$/)
    if (!m) { failures.push({ loc: p.loc, reason: '元素位置格式不正确' }); continue }
    const file = path.join(cwd, m[1])
    if (!fs.existsSync(file) || !file.startsWith(cwd)) { failures.push({ loc: p.loc, reason: '元素已变化，请刷新后重试' }); continue }
    const lines = fs.readFileSync(file, 'utf8').split('\n')
    const i = Number(m[2]) - 1, col = Number(m[3])
    let line = lines[i]
    if (line === undefined || line[col] !== '<') { failures.push({ loc: p.loc, reason: '元素已变化，请刷新后重试' }); continue }
    const isJsx = /\.[jt]sx$/.test(file)
    if (p.text !== undefined) {
      const head = line.slice(0, col), rest = line.slice(col)
      const mm = rest.match(/^(<(\w+)[^>]*>)([^<]*)(<\/\2>)/)
      if (!mm || /\{/.test(mm[3])) { failures.push({ loc: p.loc, reason: '这段文字是动态生成的，不能直接修改' }); continue }
      const safe = isJsx ? String(p.text).replace(/[{}<>]/g, (c) => `{'${c}'}`) : String(p.text).replace(/</g, '&lt;').replace(/>/g, '&gt;')
      line = head + rest.replace(mm[0], mm[1] + safe + mm[4])
    }
    if (p.styles && Object.keys(p.styles).length) {
      line = isJsx ? line.slice(0, col) + addJsxStyle(line.slice(col), p.styles) : addHtmlStyle(line, p.styles, col)
    }
    if (p.removed) line = line.slice(0, col)
    lines[i] = line
    fs.writeFileSync(file, lines.join('\n'))
    applied++
  }
  // 测试用：match 为 __shift__ 的补丁让该元素「下移一行」，用 locMap 告诉 Java
  const locMap = {}
  for (const p of patches) {
    const m = String(p.loc).match(/^(.+):(\d+):(\d+)$/)
    if (p.match === '__shift__' && m) locMap[p.loc] = `${m[1]}:${Number(m[2]) + 1}:${m[3]}`
  }
  return { applied, failures, locMap }
}

// ---------------- import ----------------
function readZip(buf) {
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  if (eocd < 0) throw fail('IMPORT_INVALID', '不是有效的 ZIP 文件')
  const count = buf.readUInt16LE(eocd + 10)
  let p = buf.readUInt32LE(eocd + 16)
  const files = []
  for (let k = 0; k < count; k++) {
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20)
    const nl = buf.readUInt16LE(p + 28), el = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32)
    const ext = buf.readUInt32LE(p + 38), local = buf.readUInt32LE(p + 42)
    const name = buf.slice(p + 46, p + 46 + nl).toString('utf8')
    p += 46 + nl + el + cl
    if (((ext >>> 16) & 0o170000) === 0o120000) throw fail('IMPORT_INVALID', '压缩包包含符号链接')
    if (name.endsWith('/')) continue
    const lnl = buf.readUInt16LE(local + 26), lel = buf.readUInt16LE(local + 28)
    const data = buf.slice(local + 30 + lnl + lel, local + 30 + lnl + lel + csize)
    files.push({ name, data: method === 0 ? data : zlib.inflateRawSync(data) })
  }
  return files
}

function doImport(cmd) {
  let files
  if (cmd.format === 'html') files = [{ name: 'index.html', data: fs.readFileSync(cmd.archivePath) }]
  else files = readZip(fs.readFileSync(cmd.archivePath))
  files = files.filter((f) => !/(^|\/)(node_modules|\.git|__MACOSX)\//.test(f.name) && !/(^|\/)\.DS_Store$/.test(f.name))
  for (const f of files) if (f.name.startsWith('/') || f.name.split('/').includes('..')) throw fail('IMPORT_INVALID', '压缩包包含不安全的路径')
  const tops = new Set(files.map((f) => (f.name.includes('/') ? f.name.split('/')[0] : '')))
  if (tops.size === 1 && !tops.has('')) { const t = [...tops][0] + '/'; files = files.map((f) => ({ ...f, name: f.name.slice(t.length) })) }
  if (cmd.rootDir) { const t = cmd.rootDir + '/'; files = files.filter((f) => f.name.startsWith(t)).map((f) => ({ ...f, name: f.name.slice(t.length) })) }
  let total = 0
  for (const f of files) {
    const dst = path.join(cwd, f.name)
    fs.mkdirSync(path.dirname(dst), { recursive: true })
    fs.writeFileSync(dst, f.data)
    total += f.data.length
  }
  const has = (n) => files.some((f) => f.name === n)
  let framework = '待识别', deployable = false, reason = '没有找到 index.html', kind = null
  if (has('package.json')) {
    const pj = JSON.parse(files.find((f) => f.name === 'package.json').data.toString('utf8'))
    const deps = { ...pj.dependencies, ...pj.devDependencies }
    if (deps.next) { framework = 'Next.js'; reason = '暂不支持部署 Next.js 项目：需要服务端运行环境' }
    else if (deps.nuxt) { framework = 'Nuxt.js'; reason = '暂不支持部署 Nuxt.js 项目：需要服务端运行环境' }
    else if (deps.vite && deps.react) { framework = 'Vite + React'; deployable = true; kind = 'vite' }
    else if (deps.vite && deps.vue) { framework = 'Vite + Vue'; deployable = true; kind = 'vite' }
  } else if (has('index.html')) {
    framework = files.filter((f) => /\.html?$/i.test(f.name)).length === 1 ? 'HTML' : '静态站点'
    deployable = true; kind = 'static'
  }
  return { framework, deployable, unsupportedReason: deployable ? null : reason, projectKind: kind, fileCount: files.length, totalSize: total }
}

const port = await server.listen()
process.stderr.write(`ZHIYE_RUNNER_READY port=${port}\n`)
log('started in', cwd)
