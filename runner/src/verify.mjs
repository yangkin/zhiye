// verify：类型检查、两套构建、冒烟测试。
import { realpathSync, existsSync, mkdirSync, rmSync, readdirSync, lstatSync, copyFileSync, readFileSync, writeFileSync, cpSync, promises as fsp } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { run, emitLog, flushLogs, summarize, log, RunnerError } from './util.mjs'
import { smokeTest } from './smoke.mjs'
import { checkSkillMd, checkReferences, checkScripts } from './skill.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
export const PREVIEW_DIR = '.zhiye/dist-preview'
export const PUBLISH_DIR = '.zhiye/dist-publish'
const SKIPPED = '前面的检查没有通过，已跳过'

export function locPluginPath(env = process.env) {
  return env.ZHIYE_LOC_PLUGIN || path.resolve(here, '../../packages/vite-plugin-loc/index.mjs')
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
  const first = rel.split(path.sep)[0]
  return first === '.git' || first === '.zhiye' || first === 'node_modules' || rel.split(path.sep).includes('node_modules') || rel.split(path.sep).includes('.git')
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
        if (/\b(BrowserRouter|createBrowserRouter)\b/.test(src)) problems.push(`${path.relative(cwd, abs)}：预览和发布地址可能在子路径下，路由请改用 HashRouter（或 createHashRouter），不要用 BrowserRouter`)
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
        if (/\bcreateWebHistory\b/.test(src)) problems.push(`${path.relative(cwd, abs)}：预览和发布地址可能在子路径下，路由请改用 createWebHashHistory，不要用 createWebHistory`)
        if (/from\s+['"](react|react-dom|lucide-react|recharts|react-router)['"]/.test(src)) problems.push(`${path.relative(cwd, abs)}：这是 Vue 项目，不能引入 React 相关的库`)
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
    const { annotateHtml } = await import(pathToFileURL(locPluginPath(env)).href)
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
    const since = Date.now()
    let r = await exec(plan)
    if (r.code !== 0 && !r.timedOut && plan.fallback) {
      const first = r.out
      r = await exec(plan.fallback)
      if (r.code === 0) notes.push(plan.fallback.note)
      else r = { ...r, out: `${first}\n\n--- 跳过前置步骤直接 vite build 也失败了 ---\n${r.out}` }
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
    ], editable: false, noDist: true }
  }
  else throw new RunnerError('BAD_REQUEST', `未知的项目类型：${kind}`)
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
