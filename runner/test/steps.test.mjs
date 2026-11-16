import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { validateTodos, sanitizeTodoContent, relPath, sanitizeText, sanitizeInline, snapshotBefore, stepDetail, toolSummary, unifiedDiff, headText, createTodoExtension, createConfirmExtension, ThinkingStream } from '../src/steps.mjs'
import { checkToolCall } from '../src/guard.mjs'

const ws = realpathSync(mkdtempSync(path.join(os.tmpdir(), 'steps-')))
mkdirSync(path.join(ws, 'src'))
const noAbs = (v) => assert.ok(!JSON.stringify(v).includes(ws) && !JSON.stringify(v).includes(ws.replace(/^\/private/, '')), `含绝对路径：${JSON.stringify(v).slice(0, 300)}`)

test('todo 校验：合法清单、清洗内容', () => {
  const r = validateTodos(ws, { items: [{ content: '  搭建页面\n结构 ', status: 'completed' }, { content: `修改 ${ws}/src/App.tsx`, status: 'in_progress' }, { content: '自查类型', status: 'pending' }] })
  assert.deepEqual(r, { items: [{ content: '搭建页面 结构', status: 'completed' }, { content: '修改 ./src/App.tsx', status: 'in_progress' }, { content: '自查类型', status: 'pending' }] })
  assert.equal(sanitizeTodoContent(ws, 'a\u0000b c'), 'a b c')
})

test('todo 校验：数量、长度、状态、多个 in_progress', () => {
  const it = (status = 'pending', content = '步骤') => ({ content, status })
  assert.match(validateTodos(ws, { items: [] }).error, /1～8/)
  assert.match(validateTodos(ws, { items: Array.from({ length: 9 }, () => it()) }).error, /1～8/)
  assert.match(validateTodos(ws, {}).error, /数组/)
  assert.match(validateTodos(ws, { items: [it('pending', '长'.repeat(81))] }).error, /太长/)
  assert.equal(validateTodos(ws, { items: [it('pending', '长'.repeat(80))] }).items[0].content.length, 80)
  assert.match(validateTodos(ws, { items: [it('pending', '  ')] }).error, /content/)
  assert.match(validateTodos(ws, { items: [it('doing')] }).error, /status/)
  assert.match(validateTodos(ws, { items: [it('in_progress'), it('in_progress')] }).error, /一项 in_progress/)
  assert.ok(validateTodos(ws, { items: Array.from({ length: 8 }, () => it('completed')) }).items)
})

test('todo_write 工具：注册、事件、错误时抛出、守卫放行', async () => {
  let tool
  const events = []
  createTodoExtension({ cwd: ws, onTodo: (items) => events.push(items) })({ registerTool: (t) => { tool = t } })
  assert.equal(tool.name, 'todo_write')
  const ok = await tool.execute('1', { items: [{ content: '第一步', status: 'in_progress' }, { content: '第二步', status: 'pending' }] })
  assert.match(ok.content[0].text, /0\/2 完成；进行中：第一步/)
  assert.deepEqual(events, [[{ content: '第一步', status: 'in_progress' }, { content: '第二步', status: 'pending' }]])
  await assert.rejects(tool.execute('2', { items: [] }), /待办清单无效/)
  assert.equal(events.length, 1)
  assert.equal(checkToolCall('todo_write', { items: [] }, { cwd: ws }), null)
})

test('request_confirm 工具：等待用户选择、接受/拒绝/超时/停止', async () => {
  let tool
  const events = []
  const waiters = new Map()
  createConfirmExtension({ emit: (e) => events.push(e), waiters, onAccepted: () => '用法：db.insert', timeoutMs: 80 })({ registerTool: (t) => { tool = t } })
  assert.equal(tool.name, 'request_confirm')
  assert.equal(toolSummary(ws, 'request_confirm', { kind: 'cloud' }), '启用 织页云服务')
  assert.equal(checkToolCall('request_confirm', { kind: 'cloud' }, { cwd: ws }), null)

  const p1 = tool.execute('call-1', { kind: 'cloud', reason: '工单要多人共用' })
  await new Promise((r) => setTimeout(r, 5))
  assert.deepEqual(events[0], { type: 'confirm', id: 'cf_call-1', kind: 'cloud', reason: '工单要多人共用', state: 'pending' })
  waiters.get('cf_call-1')({ accept: true, note: '已建表 notes' })
  const r1 = await p1
  assert.match(r1.content[0].text, /已启用[\s\S]*已建表 notes[\s\S]*用法：db.insert/)
  assert.equal(events[1].state, 'accepted')
  assert.equal(waiters.size, 0)

  const p2 = tool.execute('call-2', { kind: 'cloud', reason: 'x' })
  await new Promise((r) => setTimeout(r, 5))
  waiters.get('cf_call-2')({ accept: false })
  assert.match((await p2).content[0].text, /暂不启用[\s\S]*localStorage/)
  assert.equal(events.at(-1).state, 'declined')

  const r3 = await tool.execute('call-3', { kind: 'cloud', reason: 'x' })
  assert.match(r3.content[0].text, /没有回应/)
  assert.equal(events.at(-1).state, 'timeout')

  const ac = new AbortController()
  const p4 = tool.execute('call-4', { kind: 'cloud', reason: 'x' }, ac.signal)
  ac.abort()
  await assert.rejects(p4, /已停止/)
  assert.equal(waiters.size, 0)
  await assert.rejects(tool.execute('call-5', { kind: 'nope', reason: 'x' }), /不支持/)
})

test('路径脱敏', () => {
  assert.equal(relPath(ws, path.join(ws, 'src/App.tsx')), 'src/App.tsx')
  assert.equal(relPath(ws, 'src/App.tsx'), 'src/App.tsx')
  assert.equal(relPath(ws, ws.replace(/^\/private/, '') + '/a.txt'), 'a.txt')
  assert.equal(relPath(ws, '/etc/passwd'), '（工作区外）/passwd')
  assert.equal(relPath(ws, '~/.ssh/id_rsa'), '（工作区外）/id_rsa')
  assert.equal(sanitizeText(ws, `cd ${ws}/src && ls ${ws}`, { home: '/Users/someone' }), 'cd ./src && ls .')
  assert.equal(sanitizeText(ws, 'see /Users/someone/x and /home/bob/y', { home: '' }), 'see ~/x and ~/y')
  assert.equal(toolSummary(ws, 'bash', { command: `cat ${ws}/src/a.ts /opt/x/y/z.txt` }), '执行命令：cat src/a.ts …/z.txt')
  assert.equal(sanitizeInline(ws, `打开 ${ws}/src/App.tsx 和 /usr/local/lib/x.js`), '打开 src/App.tsx 和 …/x.js')
})

test('write 详情：新建 / 改写、行数、预览截断、计数摘要', () => {
  const args = { path: path.join(ws, 'src/New.tsx'), content: `// ${ws}\n` + Array.from({ length: 300 }, (_, i) => `line ${i}`).join('\n') + '\n' }
  const snap = snapshotBefore(ws, 'write', args)
  writeFileSync(path.join(ws, 'src/New.tsx'), args.content)
  const { detail, counts } = stepDetail(ws, 'write', args, snap, { content: [{ type: 'text', text: 'ok' }] }, false)
  assert.equal(detail.kind, 'write')
  assert.equal(detail.path, 'src/New.tsx')
  assert.equal(detail.created, true)
  assert.equal(detail.lines, 301)
  assert.equal(detail.preview.split('\n').length, 200)
  assert.equal(detail.truncated, true)
  assert.match(detail.preview, /^\/\/ \.\n/)
  noAbs(detail)
  assert.equal(toolSummary(ws, 'write', args, { created: true }) + counts, '创建 src/New.tsx +301 行')
  const snap2 = snapshotBefore(ws, 'write', { path: 'src/New.tsx' })
  assert.equal(stepDetail(ws, 'write', { path: 'src/New.tsx', content: 'x' }, snap2, null, false).detail.created, false)
  const big = headText('x'.repeat(20000))
  assert.ok(Buffer.byteLength(big.text) <= 12 * 1024 && big.truncated)
})

test('edit 详情：统一 diff、增删行数', () => {
  const file = path.join(ws, 'src/App.tsx')
  writeFileSync(file, 'a\nb\nc\nd\ne\nf\ng\n')
  const args = { path: 'src/App.tsx', edits: [{ oldText: 'c\nd', newText: 'C\nD\nD2' }] }
  const snap = snapshotBefore(ws, 'edit', args)
  writeFileSync(file, 'a\nb\nC\nD\nD2\ne\nf\ng\n')
  const { detail, counts } = stepDetail(ws, 'edit', args, snap, { content: [{ type: 'text', text: 'ok' }] }, false)
  assert.deepEqual({ kind: detail.kind, path: detail.path, added: detail.added, removed: detail.removed }, { kind: 'edit', path: 'src/App.tsx', added: 3, removed: 2 })
  assert.equal(detail.diff, '--- a/src/App.tsx\n+++ b/src/App.tsx\n@@ -1,7 +1,8 @@\n a\n b\n-c\n-d\n+C\n+D\n+D2\n e\n f\n g')
  assert.equal(toolSummary(ws, 'edit', args) + counts, '修改 src/App.tsx +3 −2')
  const long = unifiedDiff('x', '', Array.from({ length: 500 }, (_, i) => `l${i}`).join('\n'))
  assert.ok(long.diff.split('\n').length <= 201 && long.truncated && long.added === 500)
  const failed = stepDetail(ws, 'edit', args, snap, { content: [{ type: 'text', text: `Could not find text in ${file}` }] }, true)
  assert.equal(failed.detail.added, 0)
  assert.match(failed.detail.error, /\.\/src\/App\.tsx/)
  noAbs(failed.detail)
})

test('read 详情', () => {
  const { detail, counts } = stepDetail(ws, 'read', { path: path.join(ws, 'src/App.tsx') }, null, { content: [{ type: 'text', text: 'a\nb\nc\n' }] }, false)
  assert.deepEqual(detail, { kind: 'read', path: 'src/App.tsx', lines: 3 })
  assert.equal(counts, ' 3 行')
})

test('bash 详情：退出码、输出取末尾 4 KB、路径替换、被拦截', () => {
  const out = Array.from({ length: 400 }, (_, i) => `${ws}/src/f${i}.ts(1,1): error`).join('\n')
  const { detail } = stepDetail(ws, 'bash', { command: `npx tsc --noEmit -p ${ws}/tsconfig.json` }, null, { content: [{ type: 'text', text: 'x' }], structuredContent: { output: out, exit_code: 2 } }, true)
  assert.equal(detail.kind, 'bash')
  assert.equal(detail.exitCode, 2)
  assert.equal(detail.command, 'npx tsc --noEmit -p ./tsconfig.json')
  assert.ok(Buffer.byteLength(detail.output) <= 4096)
  assert.match(detail.output, /\.\/src\/f399\.ts\(1,1\): error$/)
  assert.equal(detail.truncated, true)
  noAbs(detail)
  const ok = stepDetail(ws, 'bash', { command: 'ls' }, null, { content: [{ type: 'text', text: 'a\nb' }] }, false).detail
  assert.deepEqual(ok, { kind: 'bash', command: 'ls', exitCode: 0, output: 'a\nb' })
  const viaText = stepDetail(ws, 'bash', { command: 'false' }, null, { content: [{ type: 'text', text: 'boom\n\nCommand exited with code 1' }] }, true).detail
  assert.equal(viaText.exitCode, 1)
  const blocked = stepDetail(ws, 'bash', { command: 'curl x' }, null, { content: [{ type: 'text', text: '安全守卫拦截：不允许联网' }] }, true).detail
  assert.equal(blocked.blocked, true)
  assert.equal(blocked.exitCode, null)
})

test('thinking：合并节流、脱敏、结束事件、20 KB 上限', () => {
  let now = 0
  const timers = []
  const events = []
  const t = new ThinkingStream({ cwd: ws, emit: (e) => events.push(e), now: () => now, setTimer: (fn, ms) => { timers.push({ fn, at: now + ms }); return timers.length }, clearTimer: () => {} })
  const tick = (ms) => { now += ms; const due = timers.filter((x) => x.at <= now); for (const x of due) timers.splice(timers.indexOf(x), 1); for (const x of due) x.fn() }
  t.start()
  t.delta('先看看 ')
  t.delta(`${ws}/src/Ap`) // 路径还没写完，先留着
  tick(0)
  assert.deepEqual(events, [{ type: 'thinking', text: '先看看 ' }])
  t.delta('p.tsx 的结构。')
  tick(100)
  assert.equal(events.length, 1, '300ms 内不再发')
  tick(250)
  assert.deepEqual(events[1], { type: 'thinking', text: 'src/App.tsx 的结构。' })
  now += 500
  t.end()
  assert.deepEqual(events[2], { type: 'thinking_end', durationMs: 850 })
  // 上限：超过后只发 thinking_end
  const ev2 = []
  const t2 = new ThinkingStream({ cwd: ws, emit: (e) => ev2.push(e), limit: 100, setTimer: () => 1, clearTimer: () => {} })
  t2.delta('字'.repeat(60)); t2.flush(true)
  t2.delta('后面的不再发送'); t2.end()
  const texts = ev2.filter((e) => e.type === 'thinking').map((e) => e.text).join('')
  assert.ok(Buffer.byteLength(texts) <= 100)
  assert.ok(!texts.includes('后面'))
  assert.equal(ev2.at(-1).type, 'thinking_end')
  t2.start(); t2.delta('还有'); t2.end()
  assert.equal(ev2.at(-1).type, 'thinking_end')
  assert.equal(ev2.filter((e) => e.type === 'thinking').length, 1)
})

test('读取 agent 目录里的 SKILL.md 显示为「加载技能」，项目里的同名文件仍是读取', () => {
  assert.equal(toolSummary(ws, 'read', { path: '/tmp/agents/t1/skills/charts-dashboard/SKILL.md' }), '加载技能 · charts-dashboard')
  assert.equal(toolSummary(ws, 'read', { path: 'skills/demo/SKILL.md' }), '读取 skills/demo/SKILL.md')
})
