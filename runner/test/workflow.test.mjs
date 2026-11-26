import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { parseSimpleYaml, loadProjectWorkflow, discoverWorkspaceLayout } from '../src/workflow.mjs'
import { verify } from '../src/verify.mjs'
import { runEndpointTests } from '../src/endpoint-test.mjs'
import { runTypecheckDiagnostics, analyzeWorkspaceCode } from '../src/code-intel.mjs'
import { checkBash, checkWritePath } from '../src/guard.mjs'

test('parseSimpleYaml & loadProjectWorkflow parse custom zhiye.yaml cleanly', () => {
  const doc = parseSimpleYaml(`
# 自定义 Python + Vue 工作流
runtime: python
frontendDir: web
backendDir: api
diagnostics:
  - name: 语法检查
    command: python3 -m py_compile api/server.py
test:
  - name: 单元测试
    command: python3 -c "assert 1 + 1 == 2"
start:
  command: python3 server.py
  cwd: api
  health: /healthz
`)
  assert.equal(doc.runtime, 'python')
  assert.equal(doc.frontendDir, 'web')
  assert.equal(doc.backendDir, 'api')
  assert.equal(doc.diagnostics.length, 1)
  assert.equal(doc.diagnostics[0].command, 'python3 -m py_compile api/server.py')
  assert.equal(doc.start.health, '/healthz')
})

test('zhiye.yaml drives diagnostics, verify, and endpoint-test on a 100% template-free Python project', async () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'zhiye-custom-wf-'))
  try {
    mkdirSync(path.join(ws, 'api'), { recursive: true })
    writeFileSync(path.join(ws, 'api', 'server.py'), `
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer

ITEMS = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass
    def do_GET(self):
        if self.path == '/healthz':
            self._send(200, {'ok': True, 'custom': True})
        elif self.path == '/api/items':
            self._send(200, {'items': ITEMS})
        else:
            self._send(404, {'error': 'not found'})
    def do_POST(self):
        if self.path == '/api/items':
            n = int(self.headers.get('Content-Length', '0'))
            body = json.loads(self.rfile.read(n).decode('utf-8') or '{}')
            item = {'id': len(ITEMS) + 1, 'name': body.get('name', '')}
            ITEMS.append(item)
            self._send(201, item)
        else:
            self._send(404, {'error': 'not found'})
    def _send(self, code, payload):
        raw = json.dumps(payload).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

port = int(os.environ.get('PORT', '8080'))
HTTPServer(('127.0.0.1', port), Handler).serve_forever()
`)
    writeFileSync(path.join(ws, 'zhiye.yaml'), `
runtime: python
backendDir: api
diagnostics:
  - name: Python 语法检查
    command: python3 -m py_compile api/server.py
test:
  - name: 业务断言测试
    command: python3 -c "print('unit-ok')"
start:
  command: python3 server.py
  cwd: api
  health: /healthz
`)

    const wf = loadProjectWorkflow(ws)
    assert.ok(wf)
    assert.equal(wf.start.health, '/healthz')

    const diag = await runTypecheckDiagnostics(ws, { projectKind: 'service' })
    assert.equal(diag.ok, true, diag.text)

    const vr = await verify(ws, { projectKind: 'service' })
    assert.equal(vr.ok, true, JSON.stringify(vr.checks))
    assert.ok(vr.checks.some((c) => c.name === '业务断言测试' && c.ok))
    assert.ok(vr.checks.some((c) => c.name === '服务启动与探活' && c.ok))

    const ep = await runEndpointTests(ws, {
      requests: [
        { method: 'GET', path: '/healthz', expectStatus: 200, expectIncludes: '"custom": true' },
        { method: 'POST', path: '/api/items', body: { name: 'sensor-a' }, expectStatus: 201, expectIncludes: 'sensor-a' },
        { method: 'GET', path: '/api/items', expectStatus: 200, expectIncludes: 'sensor-a' },
      ],
    })
    assert.equal(ep.ok, true, ep.summary)
  } finally {
    rmSync(ws, { recursive: true, force: true })
  }
})

test('zero-config dynamic discovery works on arbitrary directory names (web/ + api/) without zhiye.yaml', async () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'zhiye-autodiscover-'))
  try {
    mkdirSync(path.join(ws, 'web'), { recursive: true })
    mkdirSync(path.join(ws, 'api'), { recursive: true })
    writeFileSync(path.join(ws, 'web', 'package.json'), JSON.stringify({ dependencies: { vue: '^3.5.0' }, devDependencies: { vite: '^7.0.0' } }))
    writeFileSync(path.join(ws, 'api', 'main.py'), `
import json, os
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        raw = json.dumps({'ok': True}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
HTTPServer(('127.0.0.1', int(os.environ.get('PORT', '8080'))), H).serve_forever()
`)
    const layout = discoverWorkspaceLayout(ws)
    assert.equal(layout.frontendDir, 'web')
    assert.equal(layout.backend?.runtime, 'python')
    assert.equal(layout.backend?.dir, 'api')

    const vr = await verify(ws, { projectKind: 'service' })
    assert.equal(vr.ok, true, JSON.stringify(vr.checks))
  } finally {
    rmSync(ws, { recursive: true, force: true })
  }
})

test('guard allows local polyglot compile/test commands and zhiye.yaml while blocking package installs', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'zhiye-guard-poly-'))
  try {
    assert.equal(checkWritePath(ws, 'zhiye.yaml', { projectKind: 'template' }), null)
    assert.equal(checkBash(ws, 'go test ./...', { projectKind: 'service' }), null)
    assert.equal(checkBash(ws, 'go vet ./...', { projectKind: 'service' }), null)
    assert.equal(checkBash(ws, 'go build -o app .', { projectKind: 'service' }), null)
    assert.equal(checkBash(ws, 'cargo check', { projectKind: 'service' }), null)
    assert.equal(checkBash(ws, 'deno check main.ts', { projectKind: 'service' }), null)

    assert.ok(checkBash(ws, 'go get github.com/gin-gonic/gin', { projectKind: 'service' }))
    assert.ok(checkBash(ws, 'go install foo@latest', { projectKind: 'service' }))
    assert.ok(checkBash(ws, 'cargo install ripgrep', { projectKind: 'service' }))
    assert.ok(checkBash(ws, 'deno install foo', { projectKind: 'service' }))
  } finally {
    rmSync(ws, { recursive: true, force: true })
  }
})

test('code_symbols extracts Java Spring Boot & Go routes/symbols cleanly', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'zhiye-sym-poly-'))
  try {
    mkdirSync(path.join(ws, 'backend'), { recursive: true })
    writeFileSync(path.join(ws, 'backend', 'OrderController.java'), `
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @GetMapping("/list")
    public void list() {}
    @PostMapping("")
    public void create() {}
}
`)
    writeFileSync(path.join(ws, 'backend', 'main.go'), `
package main
type Order struct { ID int }
func RegisterRoutes(r Router) {
    r.GET("/api/ping", ping)
}
`)
    const res = analyzeWorkspaceCode(ws)
    assert.ok(res.symbols.some((s) => s.name === 'OrderController' && s.kind === 'class'))
    assert.ok(res.routes.some((r) => r.method === 'GET' && r.path === '/api/orders/list'))
    assert.ok(res.routes.some((r) => r.method === 'POST' && r.path === '/api/orders'))
    assert.ok(res.symbols.some((s) => s.name === 'Order' && s.kind === 'struct'))
    assert.ok(res.routes.some((r) => r.method === 'GET' && r.path === '/api/ping'))
  } finally {
    rmSync(ws, { recursive: true, force: true })
  }
})
