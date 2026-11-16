// 脚本项目：没有网页，校验脚本与 README；写入范围受限
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { verify } from '../src/verify.mjs'
import { checkWritePath } from '../src/guard.mjs'

function project(files) {
  const dir = mkdtempSync(path.join(tmpdir(), 'zhiye-script-'))
  for (const [p, content] of Object.entries(files)) {
    const abs = path.join(dir, p)
    mkdirSync(path.dirname(abs), { recursive: true })
    writeFileSync(abs, content)
  }
  return dir
}

test('脚本项目：有 main.py + README 就通过，没有构建产物', async () => {
  const dir = project({ 'main.py': 'print("hi")\n', 'README.md': '# demo\n\n运行：python3 main.py\n' })
  const r = await verify(dir, { projectKind: 'script' })
  assert.equal(r.ok, true)
  assert.equal(r.previewDir, null)
  assert.equal(r.publishDir, null)
  assert.equal(r.editable, false)
})

test('脚本项目：缺 README 或没有脚本会失败并说明原因', async () => {
  const dir = project({ 'main.py': 'print("hi")\n' })
  const r = await verify(dir, { projectKind: 'script' })
  assert.equal(r.ok, false)
  assert.ok(r.checks.find((c) => c.name === 'README')?.detail.includes('README'))
})

test('脚本项目只能写脚本、README 和 scripts/examples/docs', () => {
  const dir = project({ 'main.py': 'print(1)\n' })
  assert.equal(checkWritePath(dir, 'main.py', { projectKind: 'script' }), null)
  assert.equal(checkWritePath(dir, 'scripts/util.py', { projectKind: 'script' }), null)
  assert.ok(checkWritePath(dir, 'src/App.tsx', { projectKind: 'script' }))
  assert.ok(checkWritePath(dir, 'package.json', { projectKind: 'script' }))
})
