import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, existsSync, cpSync, symlinkSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { takeScreenshot } from '../src/screenshot.mjs'
import { createScreenshotExtension, toolSummary } from '../src/steps.mjs'
import { checkToolCall } from '../src/guard.mjs'

const CHROME = process.env.ZHIYE_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

test('截图自查：发现横向溢出和文字截断，返回截图和摘要', { skip: !existsSync(CHROME) && '没有 Chrome' }, async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'shot-'))
  writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>点检台账</title></head><body style="margin:0">
    <h1>设备点检</h1><button>新增记录</button><input placeholder="搜索设备">
    <div class="wide-table" style="width:1800px;height:40px;background:#eee">很宽的表格</div>
    <div class="cell" style="width:60px;overflow:hidden;white-space:nowrap">这是一段会被截断的很长很长的文字</div></body></html>`)
  const r = await takeScreenshot(dir, { projectKind: 'static' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
  assert.equal(r.ok, true, r.text)
  assert.ok(r.image && r.image.length > 1000)
  assert.equal(r.mimeType, 'image/jpeg')
  assert.match(r.text, /标题：点检台账/)
  assert.match(r.text, /按钮：新增记录/)
  assert.match(r.text, /横向溢出/)
  assert.match(r.text, /wide-table/)
  assert.match(r.text, /文字被截断.*cell/)
  const w = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
  assert.match(w.text, /宽屏 1920×1080/)
})

test('截图自查：宽屏 1920×1080 检出 max-w-[1440px] mx-auto 两侧留白，满宽流式布局放行', { skip: !existsSync(CHROME) && '没有 Chrome' }, async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'shot-wide-'))
  try {
    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>研发协同台</title></head><body style="margin:0;background:#eef5f1">
      <div style="max-width:1440px;margin:0 auto;display:flex;min-height:100vh">
        <aside style="width:220px;background:#fff;padding:16px">导航栏</aside>
        <main style="flex:1;padding:24px"><h1>交付看板</h1><table><tr><th>项目</th></tr><tr><td>测试</td></tr></table></main>
      </div></body></html>`)
    const bad = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(bad.ok, true, bad.text)
    assert.match(bad.text, /宽屏 1920×1080/)
    assert.match(bad.text, /外层布局未铺满/)

    // Pattern B：左侧边栏贴边，但右侧 <main> 内部套了 max-w-5xl mx-auto（如设备点检记录系统）
    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>设备点检记录系统</title></head><body style="margin:0;background:#f4f3fb">
      <div style="width:100%;display:flex;min-height:100vh">
        <aside style="width:210px;background:#fff;padding:16px">设备列表</aside>
        <main style="flex:1;padding:24px"><div style="max-width:960px;margin:0 auto"><h1>设备列表</h1><table style="width:100%"><tr><th>编号</th></tr><tr><td>SB-001</td></tr></table></div></main>
      </div></body></html>`)
    const badInner = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(badInner.ok, true, badInner.text)
    assert.match(badInner.text, /右侧主工作区未铺满/)

    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>研发协同台</title></head><body style="margin:0;background:#eef5f1">
      <div style="width:100%;display:flex;min-height:100vh">
        <aside style="width:220px;background:#fff;padding:16px">导航栏</aside>
        <main style="flex:1;padding:24px"><h1>交付看板</h1><table style="width:100%"><tr><th>项目</th></tr><tr><td>测试</td></tr></table></main>
      </div></body></html>`)
    const good = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(good.ok, true, good.text)
    assert.doesNotMatch(good.text, /未铺满/)

    // 居中述职报告 / 文章页包含 3 列小对比表（无侧边栏）：不应误报宽屏未铺满
    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>年度述职报告</title></head><body style="margin:0;background:#f8f9fc">
      <article style="max-width:960px;margin:0 auto;padding:32px;background:#fff">
        <h1>2026 年度述职报告</h1>
        <table style="width:100%"><tr><th>项目</th><th>指标</th><th>达成</th></tr><tr><td>良率提升</td><td>99.2%</td><td>达标</td></tr></table>
      </article></body></html>`)
    const report = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(report.ok, true, report.text)
    assert.doesNotMatch(report.text, /未铺满/)

    // 无侧边栏但包含 6 列宽业务台账表被锁窄居中：仍应检出
    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>备件台账</title></head><body style="margin:0;background:#f8f9fc">
      <div style="max-width:1100px;margin:0 auto;padding:24px;background:#fff">
        <h1>备件台账</h1>
        <table style="width:100%"><tr><th>编号</th><th>名称</th><th>型号</th><th>库存</th><th>状态</th><th>操作</th></tr>
        <tr><td>SP-01</td><td>阀门</td><td>V2</td><td>12</td><td>正常</td><td>编辑</td></tr></table>
      </div></body></html>`)
    const narrowLedger = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(narrowLedger.ok, true, narrowLedger.text)
    assert.match(narrowLedger.text, /外层布局未铺满/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('截图自查：支持 service 全栈项目临时起服并截图', { skip: !existsSync(CHROME) && '没有 Chrome' }, async () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'shot-svc-'))
  try {
    writeFileSync(path.join(dir, 'server.mjs'), `
      import http from 'node:http'
      const srv = http.createServer((req, res) => {
        if (req.url === '/api/health') { res.writeHead(200, {'Content-Type':'application/json'}); return res.end('{"ok":true}') }
        res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'})
        res.end('<!doctype html><html><head><title>全栈服务首页</title></head><body><h1>全栈测试页</h1><button>刷新</button></body></html>')
      })
      srv.listen(Number(process.env.PORT || 8080), '127.0.0.1')
    `)
    const r = await takeScreenshot(dir, { projectKind: 'service' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(r.ok, true, r.text)
    assert.ok(r.image && r.image.length > 1000)
    assert.match(r.text, /标题：全栈服务首页/)
    assert.match(r.text, /全栈测试页/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('截图自查：没有浏览器时给出说明', async () => {
  const r = await takeScreenshot('/tmp', { projectKind: 'static' }, { ZHIYE_CHROME_PATH: '/nope' })
  assert.equal(r.ok, false)
  assert.match(r.text, /没有浏览器/)
})

test('screenshot 工具：能看图的模型拿到图片，不能看图的只拿文字；守卫放行、步骤标题', async () => {
  let tool
  const pi = { registerTool: (t) => { tool = t } }
  createScreenshotExtension({ cwd: '/tmp', projectKind: 'template', shoot: async () => ({ ok: true, text: '摘要', image: 'AAAA', mimeType: 'image/jpeg' }) })(pi)
  assert.equal(tool.name, 'screenshot')
  const v = await tool.execute('1', {}, undefined, undefined, { model: { input: ['text', 'image'] } })
  assert.deepEqual(v.content.map((c) => c.type), ['text', 'image'])
  const t = await tool.execute('2', { device: 'wide' }, undefined, undefined, { model: { input: ['text'] } })
  assert.deepEqual(t.content.map((c) => c.type), ['text'])
  assert.match(t.content[0].text, /不能看图/)
  assert.equal(checkToolCall('screenshot', {}, { cwd: '/tmp' }), null)
  assert.equal(toolSummary('/tmp', 'screenshot', { device: 'wide', route: '#/orders' }), '截图自查 · 宽屏 1920 #/orders')
})

test('截图自查：template 项目走内存 Vite Dev Server 免写构建产物', { skip: !existsSync(CHROME) && '没有 Chrome' }, async () => {
  const root = path.resolve(import.meta.dirname, '..', '..')
  const nm = path.join(root, 'runtime', 'template-cache', 'node_modules')
  if (!existsSync(nm)) return
  const dir = mkdtempSync(path.join(os.tmpdir(), 'shot-vite-'))
  try {
    cpSync(path.join(root, 'packages', 'app-template'), dir, { recursive: true })
    symlinkSync(nm, path.join(dir, 'node_modules'))
    const r = await takeScreenshot(dir, { projectKind: 'template' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(r.ok, true, r.text)
    assert.ok(r.image && r.image.length > 1000)
    assert.equal(existsSync(path.join(dir, '.zhiye', 'dist-shot')), false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
