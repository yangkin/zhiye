// verify：类型检查、两套构建、冒烟测试、全栈起服探活。
import { realpathSync, existsSync, mkdirSync, mkdtempSync, rmSync, readdirSync, lstatSync, statSync, copyFileSync, readFileSync, writeFileSync, cpSync, promises as fsp } from 'node:fs'
import { spawn } from 'node:child_process'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { run, emitLog, flushLogs, summarize, log, RunnerError } from './util.mjs'
import { smokeTest } from './smoke.mjs'
import { checkSkillMd, checkReferences, checkScripts, checkSecurityAndCleanliness } from './skill.mjs'
import { discoverWorkspaceLayout, loadProjectWorkflow } from './workflow.mjs'
import { checkBash } from './guard.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
export const PREVIEW_DIR = '.zhiye/dist-preview'
export const PUBLISH_DIR = '.zhiye/dist-publish'
const SKIPPED = '前面的检查没有通过，已跳过'

export function locPluginPath(env = process.env) {
  const p = env.ZHIYE_LOC_PLUGIN || path.resolve(here, '../../packages/vite-plugin-loc/index.mjs')
  // Windows 原生路径（D:\...）不是合法 ESM 标识符，统一转成 file:// URL 再让 vite.config.ts 动态 import
  return p.startsWith('file:') ? p : pathToFileURL(p).href
}

function check(name, ok, detail = '', extra = {}) { return { name, ok, detail: ok ? detail : summarize(detail), ...extra } }

async function step(name, fn) {
  const t = Date.now()
  emitLog(`▶ ${name}`)
  const r = await fn()
  flushLogs()
  log(`verify ${name}: ${r.ok ? 'ok' : 'FAIL'}${r.skipped ? ' (skipped)' : ''} ${Date.now() - t}ms`)
  return { ...r, name, durationMs: Date.now() - t }
}

function hasMainGo(dir) {
  if (!existsSync(dir)) return false
  try {
    for (const name of readdirSync(dir)) {
      if (!name.endsWith('.go') || name.endsWith('_test.go')) continue
      const src = readFileSync(path.join(dir, name), 'utf8')
      if (/^\s*package\s+main\b/m.test(src)) return true
    }
  } catch {}
  return false
}

/** 识别 Go 项目编译入口：优先根目录 package main，其次 cmd/<server|api|app|*>/ 下的 package main。 */
export function detectGoBuildTarget(goDir) {
  if (hasMainGo(goDir)) return '.'
  const cmdRoot = path.join(goDir, 'cmd')
  if (existsSync(cmdRoot)) {
    try {
      const subs = readdirSync(cmdRoot, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name)
      const preferred = ['server', 'api', 'app', 'web', ...subs.filter((s) => !['server', 'api', 'app', 'web'].includes(s)).sort()]
      for (const s of [...new Set(preferred)]) {
        if (subs.includes(s) && hasMainGo(path.join(cmdRoot, s))) return `./cmd/${s}`
      }
    } catch {}
  }
  return '.'
}

function newestMtimeMs(target, depth = 0) {
  if (depth > 6 || !existsSync(target)) return 0
  let st
  try { st = statSync(target) } catch { return 0 }
  if (st.isFile()) return st.mtimeMs
  if (!st.isDirectory()) return 0
  let max = st.mtimeMs
  try {
    for (const name of readdirSync(target)) {
      if (name === 'node_modules' || name === 'dist' || name === '.git') continue
      const t = newestMtimeMs(path.join(target, name), depth + 1)
      if (t > max) max = t
    }
  } catch {}
  return max
}

/**
 * 为全栈 service 项目的浏览器自测 / 截图准备前端产物：
 * 自动识别根目录或 frontend/web/client 子目录；若 dist 缺失或源码已更新则构建 dist，
 * 并在 Java (Maven/Gradle) 仓库中同步拷贝到 src/main/resources/static。
 */
export async function prepareServiceFrontend(cwd, env = process.env) {
  const layout = discoverWorkspaceLayout(cwd)
  if (layout.frontendDir === null) return { ok: true, built: false, frontendDir: null }
  const frontAbs = layout.frontendDir ? path.join(cwd, layout.frontendDir) : cwd
  const hasVite = Boolean(viteBin(frontAbs)) && existsSync(path.join(frontAbs, 'node_modules'))
  if (!hasVite) return { ok: true, built: false, frontendDir: layout.frontendDir }

  const distIndex = path.join(frontAbs, 'dist', 'index.html')
  let needBuild = !existsSync(distIndex)
  if (!needBuild) {
    try {
      const distTime = statSync(distIndex).mtimeMs
      const srcTime = Math.max(
        newestMtimeMs(path.join(frontAbs, 'src')),
        newestMtimeMs(path.join(frontAbs, 'index.html')),
        newestMtimeMs(path.join(frontAbs, 'public')),
      )
      if (srcTime > distTime) needBuild = true
    } catch {
      needBuild = true
    }
  }
  let built = false
  if (needBuild) {
    const b = await viteBuild(frontAbs, buildEnv(env, { ZHIYE_LOC_PLUGIN: '' }), { outDir: 'dist', minify: false })
    if (!b.ok) return { ok: false, built: false, frontendDir: layout.frontendDir, detail: String(b.detail || '前端构建失败') }
    built = true
  }
  if ((layout.backend?.runtime === 'java-maven' || layout.backend?.runtime === 'java-gradle') && existsSync(distIndex)) {
    const javaDir = layout.backend.dir ? path.join(cwd, layout.backend.dir) : cwd
    const staticTarget = path.join(javaDir, 'src', 'main', 'resources', 'static')
    try {
      rmSync(staticTarget, { recursive: true, force: true })
      mkdirSync(staticTarget, { recursive: true })
      cpSync(path.join(frontAbs, 'dist'), staticTarget, { recursive: true })
    } catch {}
  }
  return { ok: true, built, frontendDir: layout.frontendDir }
}

export function buildEnv(env, extra = {}) {
  return { ...env, NODE_ENV: 'production', FORCE_COLOR: '0', NO_COLOR: '1', CI: '1', BROWSERSLIST_IGNORE_OLD_DATA: '1', ...extra }
}

/** 把 tsc 输出整理成「文件(行,列): 错误」的简洁形式。 */
function tscSummary(out) {
  const lines = out.split(/\r?\n/).filter((l) => l.trim())
  return lines.join('\n')
}

function cleanDir(cwd, rel) {
  const abs = path.join(cwd, rel)
  rmSync(abs, { recursive: true, force: true })
  mkdirSync(path.dirname(abs), { recursive: true })
  return abs
}

function viteBin(cwd) {
  const p = path.join(cwd, 'node_modules', 'vite', 'bin', 'vite.js')
  return existsSync(p) ? p : null
}

export async function viteBuild(cwd, env, { mode, outDir, minify = true } = {}) {
  const bin = viteBin(cwd)
  if (!bin) return { ok: false, detail: '找不到 vite（node_modules 不完整）' }
  cleanDir(cwd, outDir)
  const args = [bin, 'build', '--outDir', outDir, '--emptyOutDir', '--configLoader', 'native', '--logLevel', 'warn']
  if (mode) args.push('--mode', mode)
  if (minify === false) args.push('--minify', 'false')
  const r = await run(process.execPath, args, { cwd, env, timeoutMs: 180_000, onLine: emitLog })
  if (r.timedOut) return { ok: false, detail: `构建超时\n${r.out}` }
  if (r.code !== 0) return { ok: false, detail: r.out || `构建失败（退出码 ${r.code}）` }
  if (!existsSync(path.join(cwd, outDir, 'index.html'))) return { ok: false, detail: `构建完成但没有生成 index.html\n${r.out}` }
  return { ok: true, detail: '' }
}

function copyTree(src, dst, { skip = () => false } = {}) {
  mkdirSync(dst, { recursive: true })
  for (const name of readdirSync(src)) {
    const s = path.join(src, name)
    const rel = name
    if (skip(rel, s)) continue
    const st = lstatSync(s)
    if (st.isSymbolicLink()) continue
    if (st.isDirectory()) copyTree(s, path.join(dst, name), { skip: (r, abs) => skip(path.join(rel, r), abs) })
    else if (st.isFile()) copyFileSync(s, path.join(dst, name))
  }
}

const skipProject = (rel) => {
  const norm = rel.split(path.sep).join('/')
  const first = norm.split('/')[0]
  return first === '.git' || first === '.zhiye' || first === 'node_modules' || norm === 'docs/PROJECT_NOTES.md'
    || norm.split('/').includes('node_modules') || norm.split('/').includes('.git')
}

function listHtml(dir, sub = '') {
  const out = []
  for (const name of readdirSync(path.join(dir, sub))) {
    const rel = sub ? `${sub}/${name}` : name
    const st = lstatSync(path.join(dir, rel))
    if (st.isDirectory()) out.push(...listHtml(dir, rel))
    else if (st.isFile() && /\.html?$/i.test(name)) out.push(rel)
  }
  return out
}

export function templateLint(cwd) {
  const problems = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const abs = path.join(dir, name)
      const st = lstatSync(abs)
      if (st.isDirectory()) walk(abs)
      else if (st.isFile() && /\.(tsx?|jsx?)$/.test(name)) {
        const src = readFileSync(abs, 'utf8')
        if (/\b(BrowserRouter|createBrowserRouter)\b/.test(src)) problems.push(`${path.relative(cwd, abs).split(path.sep).join('/') }：预览和发布地址可能在子路径下，路由请改用 HashRouter（或 createHashRouter），不要用 BrowserRouter`)
      }
    }
  }
  if (existsSync(path.join(cwd, 'src'))) walk(path.join(cwd, 'src'))
  return problems
}

async function verifyTemplate(cwd, env) {
  const checks = []
  const plugin = locPluginPath(env)
  const tscBin = path.join(cwd, 'node_modules', 'typescript', 'bin', 'tsc')
  checks.push(await step('类型检查', async () => {
    if (!existsSync(tscBin)) return { ok: false, detail: '找不到 typescript（node_modules 不完整）' }
    const r = await run(process.execPath, [tscBin, '--noEmit', '-p', 'tsconfig.json', '--pretty', 'false'], { cwd, env: buildEnv(env), timeoutMs: 120_000 })
    if (r.timedOut) return { ok: false, detail: '类型检查超时' }
    if (r.code !== 0) return { ok: false, detail: tscSummary(r.out) || `tsc 退出码 ${r.code}` }
    const problems = templateLint(cwd)
    return problems.length ? { ok: false, detail: problems.join('\n') } : { ok: true, detail: '' }
  }))
  const [preview, publish] = await Promise.all([
    step('构建预览版', () => viteBuild(cwd, buildEnv(env, { ZHIYE_LOC_PLUGIN: plugin }), { mode: 'preview', outDir: PREVIEW_DIR, minify: false })),
    step('构建发布版', () => viteBuild(cwd, buildEnv(env, { ZHIYE_LOC_PLUGIN: '' }), { outDir: PUBLISH_DIR })),
  ])
  if (preview.ok) {
    // 防御：确认预览版确实带了 data-loc 和编辑桥
    const html = readFileSync(path.join(cwd, PREVIEW_DIR, 'index.html'), 'utf8')
    if (!html.includes('data-zhiye-editor-ui')) { preview.ok = false; preview.detail = '预览版没有注入编辑桥（vite.config.ts 里的织页插件配置被改动了？）' }
  }
  checks.push(preview, publish)
  checks.push(preview.ok && publish.ok
    ? await step('冒烟测试', () => smokeTest(path.join(cwd, PREVIEW_DIR), { requireRoot: true, captureShot: true, chromePath: env.ZHIYE_CHROME_PATH }))
    : { name: '冒烟测试', ok: false, skipped: true, detail: SKIPPED })
  return { checks, editable: true }
}

export function vueLint(cwd) {
  const problems = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const abs = path.join(dir, name)
      const st = lstatSync(abs)
      if (st.isDirectory()) walk(abs)
      else if (st.isFile() && /\.(vue|ts|js)$/.test(name)) {
        const src = readFileSync(abs, 'utf8')
        if (/\bcreateWebHistory\b/.test(src)) problems.push(`${path.relative(cwd, abs).split(path.sep).join('/')}：预览和发布地址可能在子路径下，路由请改用 createWebHashHistory，不要用 createWebHistory`)
        if (/from\s+['"](react|react-dom|lucide-react|recharts|react-router)['"]/.test(src)) problems.push(`${path.relative(cwd, abs).split(path.sep).join('/')}：这是 Vue 项目，不能引入 React 相关的库`)
      }
    }
  }
  if (existsSync(path.join(cwd, 'src'))) walk(path.join(cwd, 'src'))
  return problems
}

/** Vue 模板：vue-tsc 类型检查 → 构建（预览版与发布版相同，不注入可视化编辑桥）→ 冒烟测试 */
async function verifyVue(cwd, env) {
  const checks = []
  const vueTsc = path.join(cwd, 'node_modules', 'vue-tsc', 'bin', 'vue-tsc.js')
  checks.push(await step('类型检查', async () => {
    if (!existsSync(vueTsc)) return { ok: false, detail: '找不到 vue-tsc（node_modules 不完整）' }
    const r = await run(process.execPath, [vueTsc, '--noEmit', '-p', 'tsconfig.json', '--pretty', 'false'], { cwd, env: buildEnv(env), timeoutMs: 180_000 })
    if (r.timedOut) return { ok: false, detail: '类型检查超时' }
    if (r.code !== 0) return { ok: false, detail: tscSummary(r.out) || `vue-tsc 退出码 ${r.code}` }
    const problems = vueLint(cwd)
    return problems.length ? { ok: false, detail: problems.join('\n') } : { ok: true, detail: '' }
  }))
  const preview = await step('构建预览版', () => viteBuild(cwd, buildEnv(env, { ZHIYE_LOC_PLUGIN: '' }), { mode: 'preview', outDir: PREVIEW_DIR }))
  checks.push(preview)
  const publish = preview.ok
    ? await step('构建发布版', async () => {
      cleanDir(cwd, PUBLISH_DIR)
      cpSync(path.join(cwd, PREVIEW_DIR), path.join(cwd, PUBLISH_DIR), { recursive: true })
      return { ok: true, detail: '' }
    })
    : { name: '构建发布版', ok: false, skipped: true, detail: SKIPPED }
  checks.push(publish)
  checks.push(preview.ok && publish.ok
    ? await step('冒烟测试', () => smokeTest(path.join(cwd, PREVIEW_DIR), { requireRoot: true, captureShot: true, chromePath: env.ZHIYE_CHROME_PATH }))
    : { name: '冒烟测试', ok: false, skipped: true, detail: SKIPPED })
  return { checks, editable: false }
}

async function verifyStatic(cwd, env) {
  const checks = []
  const entry = await step('入口检查', async () => (existsSync(path.join(cwd, 'index.html')) ? { ok: true, detail: '' } : { ok: false, detail: '项目根目录没有 index.html' }))
  checks.push(entry)
  if (!entry.ok) {
    checks.push({ name: '生成预览版', ok: false, skipped: true, detail: SKIPPED }, { name: '生成发布版', ok: false, skipped: true, detail: SKIPPED }, { name: '冒烟测试', ok: false, skipped: true, detail: SKIPPED })
    return { checks, editable: true }
  }
  const preview = await step('生成预览版', async () => {
    const dst = cleanDir(cwd, PREVIEW_DIR)
    copyTree(cwd, dst, { skip: skipProject })
    const { annotateHtml } = await import(locPluginPath(env))
    const failed = []
    for (const rel of listHtml(dst)) {
      const abs = path.join(dst, rel)
      try { writeFileSync(abs, annotateHtml(readFileSync(abs, 'utf8'), rel)) } catch (e) { failed.push(`${rel}: ${e.message}`) }
    }
    return failed.length ? { ok: false, detail: `HTML 处理失败：\n${failed.join('\n')}` } : { ok: true, detail: '' }
  })
  checks.push(preview)
  const publish = await step('生成发布版', async () => {
    const dst = cleanDir(cwd, PUBLISH_DIR)
    copyTree(cwd, dst, { skip: skipProject })
    return { ok: true, detail: '' }
  })
  checks.push(publish)
  checks.push(preview.ok ? await step('冒烟测试', () => smokeTest(path.join(cwd, PREVIEW_DIR), { chromePath: env.ZHIYE_CHROME_PATH })) : { name: '冒烟测试', ok: false, skipped: true, detail: SKIPPED })
  return { checks, editable: true }
}

/** 从 vite 配置里找 outDir（找不到用 dist）。 */
export function viteOutDir(cwd) {
  for (const f of ['vite.config.ts', 'vite.config.js', 'vite.config.mjs', 'vite.config.mts', 'vite.config.cjs']) {
    const p = path.join(cwd, f)
    if (!existsSync(p)) continue
    const m = /outDir\s*:\s*['"`]([^'"`]+)['"`]/.exec(readFileSync(p, 'utf8'))
    if (m) return m[1]
  }
  return 'dist'
}

const OUT_DIRS = ['dist', 'build', 'out', 'www']

export function buildPlan(pkg, hasVite) {
  const script = String(pkg?.scripts?.build || '').trim()
  if (!script) return hasVite ? { cmd: 'vite', args: ['build', '--base=./'], label: 'vite build --base=./' } : null
  const segs = script.split('&&').map((x) => x.trim())
  const last = segs[segs.length - 1]
  const viteLast = hasVite && /^vite\s+build(\s|$)/.test(last) && !/--base\b/.test(last)
  const plan = { cmd: 'npm', args: ['run', 'build', '--no-update-notifier', ...(viteLast ? ['--', '--base=./'] : [])], label: `npm run build${viteLast ? ' -- --base=./' : ''}` }
  if (viteLast && segs.length > 1) {
    const extra = last.replace(/^vite\s+build/, '').trim().split(/\s+/).filter(Boolean)
    plan.fallback = { cmd: 'vite', args: ['build', ...extra, '--base=./'], label: `vite build ${[...extra, '--base=./'].join(' ')}`, note: `build 脚本里 vite build 之前的步骤（${segs.slice(0, -1).join(' && ')}）失败，已跳过这些步骤直接执行 vite build。常见原因是类型检查报错，建议之后修复` }
  }
  return plan
}

export function findOutDir(cwd, since, preferred = []) {
  const list = [...preferred, ...OUT_DIRS]
  const distDir = path.join(cwd, 'dist')
  if (existsSync(distDir) && lstatSync(distDir).isDirectory()) {
    for (const name of readdirSync(distDir)) { list.push(`dist/${name}/browser`, `dist/${name}`) }
  }
  for (const rel of [...new Set(list)]) {
    const abs = path.resolve(cwd, rel)
    const r = path.relative(cwd, abs)
    if (!r || r.startsWith('..') || path.isAbsolute(r)) continue
    const idx = path.join(abs, 'index.html')
    if (existsSync(idx) && lstatSync(idx).isFile() && lstatSync(idx).mtimeMs >= since - 2000) return r
  }
  return null
}

export function absoluteAssetRefs(html) {
  return /(?:src|href)\s*=\s*["']\/(?!\/)[^"']+\.(?:js|mjs|css)(?:[?#][^"']*)?["']/i.test(html)
}

const POSTCSS_CONFIGS = [
  '.postcssrc', '.postcssrc.json', '.postcssrc.yaml', '.postcssrc.yml', '.postcssrc.js', '.postcssrc.cjs', '.postcssrc.mjs', '.postcssrc.ts',
  'postcss.config.js', 'postcss.config.cjs', 'postcss.config.mjs', 'postcss.config.ts', 'postcss.config.cts', 'postcss.config.mts',
]

export function hasLocalPostcssConfig(cwd, pkg) {
  if (pkg && typeof pkg === 'object' && Object.prototype.hasOwnProperty.call(pkg, 'postcss')) return true
  return POSTCSS_CONFIGS.some((f) => existsSync(path.join(cwd, f)))
}

async function verifyVite(cwd, env) {
  const checks = []
  const notes = []
  const vOut = viteOutDir(cwd)
  let outDir = null
  const build = await step('构建', async () => {
    if (!existsSync(path.join(cwd, 'node_modules'))) return { ok: false, detail: '依赖还没有安装（请先执行 install）' }
    let pkg = {}
    try { pkg = JSON.parse(readFileSync(path.join(cwd, 'package.json'), 'utf8')) } catch { return { ok: false, detail: 'package.json 格式不正确' } }
    const vite = viteBin(cwd)
    const plan = buildPlan(pkg, !!vite)
    if (!plan) return { ok: false, detail: 'package.json 里没有 build 脚本，也找不到 vite' }
    if (vite) {
      const abs = path.resolve(cwd, vOut)
      const rel = path.relative(cwd, abs)
      if (!rel || rel.startsWith('..') || path.isAbsolute(rel)) return { ok: false, detail: `vite 配置的 outDir 不在项目目录内：${vOut}` }
      rmSync(abs, { recursive: true, force: true })
    }
    const existed = new Set(OUT_DIRS.filter((d) => existsSync(path.join(cwd, d))))
    const benv = buildEnv(env, {
      npm_config_update_notifier: 'false', CI: 'false', PUBLIC_URL: '.', GENERATE_SOURCEMAP: 'false', NG_CLI_ANALYTICS: 'false', BROWSER: 'none',
      NODE_OPTIONS: env.NODE_OPTIONS || '--max-old-space-size=4096',
    })
    const exec = (p) => {
      emitLog(`$ ${p.label}`)
      return p.cmd === 'vite' ? run(process.execPath, [vite, ...p.args], { cwd, env: benv, timeoutMs: 300_000, onLine: emitLog })
        : run(p.cmd, p.args, { cwd, env: benv, timeoutMs: 300_000, onLine: emitLog })
    }
    // 沙箱禁止访问工作区之外的父目录；未配置 PostCSS 的 Vite 项目在构建时会向上搜索父级 package.json 触发 EPERM，
    // 临时放一个空的 .postcssrc.json 截断向上查找，构建结束立即删除不污染项目仓库。
    const tempPostcss = !hasLocalPostcssConfig(cwd, pkg) ? path.join(cwd, '.postcssrc.json') : null
    if (tempPostcss) writeFileSync(tempPostcss, '{}\n', 'utf8')
    const since = Date.now()
    let r
    try {
      r = await exec(plan)
      if (r.code !== 0 && !r.timedOut && plan.fallback) {
        const first = r.out
        r = await exec(plan.fallback)
        if (r.code === 0) notes.push(plan.fallback.note)
        else r = { ...r, out: `${first}\n\n--- 跳过前置步骤直接 vite build 也失败了 ---\n${r.out}` }
      }
    } finally {
      if (tempPostcss) rmSync(tempPostcss, { force: true })
    }
    if (r.timedOut) return { ok: false, detail: `构建超时（5 分钟）\n${r.out}` }
    if (r.code !== 0) return { ok: false, detail: `构建失败（退出码 ${r.code}）：\n${r.out}` }
    outDir = findOutDir(cwd, since, vite ? [vOut] : [])
    if (!outDir) return { ok: false, detail: `构建完成，但没有找到新生成的 index.html（查找了 ${[...new Set([...(vite ? [vOut] : []), ...OUT_DIRS])].join('/、')}/）。如果产物在别的目录，请把 index.html 输出到 dist/\n${r.out}` }
    const html = readFileSync(path.join(cwd, outDir, 'index.html'), 'utf8')
    if (absoluteAssetRefs(html)) notes.push('构建产物用 / 开头的路径引用脚本或样式：发布后的站点正常，工作台预览里可能加载不到（把构建配置的 publicPath / base 改成 \'./\' 可以解决）')
    for (const d of [PREVIEW_DIR, PUBLISH_DIR]) { const dst = cleanDir(cwd, d); cpSync(path.join(cwd, outDir), dst, { recursive: true }) }
    const top = outDir.split(path.sep)[0]
    if (OUT_DIRS.includes(top) && !existed.has(top)) rmSync(path.join(cwd, top), { recursive: true, force: true })
    return { ok: true, detail: '' }
  })
  checks.push(build)
  checks.push(build.ok ? await step('冒烟测试', () => smokeTest(path.join(cwd, PREVIEW_DIR), { chromePath: env.ZHIYE_CHROME_PATH })) : { name: '冒烟测试', ok: false, skipped: true, detail: SKIPPED })
  for (const n of notes) emitLog(`提示：${n}`)
  flushLogs()
  return { checks, editable: false, notes }
}

/** 脚本项目：根目录或 scripts/ 下至少有一个能跑的脚本 */
async function checkHasScript(cwd) {
  const roots = [cwd, path.join(cwd, 'scripts')]
  const exts = ['.py', '.js', '.mjs', '.sh']
  for (const dir of roots) {
    let names = []
    try { names = await fsp.readdir(dir) } catch { continue }
    const hit = names.filter((n) => exts.some((e) => n.endsWith(e)))
    if (hit.length) return { ok: true, detail: hit.join('、') }
  }
  return { ok: false, detail: '没有找到脚本文件：请在根目录放 main.py（或 scripts/ 下的脚本）' }
}

async function checkReadme(cwd) {
  const p = path.join(cwd, 'README.md')
  const text = await fsp.readFile(p, 'utf8').catch(() => null)
  if (text === null) return { ok: false, detail: '缺少 README.md：需要说明脚本做什么、怎么运行、参数和输入输出' }
  if (text.trim().length < 20) return { ok: false, detail: 'README.md 内容太少：请写清楚用途、运行方式和参数' }
  return { ok: true, detail: '' }
}

function realCwd(cwd) { try { return realpathSync(cwd) } catch { return cwd } }

function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer()
    srv.once('error', reject)
    srv.listen(0, '127.0.0.1', () => {
      const addr = srv.address()
      const p = typeof addr === 'object' && addr ? addr.port : 0
      srv.close((err) => (err ? reject(err) : resolve(p)))
    })
  })
}

function httpProbe(url, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { if (body.length < 2048) body += c })
      res.on('end', () => resolve({ ok: true, status: res.statusCode || 0, body }))
    })
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, error: 'timeout' }) })
    req.on('error', (err) => resolve({ ok: false, error: err.message }))
  })
}

function detectNodeServiceEntry(cwd) {
  for (const rel of ['server/index.mjs', 'server/index.js']) {
    if (existsSync(path.join(cwd, rel))) return rel
  }
  const pkgPath = path.join(cwd, 'package.json')
  if (existsSync(pkgPath)) {
    try {
      const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))
      const start = String(pkg?.scripts?.start || '').trim()
      const m = /^node\s+(?:--[a-z0-9-]+\s+)*([^\s;|&'"]+)/i.exec(start)
      if (m && existsSync(path.join(cwd, m[1]))) return m[1]
    } catch {}
  }
  return null
}

/** 启动 Node/Express 全栈后端到临时端口，探活 /api/health，并在有 Chrome + dist 时检查首屏渲染。 */
export async function serviceLiveSmoke(cwd, env = process.env) {
  const entry = detectNodeServiceEntry(cwd)
  if (!entry) return { ok: true, skipped: true, detail: '未检测到本地 Node 服务端入口，跳过沙箱起服探活' }
  const pkgPath = path.join(cwd, 'package.json')
  if (existsSync(pkgPath) && !existsSync(path.join(cwd, 'node_modules'))) {
    return { ok: true, skipped: true, detail: '未安装本地 node_modules，跳过沙箱起服探活' }
  }
  const port = await findFreePort()
  const tmpData = mkdtempSync(path.join(os.tmpdir(), 'zhiye-svc-verify-'))
  let logs = ''
  const appendLog = (chunk) => {
    logs += String(chunk || '')
    if (logs.length > 8000) logs = logs.slice(-8000)
  }
  const child = spawn(process.execPath, [path.join(cwd, entry)], {
    cwd,
    env: { ...buildEnv(env), PORT: String(port), HOST: '127.0.0.1', DATA_DIR: tmpData },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let exited = null
  child.stdout?.on('data', appendLog)
  child.stderr?.on('data', appendLog)
  child.once('exit', (code, sig) => { exited = { code, sig } })

  const killChild = async () => {
    if (exited || child.killed) return
    try { child.kill('SIGTERM') } catch {}
    await new Promise((r) => setTimeout(r, 250))
    if (!exited) { try { child.kill('SIGKILL') } catch {} }
  }

  try {
    const deadline = Date.now() + 10_000
    let healthy = false
    let lastStatus = ''
    while (Date.now() < deadline) {
      if (exited) {
        return { ok: false, detail: `后端服务（${entry}）启动后立即退出（code=${exited.code ?? exited.sig}）：\n${logs.trim() || '（无输出）'}` }
      }
      const r = await httpProbe(`http://127.0.0.1:${port}/api/health`, 1500)
      if (r.ok) {
        if (r.status >= 200 && r.status < 300) { healthy = true; break }
        if (r.status === 404) {
          const r2 = await httpProbe(`http://127.0.0.1:${port}/`, 1500)
          if (r2.ok && r2.status >= 200 && r2.status < 400) { healthy = true; break }
          lastStatus = `GET /api/health → 404，GET / → ${r2.ok ? r2.status : r2.error}`
        } else {
          lastStatus = `GET /api/health 返回 HTTP ${r.status}：${r.body.slice(0, 300)}`
        }
      } else {
        lastStatus = r.error
      }
      await new Promise((r) => setTimeout(r, 200))
    }
    if (!healthy) {
      return { ok: false, detail: `后端服务未在 10 秒内通过健康检查（${lastStatus}）。\n最近输出：\n${logs.trim() || '（无输出）'}` }
    }

    // 有构建产物和无头浏览器时，顺带打开真实全栈首页检查白屏 / 脚本异常 / 5xx 接口错误，并抓 1920 截图
    const chromePath = env.ZHIYE_CHROME_PATH
    if (existsSync(path.join(cwd, 'dist', 'index.html')) && chromePath && existsSync(chromePath)) {
      const { chromium } = await import('playwright-core')
      const userDataDir = mkdtempSync(path.join(os.tmpdir(), 'zhiye-svc-chrome-'))
      let browser
      try {
        browser = await chromium.launch({
          executablePath: chromePath,
          headless: true,
          timeout: 25_000,
          args: ['--no-sandbox', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--use-mock-keychain', '--password-store=basic'],
          env: { ...process.env, HOME: process.env.HOME || userDataDir },
        })
        const origin = `http://127.0.0.1:${port}`
        const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, locale: 'zh-CN' })
        const page = await ctx.newPage()
        const errors = []
        await page.route('**/*', (route) => {
          const u = route.request().url()
          return u.startsWith(origin) || u.startsWith('data:') || u.startsWith('blob:') ? route.continue() : route.abort('blockedbyclient')
        })
        page.on('pageerror', (e) => errors.push(`页面脚本错误：${e.message}`))
        page.on('console', (m) => { if (m.type() === 'error' && !/ERR_BLOCKED_BY_CLIENT|favicon/.test(m.text())) errors.push(`控制台错误：${m.text()}`) })
        page.on('response', (res) => {
          const u = res.url()
          if (u.startsWith(origin) && res.status() >= 500) errors.push(`后端接口返回 HTTP ${res.status()}：${u.replace(origin, '')}`)
        })
        await page.goto(origin + '/', { waitUntil: 'load', timeout: 20_000 }).catch((e) => errors.push(`首页打开失败：${String(e.message).split('\n')[0]}`))
        await page.waitForTimeout(900)
        const state = await page.evaluate(() => {
          const root = document.getElementById('root') || document.getElementById('app')
          const text = (document.body?.innerText || '').trim()
          return { hasRoot: !!root, rootChildren: root ? root.childElementCount : 0, textLen: text.length }
        })
        if (state.hasRoot && state.rootChildren === 0 && state.textLen === 0) {
          errors.push('全栈首页是空白的：#root 未渲染出内容')
        }
        let screenshot
        if (errors.length === 0) {
          const buf = await page.screenshot({ type: 'jpeg', quality: 65 }).catch(() => null)
          if (buf) screenshot = buf.toString('base64')
        }
        if (errors.length) {
          return { ok: false, detail: `全栈首屏冒烟未通过：\n${errors.slice(0, 6).join('\n')}${logs.trim() ? `\n\n后端日志：\n${logs.trim().slice(-1500)}` : ''}` }
        }
        return { ok: true, detail: `后端 ${entry} 启动正常（/api/health 200），全栈首页渲染与接口均无报错`, ...(screenshot ? { screenshot } : {}) }
      } finally {
        if (browser) await browser.close().catch(() => {})
        try { rmSync(userDataDir, { recursive: true, force: true }) } catch {}
      }
    }
    return { ok: true, detail: `后端 ${entry} 启动正常（/api/health 200）` }
  } finally {
    await killChild()
    try { rmSync(tmpData, { recursive: true, force: true }) } catch {}
  }
}

async function runCustomStep(cwd, stepDef, bEnv) {
  const stepCwd = stepDef.cwd ? path.resolve(cwd, stepDef.cwd) : path.resolve(cwd)
  const rel = path.relative(path.resolve(cwd), stepCwd)
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    return { ok: false, detail: `zhiye.yaml 步骤工作目录越界：${stepDef.cwd}` }
  }
  const blocked = checkBash(stepCwd, stepDef.command, { projectKind: 'service' })
  if (blocked) return { ok: false, detail: `安全守卫拦截 zhiye.yaml 命令「${stepDef.command}」：${blocked}` }
  emitLog(`$ ${stepDef.command}`)
  const r = await run('sh', ['-c', stepDef.command], { cwd: stepCwd, env: bEnv, timeoutMs: stepDef.timeoutMs || 180_000, onLine: emitLog })
  if (r.timedOut) return { ok: false, detail: `命令执行超时：${stepDef.command}` }
  if (r.code !== 0) return { ok: false, detail: String(r.out || `命令退出码 ${r.code}`).trim().slice(-1500) }
  return { ok: true, detail: `${stepDef.command} 通过` }
}

async function verifyService(cwd, env) {
  const checks = []
  const bEnv = buildEnv(env)
  const layout = discoverWorkspaceLayout(cwd)
  const wf = layout.workflow

  // 0. 项目声明式工作流（zhiye.yaml）优先：完全按项目自己定义的 build / test / verify / start 执行，不写死任何框架
  const declaredSteps = [...(wf?.build || []), ...(wf?.test || []), ...(wf?.verify || [])]
  if (declaredSteps.length > 0 || wf?.start) {
    for (const s of declaredSteps) {
      const prevOk = checks.every((c) => c.ok)
      checks.push(prevOk
        ? await step(s.name, () => runCustomStep(cwd, s, bEnv))
        : { name: s.name, ok: false, skipped: true, detail: SKIPPED })
    }
    if (wf?.start || layout.backend) {
      const prevOk = checks.every((c) => c.ok)
      const healthPath = wf?.start?.health || '/api/health'
      checks.push(prevOk
        ? await step('服务启动与探活', async () => {
            const { startTempServiceServer } = await import('./endpoint-test.mjs')
            let srv
            try {
              srv = await startTempServiceServer(cwd, bEnv, { healthPath, startupTimeoutMs: wf?.start?.timeoutMs || 30_000 })
              return { ok: true, detail: `服务 (${srv.label}) 启动正常，${srv.healthPath || healthPath} 返回 ${srv.readyStatus || 200}` }
            } catch (e) {
              return { ok: false, detail: String(e?.message || e) }
            } finally {
              if (srv) await srv.close()
            }
          })
        : { name: '服务启动与探活', ok: false, skipped: true, detail: SKIPPED })
    }
    return { checks, editable: false, noDist: true }
  }

  // 1. 零配置动态结构发现：按真实目录（任意 frontend/web/client + 任意 Java/Go/Python/Rust/Node 后端）动态组合校验
  const frontSubDir = layout.frontendDir ? path.join(cwd, layout.frontendDir) : null
  const javaDir = layout.backend?.runtime === 'java-maven' ? (layout.backend.dir ? path.join(cwd, layout.backend.dir) : cwd) : null
  const gradleDir = layout.backend?.runtime === 'java-gradle' ? (layout.backend.dir ? path.join(cwd, layout.backend.dir) : cwd) : null
  const goDir = layout.backend?.runtime === 'go' ? (layout.backend.dir ? path.join(cwd, layout.backend.dir) : cwd) : null
  const pyDir = layout.backend?.runtime === 'python' ? (layout.backend.dir ? path.join(cwd, layout.backend.dir) : cwd) : null

  if (frontSubDir) {
    const vueTsc = path.join(frontSubDir, 'node_modules', 'vue-tsc', 'bin', 'vue-tsc.js')
    const tscBin = path.join(frontSubDir, 'node_modules', 'typescript', 'bin', 'tsc')
    const chosenBin = existsSync(vueTsc) ? vueTsc : existsSync(tscBin) ? tscBin : null
    if (existsSync(path.join(frontSubDir, 'tsconfig.json')) && chosenBin) {
      checks.push(await step('前端类型检查', async () => {
        const r = await run(process.execPath, [chosenBin, '--noEmit', '-p', 'tsconfig.json', '--pretty', 'false'], { cwd: frontSubDir, env: bEnv, timeoutMs: 120_000 })
        if (r.timedOut) return { ok: false, detail: '前端类型检查超时' }
        if (r.code !== 0) return { ok: false, detail: tscSummary(r.out) || `类型检查退出码 ${r.code}` }
        return { ok: true, detail: '' }
      }))
    }
    if (existsSync(path.join(frontSubDir, 'node_modules')) && viteBin(frontSubDir)) {
      checks.push(await step('前端构建', () => viteBuild(frontSubDir, bEnv, { outDir: 'dist' })))
    }
  }

  if (javaDir) {
    checks.push(await step('Spring Boot 离线编译打包', async () => {
      if (frontSubDir && existsSync(path.join(frontSubDir, 'dist', 'index.html'))) {
        const staticTarget = path.join(javaDir, 'src', 'main', 'resources', 'static')
        rmSync(staticTarget, { recursive: true, force: true })
        mkdirSync(staticTarget, { recursive: true })
        cpSync(path.join(frontSubDir, 'dist'), staticTarget, { recursive: true })
      }
      const mvnArgs = ['-B', '-o', '-DskipTests', '-Daether.syncContext.named.factory=noop']
      if (bEnv.ZHIYE_M2_REPO) {
        mvnArgs.push(`-Dmaven.repo.local=${bEnv.ZHIYE_M2_REPO}`, `-Dmaven.repo.local.tail=${bEnv.ZHIYE_M2_REPO}`)
      }
      mvnArgs.push('package')
      const r = await run('mvn', mvnArgs, { cwd: javaDir, env: bEnv, timeoutMs: 180_000 })
      if (r.timedOut) return { ok: false, detail: 'Maven 构建超时' }
      if (r.code !== 0) {
        const errs = String(r.out || '').split(/\r?\n/).filter((l) => l.includes('[ERROR]')).slice(0, 15).join('\n')
        return { ok: false, detail: errs || String(r.out || '').slice(-1500) }
      }
      return { ok: true, detail: 'mvn -B -o -DskipTests package 构建通过' }
    }))
    const prevOk = checks.every((c) => c.ok)
    checks.push(prevOk
      ? await step('Spring Boot 启动与探活', async () => {
          const { startTempServiceServer } = await import('./endpoint-test.mjs')
          let srv
          try {
            srv = await startTempServiceServer(cwd, bEnv, { healthPath: '/api/health', startupTimeoutMs: 45_000 })
            return { ok: true, detail: `Spring Boot (${srv.label}) 启动正常，/api/health 返回 200` }
          } catch (e) {
            return { ok: false, detail: String(e?.message || e) }
          } finally {
            if (srv) await srv.close()
          }
        })
      : { name: 'Spring Boot 启动与探活', ok: false, skipped: true, detail: SKIPPED })
    return { checks, editable: false, noDist: true }
  }

  if (gradleDir) {
    checks.push(await step('Gradle 离线编译打包', async () => {
      const cmd = existsSync(path.join(gradleDir, 'gradlew')) ? './gradlew' : 'gradle'
      const r = await run(cmd, ['--offline', 'build', '-x', 'test'], { cwd: gradleDir, env: bEnv, timeoutMs: 180_000 })
      if (r.timedOut) return { ok: false, detail: 'Gradle 构建超时' }
      if (r.code !== 0) return { ok: false, detail: String(r.out || `gradle exit=${r.code}`).slice(-1500) }
      return { ok: true, detail: `${cmd} build 构建通过` }
    }))
    const prevOk = checks.every((c) => c.ok)
    checks.push(prevOk
      ? await step('Java 服务启动与探活', async () => {
          const { startTempServiceServer } = await import('./endpoint-test.mjs')
          let srv
          try {
            srv = await startTempServiceServer(cwd, bEnv, { healthPath: '/api/health', startupTimeoutMs: 45_000 })
            return { ok: true, detail: `Java 服务 (${srv.label}) 启动正常，/api/health 返回 200` }
          } catch (e) {
            return { ok: false, detail: String(e?.message || e) }
          } finally {
            if (srv) await srv.close()
          }
        })
      : { name: 'Java 服务启动与探活', ok: false, skipped: true, detail: SKIPPED })
    return { checks, editable: false, noDist: true }
  }

  if (goDir) {
    checks.push(await step('Go 编译构建', async () => {
      const target = detectGoBuildTarget(goDir)
      const r = await run('go', ['build', '-o', 'app', target], { cwd: goDir, env: bEnv, timeoutMs: 120_000 })
      if (r.timedOut) return { ok: false, detail: 'go build 超时' }
      if (r.code !== 0) return { ok: false, detail: String(r.out || `go build exit=${r.code}`).slice(-1200) }
      return { ok: true, detail: `go build -o app ${target} 编译通过` }
    }))
    const prevOk = checks.every((c) => c.ok)
    checks.push(prevOk
      ? await step('Go 服务启动与探活', async () => {
          const { startTempServiceServer } = await import('./endpoint-test.mjs')
          let srv
          try {
            srv = await startTempServiceServer(cwd, bEnv, { healthPath: '/api/health', startupTimeoutMs: 15_000 })
            return { ok: true, detail: `Go 服务 (${srv.label}) 启动正常，/api/health 返回 200` }
          } catch (e) {
            return { ok: false, detail: String(e?.message || e) }
          } finally {
            if (srv) await srv.close()
          }
        })
      : { name: 'Go 服务启动与探活', ok: false, skipped: true, detail: SKIPPED })
    return { checks, editable: false, noDist: true }
  }

  if (pyDir) {
    checks.push(await step('Python 语法检查', () => checkScripts(cwd, env)))
    const prevOk = checks.every((c) => c.ok)
    checks.push(prevOk
      ? await step('Python 服务启动与探活', async () => {
          const { startTempServiceServer } = await import('./endpoint-test.mjs')
          let srv
          try {
            srv = await startTempServiceServer(cwd, bEnv, { healthPath: '/api/health', startupTimeoutMs: 20_000 })
            return { ok: true, detail: `Python 服务 (${srv.label}) 启动正常，/api/health 返回 ${srv.readyStatus || 200}` }
          } catch (e) {
            return { ok: false, detail: String(e?.message || e) }
          } finally {
            if (srv) await srv.close()
          }
        })
      : { name: 'Python 服务启动与探活', ok: false, skipped: true, detail: SKIPPED })
    return { checks, editable: false, noDist: true }
  }

  // 默认：Node.js 全栈服务校验（支持根目录或任意后端子目录）
  checks.push(await step('脚本语法', () => checkScripts(cwd, env)))
  const tscBin = path.join(cwd, 'node_modules', 'typescript', 'bin', 'tsc')
  if (existsSync(path.join(cwd, 'tsconfig.json')) && existsSync(tscBin)) {
    checks.push(await step('类型检查', async () => {
      const r = await run(process.execPath, [tscBin, '--noEmit', '-p', 'tsconfig.json', '--pretty', 'false'], { cwd, env: bEnv, timeoutMs: 120_000 })
      if (r.timedOut) return { ok: false, detail: '类型检查超时' }
      if (r.code !== 0) return { ok: false, detail: tscSummary(r.out) || `tsc 退出码 ${r.code}` }
      return { ok: true, detail: '' }
    }))
  }
  const hasFrontend = existsSync(path.join(cwd, 'package.json')) && existsSync(path.join(cwd, 'node_modules')) && viteBin(cwd)
  if (hasFrontend) {
    checks.push(await step('前端构建', () => viteBuild(cwd, bEnv, { outDir: 'dist' })))
  }
  const prevOk = checks.every((c) => c.ok)
  checks.push(prevOk
    ? await step('服务启动与探活', () => serviceLiveSmoke(cwd, env))
    : { name: '服务启动与探活', ok: false, skipped: true, detail: SKIPPED })
  return { checks, editable: false, noDist: true }
}

export async function verify(cwd, { projectKind } = {}, env = process.env) {
  const kind = projectKind || 'template'
  let r
  if (kind === 'template') r = await verifyTemplate(cwd, env)
  else if (kind === 'static') r = await verifyStatic(cwd, env)
  else if (kind === 'vite') r = await verifyVite(cwd, env)
  else if (kind === 'vue') r = await verifyVue(cwd, env)
  else if (kind === 'script') {
    r = { checks: [
      await step('脚本文件', async () => checkHasScript(cwd)),
      await step('README', async () => checkReadme(cwd)),
      await step('脚本语法', () => checkScripts(cwd, env)),
    ], editable: false, noDist: true }
  }
  else if (kind === 'skill') {
    r = { checks: [
      await step('SKILL.md 格式', async () => checkSkillMd(cwd)),
      await step('引用文件', async () => checkReferences(cwd)),
      await step('脚本语法', () => checkScripts(cwd, env)),
      await step('安全与文件清理', async () => checkSecurityAndCleanliness(cwd)),
    ], editable: false, noDist: true }
  }
  else if (kind === 'service') {
    r = await verifyService(cwd, env)
  }
  else throw new RunnerError('BAD_REQUEST', `未知的项目类型：${kind}`)
  // 非 service 项目如果也在 zhiye.yaml 中声明了自定义 verify/test 步骤，同样追加执行（不写死校验流）
  if (kind !== 'service') {
    const wf = loadProjectWorkflow(cwd)
    const extraSteps = [...(wf?.test || []), ...(wf?.verify || [])]
    if (extraSteps.length) {
      const bEnv = buildEnv(env)
      for (const s of extraSteps) {
        const prevOk = r.checks.every((c) => c.ok)
        r.checks.push(prevOk
          ? await step(s.name, () => runCustomStep(cwd, s, bEnv))
          : { name: s.name, ok: false, skipped: true, detail: SKIPPED })
      }
    }
  }
  // 报错里的绝对路径换成相对工作区的路径，便于 agent 阅读
  const strip = (d) => [cwd, realCwd(cwd)].reduce((acc, p) => acc.split(p + '/').join(''), String(d || ''))
  const checks = r.checks.map(({ name, ok, detail, skipped, durationMs, screenshot }) => ({
    name, ok, detail: strip(detail),
    ...(skipped ? { skipped: true } : {}),
    ...(durationMs !== undefined ? { durationMs } : {}),
    ...(screenshot ? { screenshot } : {}),
  }))
  return { ok: checks.every((c) => c.ok), checks, previewDir: r.noDist ? null : PREVIEW_DIR, publishDir: r.noDist ? null : PUBLISH_DIR, editable: r.editable, ...(r.notes?.length ? { notes: r.notes } : {}) }
}
