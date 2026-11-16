import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { zipSync, strToU8 } from 'fflate'
import { extractZip, importArchive, detectFramework, readZipEntries, LIMITS, selectRoot, pickPackageManager, installHint } from '../src/importer.mjs'

const tmp = () => mkdtempSync(path.join(os.tmpdir(), 'imp-'))
function zip(files, opts = {}) {
  return Buffer.from(zipSync(Object.fromEntries(Object.entries(files).map(([k, v]) => [k, typeof v === 'string' ? strToU8(v) : v])), opts))
}
function workspace() { const w = tmp(); mkdirSync(path.join(w, '.git')); return w }
function importZip(files, extra = {}) {
  const w = workspace()
  const a = path.join(tmp(), 'a.zip')
  writeFileSync(a, Buffer.isBuffer(files) ? files : zip(files))
  return { w, r: importArchive(w, { archivePath: a, format: 'zip', ...extra }) }
}
const rejects = (files, code, re) => assert.throws(() => importZip(files), (e) => e.code === code && (!re || re.test(e.message)))

test('ZIP：路径穿越 ../ 与绝对路径被拒绝', () => {
  rejects({ '../evil.html': '<h1>x</h1>', 'index.html': '<h1>ok</h1>' }, 'IMPORT_UNSAFE', /上级目录/)
  rejects({ 'a/../../evil.html': 'x', 'index.html': 'ok' }, 'IMPORT_UNSAFE')
  rejects({ '/etc/evil': 'x' }, 'IMPORT_UNSAFE', /绝对路径/)
  rejects({ 'C:/evil': 'x' }, 'IMPORT_UNSAFE')
  rejects({ '..\\evil.html': 'x' }, 'IMPORT_UNSAFE')
})

test('ZIP：符号链接被拒绝', () => {
  const buf = zip({ 'index.html': 'ok', link: [strToU8('/etc/passwd'), { os: 3, attrs: (0o120777 << 16) >>> 0 }] })
  assert.equal(readZipEntries(buf).find((e) => e.name === 'link').isSymlink, true)
  rejects(buf, 'IMPORT_UNSAFE', /符号链接/)
})

test('ZIP：去掉统一的顶层目录、跳过 node_modules/.git/__MACOSX/.DS_Store', () => {
  const { w, r } = importZip({
    'vite-app/package.json': '{}',
    'vite-app/index.html': '<h1>x</h1>',
    'vite-app/src/a.js': '1',
    'vite-app/node_modules/x/index.js': '1',
    'vite-app/.git/config': 'x',
    '__MACOSX/vite-app/._index.html': 'x',
    'vite-app/.DS_Store': 'x',
  })
  assert.deepEqual(readdirSync(w).sort(), ['.git', 'index.html', 'package.json', 'src'])
  assert.equal(readFileSync(path.join(w, 'src/a.js'), 'utf8'), '1')
  assert.equal(existsSync(path.join(w, '.git', 'config')), false)
  assert.equal(r.fileCount, 3)
})

test('ZIP：中文目录名（无 UTF-8 标记的 GBK 也能识别）', () => {
  const files = extractZip(readFileSync(path.resolve(import.meta.dirname, '../../e2e/fixtures/html-project.zip')))
  assert.deepEqual(files.map((f) => f.path), ['index.html'])
})

test('ZIP：多个顶层目录时不去掉', () => {
  const { w } = importZip({ 'a/index.html': 'x', 'b/x.html': 'y', 'index.html': 'z' })
  assert.ok(existsSync(path.join(w, 'a/index.html')))
})

test('ZIP：压缩炸弹 — 单文件超限', () => {
  rejects({ 'big.bin': new Uint8Array(LIMITS.fileBytes + 1) }, 'IMPORT_TOO_LARGE', /单个文件/)
})

test('ZIP：压缩炸弹 — 中央目录谎报大小时边解压边计数', () => {
  const buf = zip({ 'big.bin': new Uint8Array(LIMITS.fileBytes + 1024) }, { level: 9 })
  // 把中央目录里的未压缩大小改成 10 字节
  const i = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02]))
  buf.writeUInt32LE(10, i + 24)
  rejects(buf, 'IMPORT_TOO_LARGE', /单个文件/)
})

test('ZIP：压缩炸弹 — 总大小超限', () => {
  const files = {}
  for (let i = 0; i < 11; i++) files[`f${i}.bin`] = new Uint8Array(19 * 1024 * 1024)
  rejects(files, 'IMPORT_TOO_LARGE', /总大小/)
})

test('ZIP：文件数超限', () => {
  const files = {}
  for (let i = 0; i <= LIMITS.files; i++) files[`f${i}.txt`] = 'x'
  rejects(files, 'IMPORT_TOO_LARGE', /文件数量/)
})

test('ZIP：无效文件', () => {
  rejects(Buffer.from('这不是 zip'), 'IMPORT_INVALID')
  rejects({ '__MACOSX/x': 'x' }, 'IMPORT_INVALID', /没有可导入/)
})

test('单个 HTML 存为 index.html；空文件拒绝', () => {
  const w = workspace()
  const a = path.join(tmp(), '小小花园.html')
  writeFileSync(a, '<h1 onclick="x()">hi</h1>')
  const r = importArchive(w, { archivePath: a, format: 'html' })
  assert.deepEqual(r, { framework: 'HTML', deployable: true, unsupportedReason: null, projectKind: 'static', fileCount: 1, totalSize: 25 })
  assert.equal(readFileSync(path.join(w, 'index.html'), 'utf8'), '<h1 onclick="x()">hi</h1>')
  const e = path.join(tmp(), 'e.html')
  writeFileSync(e, '   ')
  assert.throws(() => importArchive(workspace(), { archivePath: e, format: 'html' }), (x) => x.code === 'IMPORT_INVALID')
})

test('ZIP 里唯一的 html 不叫 index.html 时改名', () => {
  const { w, r } = importZip({ 'site/page.html': '<p>x</p>', 'site/a.css': '' })
  assert.ok(existsSync(path.join(w, 'index.html')))
  assert.equal(r.framework, 'HTML')
})

test('工作区非空时拒绝导入', () => {
  const w = workspace()
  writeFileSync(path.join(w, 'x'), '')
  const a = path.join(tmp(), 'a.html')
  writeFileSync(a, 'x')
  assert.throws(() => importArchive(w, { archivePath: a, format: 'html' }), (e) => e.code === 'BAD_STATE')
})

const pkg = (deps, dev = {}) => JSON.stringify({ dependencies: deps, devDependencies: dev })
const detect = (files) => detectFramework(Object.keys(files), (f) => files[f])

test('框架识别', () => {
  const cases = [
    [{ 'package.json': pkg({ next: '15', react: '19' }) }, 'Next.js', false, /Next\.js/],
    [{ 'package.json': pkg({ nuxt: '3', vue: '3' }) }, 'Nuxt.js', false, /Nuxt/],
    [{ 'package.json': pkg({ react: '19' }, { vite: '7' }), 'index.html': '' }, 'Vite + React', true],
    [{ 'package.json': pkg({ vue: '3' }, { vite: '7' }) }, 'Vite + Vue', true],
    [{ 'package.json': pkg({ react: '19' }, { webpack: '5' }) }, 'React', false, /build 脚本/],
    [{ 'package.json': JSON.stringify({ scripts: { build: 'webpack' }, dependencies: { react: '19' }, devDependencies: { webpack: '5' } }) }, 'Webpack + React', true],
    [{ 'package.json': JSON.stringify({ scripts: { build: 'vue-cli-service build' }, dependencies: { vue: '2' }, devDependencies: { '@vue/cli-service': '5' } }) }, 'Vue CLI + Vue', true],
    [{ 'package.json': JSON.stringify({ scripts: { build: 'react-scripts build' }, dependencies: { react: '18', 'react-scripts': '5' } }) }, 'Create React App', true],
    [{ 'package.json': JSON.stringify({ scripts: { build: 'next build' }, dependencies: { next: '15' } }), 'next.config.mjs': "export default { output: 'export' }" }, 'Next.js（静态导出）', true],
    [{ 'package.json': pkg({ express: '4' }) }, 'Node.js 服务端', false, /服务端/],
    [{ 'package.json': pkg({ '@tarojs/taro': '3', react: '18' }) }, 'Taro', false, /小程序/],
    [{ 'package.json': pkg({ vue: '2' }) }, 'Vue', false, /Vue/],
    [{ 'app.py': '', 'requirements.txt': 'flask' }, 'Python', false, /Python/],
    [{ 'pyproject.toml': '' }, 'Python', false],
    [{ 'go.mod': '' }, 'Go', false, /Go/],
    [{ 'pom.xml': '' }, 'Java', false, /Java/],
    [{ 'build.gradle': '' }, 'Java', false],
    [{ 'index.html': '' }, 'HTML', true],
    [{ 'index.html': '', 'about.html': '', 'a.css': '' }, '静态站点', true],
    [{ 'package.json': pkg({ lodash: '4' }), 'index.html': '' }, 'HTML', true],
    [{ 'readme.md': '' }, '未知', false],
  ]
  for (const [files, fw, deployable, reason] of cases) {
    const r = detect(files)
    assert.equal(r.framework, fw, JSON.stringify(files))
    assert.equal(r.deployable, deployable, fw)
    if (deployable) { assert.equal(r.unsupportedReason, null); assert.ok(r.projectKind) }
    else { assert.ok(r.unsupportedReason); assert.equal(r.projectKind, null) }
    if (reason) assert.match(r.unsupportedReason, reason)
  }
  assert.equal(detect({ 'package.json': pkg({ react: '19' }, { vite: '7' }) }).projectKind, 'vite')
  assert.equal(detect({ 'index.html': '' }).projectKind, 'static')
  assert.match(detect({ 'package.json': pkg({ next: '15' }) }).unsupportedReason, /^暂不支持部署 Next\.js 项目：需要服务端运行环境。.*output: 'export'/)
})

test('rootDir：只导入选中的子目录（前后端同仓库时只部署前端）', () => {
  const { w, r } = importZip({
    'repo/pom.xml': '<project/>',
    'repo/src/main/java/App.java': 'class App {}',
    'repo/frontend/package.json': JSON.stringify({ scripts: { build: 'vite build' }, dependencies: { vue: '3' }, devDependencies: { vite: '7' } }),
    'repo/frontend/index.html': '<div id=app></div>',
    'repo/frontend/src/main.js': '1',
  }, { rootDir: 'frontend' })
  assert.deepEqual(readdirSync(w).sort(), ['.git', 'index.html', 'package.json', 'src'])
  assert.equal(r.framework, 'Vite + Vue')
  assert.equal(r.projectKind, 'vite')
  assert.throws(() => selectRoot([{ path: 'a/x', data: '' }], 'b'), (e) => e.code === 'IMPORT_INVALID')
  assert.throws(() => selectRoot([{ path: 'a/x', data: '' }], '../a'), (e) => e.code === 'IMPORT_UNSAFE')
})

test('rootDir：直接部署已构建的 dist/', () => {
  const { w, r } = importZip({ 'p/package.json': pkg({ vue: '3' }, { vite: '7' }), 'p/src/a.vue': '', 'p/dist/index.html': '<script src="./assets/a.js"></script>', 'p/dist/assets/a.js': '1' }, { rootDir: 'dist' })
  assert.deepEqual(readdirSync(w).sort(), ['.git', 'assets', 'index.html'])
  assert.equal(r.projectKind, 'static')
})

test('包管理器选择与安装失败提示', () => {
  const w = tmp()
  writeFileSync(path.join(w, 'package.json'), '{}')
  assert.deepEqual(pickPackageManager(w, { PATH: '' }), { pm: 'npm', lock: false })
  writeFileSync(path.join(w, 'yarn.lock'), '')
  assert.equal(pickPackageManager(w, { PATH: '' }).hideLock, 'yarn.lock')
  writeFileSync(path.join(w, 'pnpm-lock.yaml'), '')
  const bin = tmp()
  writeFileSync(path.join(bin, 'pnpm'), '')
  assert.deepEqual(pickPackageManager(w, { PATH: bin }), { pm: 'pnpm', lock: true })
  assert.match(pickPackageManager(w, { PATH: '' }).note, /没有 pnpm/)
  writeFileSync(path.join(w, 'package-lock.json'), '{}')
  assert.deepEqual(pickPackageManager(w, { PATH: bin }), { pm: 'npm', lock: true })
  assert.match(installHint('npm error 404 Not Found - GET https://registry.npmmirror.com/@corp%2fui'), /私有包/)
  assert.equal(installHint('ok'), '')
})
