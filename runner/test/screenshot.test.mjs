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
  const m = await takeScreenshot(dir, { projectKind: 'static', device: 'mobile' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
  assert.match(m.text, /手机 390×844/)
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
    assert.match(bad.text, /宽屏下内容区未铺满/)

    writeFileSync(path.join(dir, 'index.html'), `<!doctype html><html><head><title>研发协同台</title></head><body style="margin:0;background:#eef5f1">
      <div style="width:100%;display:flex;min-height:100vh">
        <aside style="width:220px;background:#fff;padding:16px">导航栏</aside>
        <main style="flex:1;padding:24px"><h1>交付看板</h1><table><tr><th>项目</th></tr><tr><td>测试</td></tr></table></main>
      </div></body></html>`)
    const good = await takeScreenshot(dir, { projectKind: 'static', device: 'wide' }, { ...process.env, ZHIYE_CHROME_PATH: CHROME })
    assert.equal(good.ok, true, good.text)
    assert.doesNotMatch(good.text, /宽屏下内容区未铺满/)
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
  const t = await tool.execute('2', { device: 'mobile' }, undefined, undefined, { model: { input: ['text'] } })
  assert.deepEqual(t.content.map((c) => c.type), ['text'])
  assert.match(t.content[0].text, /不能看图/)
  assert.equal(checkToolCall('screenshot', {}, { cwd: '/tmp' }), null)
  assert.equal(toolSummary('/tmp', 'screenshot', { device: 'mobile', route: '#/orders' }), '截图自查 · 手机 #/orders')
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
