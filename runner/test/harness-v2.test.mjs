import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { runEndpointTests } from '../src/endpoint-test.mjs'
import { runBrowserTest } from '../src/browser-test.mjs'
import { analyzeWorkspaceCode, findSymbolReferences, generateArchitectureSummary, readSourceInspectSnapshot, runTypecheckDiagnostics } from '../src/code-intel.mjs'
import { validatePackageRequest } from '../src/package-installer.mjs'
import { invokeMcpTool, readMcpCatalog } from '../src/mcp-bridge.mjs'
import { parseQaVerdict } from '../src/qa.mjs'
import { toolSummary } from '../src/steps.mjs'
import { checkToolCall } from '../src/guard.mjs'

function tmpDir(prefix = 'zhiye-hv2-') {
  return mkdtempSync(path.join(os.tmpdir(), prefix))
}

test('test_endpoint runs stateful CRUD sequence against temporary loopback server and blocks external URLs', async () => {
  const dir = tmpDir('zhiye-ep-')
  try {
    mkdirSync(path.join(dir, 'server'), { recursive: true })
    writeFileSync(path.join(dir, 'server', 'index.mjs'), `
import http from 'node:http'
const items = new Map()
let seq = 1
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')
  res.setHeader('Content-Type', 'application/json')
  if (url.pathname === '/api/health') return res.end(JSON.stringify({ ok: true }))
  if (req.method === 'POST' && url.pathname === '/api/items') {
    let body = ''
    req.on('data', (c) => body += c)
    req.on('end', () => {
      const data = JSON.parse(body || '{}')
      const id = String(seq++)
      const row = { id, title: data.title }
      items.set(id, row)
      res.statusCode = 201
      res.end(JSON.stringify({ data: row }))
    })
    return
  }
  const m = /^\\/api\\/items\\/(\\d+)$/.exec(url.pathname)
  if (m && req.method === 'GET') {
    const row = items.get(m[1])
    if (!row) { res.statusCode = 404; return res.end(JSON.stringify({ error: 'not found' })) }
    return res.end(JSON.stringify({ data: row }))
  }
  if (m && req.method === 'DELETE') {
    items.delete(m[1])
    return res.end(JSON.stringify({ ok: true }))
  }
  res.statusCode = 404
  res.end(JSON.stringify({ error: 'unknown' }))
})
server.listen(Number(process.env.PORT || 8080), '127.0.0.1')
`)
    const res = await runEndpointTests(dir, {
      requests: [
        { name: '健康检查', method: 'GET', path: '/api/health', expectJsonIncludes: { ok: true } },
        { name: '创建工单', method: 'POST', path: '/api/items', body: { title: 'T1良率排查' }, expectStatus: 201, saveVar: 'itemId', saveJsonPath: 'data.id' },
        { name: '按ID回查', method: 'GET', path: '/api/items/{{itemId}}', expectJsonIncludes: { data: { title: 'T1良率排查' } } },
        { name: '删除工单', method: 'DELETE', path: '/api/items/{{itemId}}', expectJsonIncludes: { ok: true } },
        { name: '确认已删', method: 'GET', path: '/api/items/{{itemId}}', expectStatus: 404 },
      ],
    })
    assert.equal(res.ok, true, res.summary)
    assert.equal(res.results.length, 5)

    const blocked = await runEndpointTests(dir, {
      requests: [{ method: 'GET', path: 'https://example.com/leak' }],
    })
    assert.equal(blocked.ok, false)
    assert.match(blocked.summary, /禁止访问外部 URL/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('code_intel extracts AST symbols, routes, SQL tables, cross-file references and writes .zhiye/architecture.md', async () => {
  const dir = tmpDir('zhiye-ci-')
  try {
    mkdirSync(path.join(dir, 'src'), { recursive: true })
    mkdirSync(path.join(dir, 'server'), { recursive: true })
    mkdirSync(path.join(dir, 'cloud'), { recursive: true })
    writeFileSync(path.join(dir, 'src', 'OrderCard.tsx'), `
export interface OrderItem { id: string; status: string }
export function OrderCard({ item }: { item: OrderItem }) { return null }
`)
    writeFileSync(path.join(dir, 'src', 'App.tsx'), `
import { OrderCard } from './OrderCard'
export default function App() { return OrderCard }
`)
    writeFileSync(path.join(dir, 'server', 'index.mjs'), `
const app = { get() {}, post() {} }
app.get('/api/orders', () => {})
app.post('/api/orders', () => {})
`)
    writeFileSync(path.join(dir, 'cloud', 'schema.sql'), `
create table if not exists production_orders (id serial primary key, title text);
`)
    const info = analyzeWorkspaceCode(dir)
    assert.ok(info.symbols.some((s) => s.name === 'OrderCard' && s.exported))
    assert.ok(info.symbols.some((s) => s.name === 'OrderItem' && s.kind === 'interface'))
    assert.ok(info.routes.some((r) => r.method === 'POST' && r.path === '/api/orders'))
    assert.ok(info.tables.some((t) => t.name === 'production_orders'))

    const refs = findSymbolReferences(dir, 'OrderCard')
    assert.equal(refs.matches.length >= 2, true)

    const md = generateArchitectureSummary(dir, 'service')
    assert.match(md, /POST \/api\/orders/)
    assert.match(md, /production_orders/)
    assert.equal(existsSync(path.join(dir, '.zhiye', 'architecture.md')), true)
    assert.equal(readFileSync(path.join(dir, '.zhiye', 'architecture.md'), 'utf8'), md)

    const diag = await runTypecheckDiagnostics(dir, { projectKind: 'service' })
    assert.equal(diag.ok, true, diag.text)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('inspect_source formats live probe and sample snapshot cleanly', () => {
  const dir = tmpDir('zhiye-src-')
  try {
    writeFileSync(path.join(dir, 'sources-inspect.json'), JSON.stringify({
      sources: [{
        key: 'agw_yield_daily',
        name: 'T1日良率接口',
        method: 'GET',
        description: '查询厂区日良率',
        params: [{ name: 'fab', type: 'string', required: true, example: 'T1' }],
        liveProbe: { status: 200, sample: '{"fab":"T1","yieldRate":97.4}' },
      }],
    }))
    const out = readSourceInspectSnapshot(dir, 'agw_yield_daily')
    assert.match(out, /T1日良率接口/)
    assert.match(out, /yieldRate/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('package_installer enforces curated allowlist and blocks shell injection', () => {
  assert.equal(validatePackageRequest('echarts').ok, true)
  assert.equal(validatePackageRequest('zod@3.23.8').ok, true)
  assert.equal(validatePackageRequest('evil-backdoor-pkg').ok, false)
  assert.equal(validatePackageRequest('echarts; rm -rf /').ok, false)
  assert.equal(validatePackageRequest('custom-internal-lib', 'npm', { ZHIYE_ALLOWED_PACKAGES: 'custom-internal-lib' }).ok, true)
  assert.equal(validatePackageRequest('fastapi', 'pip').ok, true)
})

test('parseQaVerdict parses structured JSON and forces passed=false on high/critical defects', () => {
  const v1 = parseQaVerdict('测试完成：\n```json\n{"passed":true,"score":95,"summary":"全部通过","checklist":[{"item":"表单提交","passed":true}],"defects":[]}\n```')
  assert.equal(v1.passed, true)
  assert.equal(v1.score, 95)

  const v2 = parseQaVerdict('```json\n{"passed":true,"score":80,"summary":"有高危问题","defects":[{"severity":"high","area":"提交","repro":"点提交","expected":"200","actual":"500"}]}\n```')
  assert.equal(v2.passed, false)
  assert.equal(v2.defects.length, 1)
})

test('mcp_bridge reads catalog and calls platform proxy with task token', async () => {
  const dir = tmpDir('zhiye-mcp-')
  let receivedAuth = ''
  let receivedBody = null
  const srv = http.createServer((req, res) => {
    receivedAuth = req.headers.authorization || ''
    let raw = ''
    req.on('data', (c) => raw += c)
    req.on('end', () => {
      receivedBody = JSON.parse(raw || '{}')
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify({ ok: true, content: 'MES-API-v2 schema: lotId, fab, yieldRate' }))
    })
  })
  await new Promise((r) => srv.listen(0, '127.0.0.1', r))
  const port = srv.address().port
  try {
    writeFileSync(path.join(dir, 'mcp-servers.json'), JSON.stringify({
      servers: [{ key: 'csot-docs', name: '华星文档MCP', transport: 'mock', tools: [{ name: 'search_docs', description: '检索研发文档' }] }],
    }))
    assert.equal(readMcpCatalog(dir).length, 1)
    const r = await invokeMcpTool(dir, { server: 'csot-docs', tool: 'search_docs', arguments: { q: 'MES' } }, {
      ZHIYE_GATEWAY_URL: `http://127.0.0.1:${port}/llm/v1`,
      ZHIYE_TASK_TOKEN: 'tok-test-123',
    })
    assert.equal(r.ok, true)
    assert.match(r.text, /yieldRate/)
    assert.equal(receivedAuth, 'Bearer tok-test-123')
    assert.equal(receivedBody.serverKey, 'csot-docs')
    assert.equal(receivedBody.tool, 'search_docs')
  } finally {
    srv.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('browser_test interacts with a real local HTML page in headless Chrome when Chrome is available', async (t) => {
  const chrome = process.env.ZHIYE_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  if (!existsSync(chrome)) {
    t.skip('No local Chrome installed for headless E2E test')
    return
  }
  const dir = tmpDir('zhiye-bt-')
  try {
    writeFileSync(path.join(dir, 'index.html'), `<!doctype html>
<html><body>
  <h1>工单工作台</h1>
  <input id="title" placeholder="输入工单标题" />
  <button id="add">新增工单</button>
  <ul id="list"></ul>
  <script>
    const list = document.getElementById('list');
    const items = JSON.parse(localStorage.getItem('orders') || '[]');
    function render() { list.innerHTML = items.map(x => '<li class="row">' + x + '</li>').join(''); }
    render();
    document.getElementById('add').addEventListener('click', () => {
      const v = document.getElementById('title').value.trim();
      if (!v) return;
      items.push(v);
      localStorage.setItem('orders', JSON.stringify(items));
      render();
    });
  </script>
</body></html>`)
    const res = await runBrowserTest(dir, {
      device: 'desktop',
      steps: [
        { action: 'fill', selector: '#title', value: 'T2曝光机异常排查' },
        { action: 'click', text: '新增工单' },
        { action: 'assert_text', expected: 'T2曝光机异常排查' },
        { action: 'assert_count', selector: '.row', expected: 1 },
        { action: 'reload' },
        { action: 'assert_text', expected: 'T2曝光机异常排查' },
      ],
    }, { projectKind: 'static', env: { ...process.env, ZHIYE_CHROME_PATH: chrome } })
    assert.equal(res.ok, true, res.text)
    assert.equal(res.consoleErrors.length, 0)
    assert.ok(res.image && res.image.length > 100)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('toolSummary formats all Harness V2 tools cleanly', () => {
  assert.match(toolSummary('/tmp', 'browser_test', { steps: [{}, {}], device: 'wide' }), /浏览器交互自测（2 步 · 宽屏）/)
  assert.match(toolSummary('/tmp', 'test_endpoint', { requests: [{}] }), /实测后端接口（1 步）/)
  assert.match(toolSummary('/tmp', 'typecheck_diagnostics', {}), /增量类型与语法诊断/)
  assert.match(toolSummary('/tmp', 'code_symbols', { action: 'references', symbol: 'OrderCard' }), /追踪符号引用 · OrderCard/)
  assert.match(toolSummary('/tmp', 'install_package', { packageName: 'echarts' }), /安装白名单依赖 · echarts/)
  assert.match(toolSummary('/tmp', 'mcp_call', { server: 'csot-docs', tool: 'search' }), /调用企业 MCP · csot-docs\/search/)
  for (const name of ['browser_test', 'test_endpoint', 'typecheck_diagnostics', 'code_symbols', 'inspect_source', 'install_package', 'mcp_list', 'mcp_call']) {
    assert.equal(checkToolCall(name, {}, { cwd: '/tmp', projectKind: 'template', agentDir: '/tmp' }), null, `guard should allow ${name}`)
  }
  assert.notEqual(checkToolCall('evil_unregistered_tool', {}, { cwd: '/tmp', projectKind: 'template', agentDir: '/tmp' }), null)
})

test('install_package detects preinstalled packages and restores node_modules symlink on failure', async () => {
  const { installPackage } = await import('../src/package-installer.mjs')
  const { symlinkSync, lstatSync, readlinkSync } = await import('node:fs')
  const root = mkdtempSync(path.join(os.tmpdir(), 'zhiye-pkg-sym-'))
  const cacheNm = path.join(root, 'cache-nm')
  const ws = path.join(root, 'ws')
  try {
    mkdirSync(path.join(cacheNm, 'pg'), { recursive: true })
    writeFileSync(path.join(cacheNm, 'pg', 'package.json'), '{"name":"pg","version":"8.13.1"}')
    mkdirSync(ws, { recursive: true })
    writeFileSync(path.join(ws, 'package.json'), '{"name":"demo","dependencies":{}}')
    symlinkSync(cacheNm, path.join(ws, 'node_modules'))

    // 1. Preinstalled package returns ok without touching symlink
    const hit = await installPackage(ws, { packageName: 'pg' })
    assert.equal(hit.ok, true)
    assert.equal(lstatSync(path.join(ws, 'node_modules')).isSymbolicLink(), true)

    // 2. Failed offline install against invalid registry MUST restore symlink & package.json
    const fail = await installPackage(ws, { packageName: 'echarts' }, { ...process.env, ZHIYE_NPM_REGISTRY: 'http://127.0.0.1:9' })
    assert.equal(fail.ok, false)
    assert.equal(lstatSync(path.join(ws, 'node_modules')).isSymbolicLink(), true)
    assert.equal(readlinkSync(path.join(ws, 'node_modules')), cacheNm)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('static typecheck_diagnostics checks JS syntax, FastAPI detects custom instance/factory, Go detects cmd/*/main.go', async () => {
  const { detectFastApiTarget } = await import('../src/endpoint-test.mjs')
  const { detectGoBuildTarget } = await import('../src/verify.mjs')

  // 1. static JS syntax checking
  const sdir = tmpDir('zhiye-static-diag-')
  try {
    writeFileSync(path.join(sdir, 'index.html'), '<h1>hi</h1><script src="app.js"></script>')
    writeFileSync(path.join(sdir, 'app.js'), 'const x = 1;\nconsole.log(x);\n')
    const okDiag = await runTypecheckDiagnostics(sdir, { projectKind: 'static' })
    assert.equal(okDiag.ok, true, okDiag.text)
    assert.match(okDiag.text, /已检查 1 个 JS 文件语法/)

    writeFileSync(path.join(sdir, 'app.js'), 'function broken( {\n')
    const badDiag = await runTypecheckDiagnostics(sdir, { projectKind: 'static' })
    assert.equal(badDiag.ok, false)
    assert.equal(badDiag.diagnostics[0].code, 'JS_SYNTAX')
  } finally {
    rmSync(sdir, { recursive: true, force: true })
  }

  // 2. FastAPI dynamic symbol & factory detection
  assert.deepEqual(detectFastApiTarget('from fastapi import FastAPI\napplication = FastAPI()\n', 'main'), { target: 'main:application', factory: false })
  assert.deepEqual(detectFastApiTarget('api: FastAPI = fastapi.FastAPI(title="x")\n', 'server'), { target: 'server:api', factory: false })
  assert.deepEqual(detectFastApiTarget('def create_app():\n    return FastAPI()\n', 'app'), { target: 'app:create_app', factory: true })
  assert.deepEqual(detectFastApiTarget('FastAPI()', 'main'), { target: 'main:app', factory: false })

  // 3. Go cmd/*/main.go detection
  const gdir = tmpDir('zhiye-go-target-')
  try {
    mkdirSync(path.join(gdir, 'cmd', 'server'), { recursive: true })
    writeFileSync(path.join(gdir, 'go.mod'), 'module demo\n')
    writeFileSync(path.join(gdir, 'cmd', 'server', 'main.go'), 'package main\nfunc main() {}\n')
    assert.equal(detectGoBuildTarget(gdir), './cmd/server')

    writeFileSync(path.join(gdir, 'main.go'), 'package main\nfunc main() {}\n')
    assert.equal(detectGoBuildTarget(gdir), '.')
  } finally {
    rmSync(gdir, { recursive: true, force: true })
  }
})
