import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { smokeTest } from '../src/smoke.mjs'
import { systemPrompt } from '../src/prompts.mjs'

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const HAS_CHROME = existsSync(CHROME)

function makeSite(html) {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'smoke-sbox-'))
  writeFileSync(path.join(dir, 'index.html'), html)
  return dir
}

test('systemPrompt 包含沙箱存储安全与云函数调用防呆约束', () => {
  const off = systemPrompt('template', false)
  assert.match(off, /sandbox|沙箱.*iframe/)
  assert.match(off, /try\/catch/)

  const on = systemPrompt('template', true)
  assert.match(on, /window\.zhiye\.fn\.call/)
  assert.match(on, /幂等/)
  assert.match(on, /loginName/)
  assert.match(on, /function_write/)
})

test('smokeTest 在沙箱 iframe 中捕获未加 try/catch 的 localStorage 崩溃', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><body>
<div id="root"></div>
<script>
  // 顶层页面可正常执行，但在 sandbox="allow-scripts allow-forms" iframe 下会抛 SecurityError 白屏
  const v = localStorage.getItem('demo') || 'ok';
  document.getElementById('root').innerHTML = '<main>' + v + '</main>';
</script>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /沙箱预览/)
    assert.match(r.detail, /localStorage|SecurityError/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('smokeTest 对 try/catch 降级存储且路由正常的多页产物放行', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><body>
<div id="root"></div>
<script>
  let saved = 'ok';
  try { saved = localStorage.getItem('demo') || 'ok'; } catch {}
  function render() {
    const h = window.location.hash || '#/orders';
    document.getElementById('root').innerHTML = '<nav><a href="#/orders">工单</a><a href="#/stock">库存</a></nav><main>' + h + ':' + saved + '</main>';
  }
  window.addEventListener('hashchange', render);
  render();
</script>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, true, r.detail)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('smokeTest 能检出子路由切换后 #root 白屏', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><body>
<div id="root"></div>
<script>
  function render() {
    if (window.location.hash === '#/broken') { document.getElementById('root').innerHTML = ''; return; }
    document.getElementById('root').innerHTML = '<nav><a href="#/broken">坏页面</a></nav><main>首页</main>';
  }
  window.addEventListener('hashchange', render);
  render();
</script>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /路由 #\/broken/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
