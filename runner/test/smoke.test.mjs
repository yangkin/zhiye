import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { smokeTest, runLiveFlow, startStaticServer } from '../src/smoke.mjs'
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

test('smokeTest 能检出移动端 390px 视口下的横向溢出', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0">
<div id="root"><div style="width:900px;height:80px">宽表格撑爆移动端视口</div></div>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /移动端.*横向溢出/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('smokeTest 安全交互探活能检出点击 Tab 后的运行时异常', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><body>
<div id="root">
  <nav>
    <button role="tab" onclick="document.getElementById('panel').textContent='概览'">概览</button>
    <button role="tab" onclick="throw new Error('tab boom')">明细</button>
  </nav>
  <main id="panel">概览</main>
</div>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /tab boom|页面脚本错误/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('smokeTest 对响应式正常且 Tab 切换无异常的应用放行', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0">
<div id="root">
  <nav>
    <button role="tab" onclick="document.getElementById('panel').textContent='概览'">概览</button>
    <button role="tab" onclick="document.getElementById('panel').textContent='设置'">设置</button>
  </nav>
  <input type="search" placeholder="搜索设备" oninput="document.getElementById('panel').dataset.q=this.value" />
  <main id="panel" style="max-width:100%">内容区</main>
</div>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, captureShot: true, chromePath: CHROME })
    assert.equal(r.ok, true, r.detail)
    assert.ok(typeof r.screenshot === 'string' && r.screenshot.length > 100)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('smokeTest 能检出按钮控件重叠与损坏的本地图片', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const dir = makeSite(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0">
<div id="root"><main style="position:relative;height:120px">
  <button style="position:absolute;left:10px;top:10px;width:120px;height:40px">导出报表</button>
  <button style="position:absolute;left:20px;top:15px;width:120px;height:40px">新建巡检</button>
</main></div>
</body></html>`)
  try {
    const r = await smokeTest(dir, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /控件位置重叠/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }

  const badImg = makeSite(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
<div id="root"><img src="./missing.png" alt="损坏图标" style="width:40px;height:40px" /><p>正常文字</p></div>
</body></html>`)
  try {
    const r = await smokeTest(badImg, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /损坏图标|missing\.png|资源加载失败/)
  } finally {
    rmSync(badImg, { recursive: true, force: true })
  }
})

test('smokeTest 能检出切换下拉筛选框后的运行时崩溃，runLiveFlow 能验证实机渲染文本', { skip: !HAS_CHROME && '没有本地 Chrome' }, async () => {
  const badSel = makeSite(`<!doctype html><html><body>
<div id="root">
  <select onchange="throw new Error('filter crashed')">
    <option value="all">全部容量</option>
    <option value="large">大会议室</option>
  </select>
  <p>会议室预约看板</p>
</div>
</body></html>`)
  try {
    const r = await smokeTest(badSel, { requireRoot: true, chromePath: CHROME })
    assert.equal(r.ok, false)
    assert.match(r.detail, /filter crashed|页面脚本错误/)
  } finally {
    rmSync(badSel, { recursive: true, force: true })
  }

  const goodFlow = makeSite(`<!doctype html><html><body>
<div id="root">
  <input id="title" type="text" placeholder="会议主题" />
  <button onclick="const v=document.getElementById('title').value;if(v){const li=document.createElement('li');li.textContent=v+' (20人)';document.getElementById('list').appendChild(li)}">新建预约</button>
  <select onchange="for(const li of document.querySelectorAll('#list li'))li.style.display=(this.value==='all'||li.textContent.includes(this.value))?'':'none'">
    <option value="all">全部容量</option>
    <option value="20人">20人以上</option>
  </select>
  <button onclick="const u=window.URL.createObjectURL(new Blob(['主题,容量\\n测试,20人'],{type:'text/csv'}));const a=document.createElement('a');a.href=u;a.download='schedule.csv';a.click()">导出今日日程</button>
  <ul id="list"><li>研发周会 (8人)</li><li>产品评审 (20人)</li></ul>
</div>
</body></html>`)
  const hollowShell = makeSite(`<!doctype html><html><body>
<div id="root">
  <p>会议室预约看板（容量与导出演示）</p>
  <input type="text" placeholder="会议主题" />
  <button onclick="void 0">新建预约</button>
  <select onchange="void 0"><option value="all">全部容量</option><option value="20">20人以上</option></select>
  <button onclick="void 0">导出今日日程</button>
  <ul id="list"><li>研发周会 (8人)</li></ul>
</div>
</body></html>`)
  const srv = await startStaticServer(goodFlow)
  const fakeSrv = await startStaticServer(hollowShell)
  try {
    const flowSpec = { expectText: ['预约', '容量', '导出'], verifyCreate: true, verifyFilter: true, verifyExport: true }
    const ok = await runLiveFlow(`http://127.0.0.1:${srv.port}/index.html`, flowSpec, { chromePath: CHROME })
    assert.equal(ok.ok, true, ok.detail)

    // 复现空壳页面陷阱：文案齐全、控件不报错，但点新建/改筛选/点导出均无真实业务结果 → 必须逐项拦截
    const failCreate = await runLiveFlow(`http://127.0.0.1:${fakeSrv.port}/index.html`, { verifyCreate: true }, { chromePath: CHROME })
    assert.equal(failCreate.ok, false)
    assert.match(failCreate.detail, /空壳按钮|未新增/)

    const failFilter = await runLiveFlow(`http://127.0.0.1:${fakeSrv.port}/index.html`, { verifyFilter: true }, { chromePath: CHROME })
    assert.equal(failFilter.ok, false)
    assert.match(failFilter.detail, /空壳筛选|未发生任何变化/)

    const failExport = await runLiveFlow(`http://127.0.0.1:${fakeSrv.port}/index.html`, { verifyExport: true }, { chromePath: CHROME })
    assert.equal(failExport.ok, false)
    assert.match(failExport.detail, /空壳导出|未产生任何下载文件/)

    const miss = await runLiveFlow(`http://127.0.0.1:${srv.port}/index.html`, { expectText: ['不存在的模块'] }, { chromePath: CHROME })
    assert.equal(miss.ok, false)
    assert.match(miss.detail, /未渲染预期业务内容/)
  } finally {
    await srv.close()
    await fakeSrv.close()
    rmSync(goodFlow, { recursive: true, force: true })
    rmSync(hollowShell, { recursive: true, force: true })
  }
})
