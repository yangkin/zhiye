import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { verify, buildPlan, absoluteAssetRefs, hasLocalPostcssConfig, PREVIEW_DIR } from '../src/verify.mjs'

const ZHIYE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CACHE = path.join(ZHIYE, 'runtime/template-cache/node_modules')
const noCache = !existsSync(CACHE) && '没有模板依赖缓存'

function project(files, linkDeps = true) {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'imp-build-'))
  for (const [p, c] of Object.entries(files)) { mkdirSync(path.dirname(path.join(ws, p)), { recursive: true }); writeFileSync(path.join(ws, p), c) }
  if (linkDeps) symlinkSync(CACHE, path.join(ws, 'node_modules'))
  else mkdirSync(path.join(ws, 'node_modules'))
  return ws
}
const VITE_APP = {
  'index.html': '<!doctype html><html><head><title>x</title></head><body><div id="root"></div><script type="module" src="/src/main.js"></script></body></html>',
  'src/main.js': "document.getElementById('root').textContent = '导入的应用'\n",
  'vite.config.js': 'export default {}\n',
}

test('buildPlan：最后一步是 vite build 时追加 --base=./，前置步骤失败可跳过', () => {
  assert.deepEqual(buildPlan({ scripts: { build: 'vite build' } }, true).args, ['run', 'build', '--no-update-notifier', '--', '--base=./'])
  const p = buildPlan({ scripts: { build: 'vue-tsc -b && vite build --mode staging' } }, true)
  assert.deepEqual(p.fallback.args, ['build', '--mode', 'staging', '--base=./'])
  assert.match(p.fallback.note, /vue-tsc -b/)
  assert.deepEqual(buildPlan({ scripts: { build: 'vite build --base /admin/' } }, true).args, ['run', 'build', '--no-update-notifier'])
  assert.deepEqual(buildPlan({ scripts: { build: 'vue-cli-service build' } }, false).args, ['run', 'build', '--no-update-notifier'])
  assert.equal(buildPlan({ scripts: { build: 'vue-cli-service build' } }, false).fallback, undefined)
  assert.deepEqual(buildPlan({}, true).args, ['build', '--base=./'])
  assert.equal(buildPlan({}, false), null)
  assert.equal(absoluteAssetRefs('<script src="/assets/a.js"></script>'), true)
  assert.equal(absoluteAssetRefs('<script src="./assets/a.js"></script><a href="/about">x</a><script src="//cdn/x.js"></script>'), false)
})

test('默认 base 的 Vite 项目：构建产物用相对路径（预览挂在 /p/{buildId}/ 下也能加载）', { skip: noCache, timeout: 120_000 }, async () => {
  const ws = project({ ...VITE_APP, 'package.json': JSON.stringify({ scripts: { build: 'vite build' }, devDependencies: { vite: '7' } }) })
  const r = await verify(ws, { projectKind: 'vite' })
  assert.equal(r.ok, true, JSON.stringify(r.checks))
  const html = readFileSync(path.join(ws, PREVIEW_DIR, 'index.html'), 'utf8')
  assert.match(html, /src="\.\/assets\/index-[^"]+\.js"/)
  assert.equal(absoluteAssetRefs(html), false)
  assert.equal(existsSync(path.join(ws, 'dist')), false)
  assert.equal(existsSync(path.join(ws, '.postcssrc.json')), false)
  assert.equal(r.notes, undefined)
})

test('hasLocalPostcssConfig：识别项目级 PostCSS 配置并在缺失时放行临时隔离', () => {
  const emptyWs = project({ 'package.json': '{}' }, false)
  assert.equal(hasLocalPostcssConfig(emptyWs, {}), false)
  assert.equal(hasLocalPostcssConfig(emptyWs, { postcss: {} }), true)
  const cfgWs = project({ 'package.json': '{}', 'postcss.config.js': 'export default {}\n' }, false)
  assert.equal(hasLocalPostcssConfig(cfgWs, {}), true)
})

test('build 脚本的前置步骤（类型检查）失败：跳过后直接 vite build，并给出提示', { skip: noCache, timeout: 120_000 }, async () => {
  const ws = project({ ...VITE_APP, 'package.json': JSON.stringify({ scripts: { build: 'node -e "process.exit(2)" && vite build' }, devDependencies: { vite: '7' } }) })
  const r = await verify(ws, { projectKind: 'vite' })
  assert.equal(r.ok, true, JSON.stringify(r.checks))
  assert.match(r.notes[0], /已跳过这些步骤直接执行 vite build/)
})

test('非 Vite 的 build 脚本：在 build/ 里找到产物，提示绝对路径，构建目录不留在源码里', { timeout: 60_000 }, async () => {
  const ws = project({
    'package.json': JSON.stringify({ scripts: { build: 'node build.mjs' }, dependencies: { react: '18', 'react-scripts': '5' } }),
    'build.mjs': "import { mkdirSync, writeFileSync } from 'node:fs'\nmkdirSync('build/static', { recursive: true })\nwriteFileSync('build/static/main.js', 'document.body.innerHTML = \"<h1>CRA</h1>\"')\nwriteFileSync('build/index.html', '<!doctype html><html><head></head><body><script src=\"/static/main.js\"></script></body></html>')\n",
  }, false)
  const r = await verify(ws, { projectKind: 'vite' })
  assert.equal(r.checks[0].ok, true, r.checks[0].detail)
  assert.ok(existsSync(path.join(ws, PREVIEW_DIR, 'static/main.js')))
  assert.equal(existsSync(path.join(ws, 'build')), false)
  assert.match(r.notes.join('\n'), /用 \/ 开头的路径/)
})

test('构建成功但没有 index.html：说明查找过哪些目录', { timeout: 60_000 }, async () => {
  const ws = project({ 'package.json': JSON.stringify({ scripts: { build: 'node -e "1"' }, dependencies: { react: '18' } }) }, false)
  const r = await verify(ws, { projectKind: 'vite' })
  assert.equal(r.ok, false)
  assert.match(r.checks[0].detail, /没有找到新生成的 index\.html（查找了 dist\/、build\/、out\/、www\/）/)
})
