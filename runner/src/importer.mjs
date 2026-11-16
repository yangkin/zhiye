// 导入：安全解包 ZIP / 单个 HTML，识别框架；安装依赖。
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, lstatSync, statSync, renameSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { Inflate } from 'fflate'
import { RunnerError, run, emitLog, flushLogs, summarize, log } from './util.mjs'

export const LIMITS = { totalBytes: 200 * 1024 * 1024, files: 5000, fileBytes: 20 * 1024 * 1024 }
const SKIP_DIRS = new Set(['node_modules', '.git', '__MACOSX'])
const SKIP_FILES = new Set(['.DS_Store', 'Thumbs.db'])

const MB = (n) => `${Math.round(n / 1024 / 1024)} MB`

function decodeName(bytes, utf8Flag) {
  if (utf8Flag) return new TextDecoder('utf-8').decode(bytes)
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes) } catch {}
  try { return new TextDecoder('gbk').decode(bytes) } catch {}
  return new TextDecoder('latin1').decode(bytes)
}

/** 读取 ZIP 中央目录。返回条目列表（不解压）。 */
export function readZipEntries(buf) {
  if (buf.length < 22) throw new RunnerError('IMPORT_INVALID', '不是有效的 ZIP 文件')
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new RunnerError('IMPORT_INVALID', '不是有效的 ZIP 文件')
  const count = buf.readUInt16LE(eocd + 10)
  const cdSize = buf.readUInt32LE(eocd + 12)
  const cdOffset = buf.readUInt32LE(eocd + 16)
  if (count === 0xffff || cdOffset === 0xffffffff) throw new RunnerError('IMPORT_INVALID', '不支持 ZIP64 格式的压缩包，请重新压缩后上传')
  if (cdOffset + cdSize > buf.length) throw new RunnerError('IMPORT_INVALID', 'ZIP 文件已损坏')
  const entries = []
  let p = cdOffset
  for (let i = 0; i < count; i++) {
    if (p + 46 > buf.length || buf.readUInt32LE(p) !== 0x02014b50) throw new RunnerError('IMPORT_INVALID', 'ZIP 文件已损坏')
    const madeBy = buf.readUInt16LE(p + 4) >> 8
    const flags = buf.readUInt16LE(p + 8)
    const method = buf.readUInt16LE(p + 10)
    const compSize = buf.readUInt32LE(p + 20)
    const size = buf.readUInt32LE(p + 24)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const extAttr = buf.readUInt32LE(p + 38)
    const localOffset = buf.readUInt32LE(p + 42)
    const name = decodeName(buf.subarray(p + 46, p + 46 + nameLen), (flags & 0x800) !== 0)
    const mode = madeBy === 3 /* unix */ ? (extAttr >>> 16) & 0xffff : 0
    entries.push({
      name,
      flags,
      method,
      compSize,
      size,
      localOffset,
      isDir: name.endsWith('/') || name.endsWith('\\') || (madeBy === 0 && (extAttr & 0x10) !== 0),
      isSymlink: (mode & 0xf000) === 0xa000,
      encrypted: (flags & 1) !== 0,
    })
    p += 46 + nameLen + extraLen + commentLen
  }
  return entries
}

/** 校验条目名，返回规范化后的相对路径（/ 分隔）或抛错。 */
export function safeEntryPath(name) {
  const n = name.replace(/\\/g, '/')
  if (n.startsWith('/') || /^[A-Za-z]:/.test(n)) throw new RunnerError('IMPORT_UNSAFE', `压缩包里有绝对路径的文件，已拒绝：${name}`)
  const segs = n.split('/').filter((s) => s !== '' && s !== '.')
  if (segs.some((s) => s === '..')) throw new RunnerError('IMPORT_UNSAFE', `压缩包里有指向上级目录的文件（${name}），已拒绝`)
  if (segs.some((s) => /[\x00-\x1f]/.test(s))) throw new RunnerError('IMPORT_UNSAFE', `文件名包含非法字符：${JSON.stringify(name)}`)
  return segs.join('/')
}

function shouldSkip(rel) {
  const segs = rel.split('/')
  if (segs.some((s) => SKIP_DIRS.has(s))) return true
  if (SKIP_FILES.has(segs[segs.length - 1])) return true
  if (segs[segs.length - 1].startsWith('._') && segs.includes('__MACOSX')) return true
  return false
}

/** 解压一个条目，边解压边计数，超限立即停止。 */
function inflateEntry(buf, e, budget) {
  const lp = e.localOffset
  if (lp + 30 > buf.length || buf.readUInt32LE(lp) !== 0x04034b50) throw new RunnerError('IMPORT_INVALID', 'ZIP 文件已损坏')
  const start = lp + 30 + buf.readUInt16LE(lp + 26) + buf.readUInt16LE(lp + 28)
  const data = buf.subarray(start, start + e.compSize)
  if (data.length !== e.compSize) throw new RunnerError('IMPORT_INVALID', 'ZIP 文件已损坏')
  if (e.method === 0) {
    if (e.compSize > LIMITS.fileBytes) throw new RunnerError('IMPORT_TOO_LARGE', `单个文件不能超过 ${MB(LIMITS.fileBytes)}：${e.name}`)
    budget.used += e.compSize
    if (budget.used > LIMITS.totalBytes) throw new RunnerError('IMPORT_TOO_LARGE', `解压后总大小不能超过 ${MB(LIMITS.totalBytes)}`)
    return Buffer.from(data)
  }
  if (e.method !== 8) throw new RunnerError('IMPORT_INVALID', `不支持的压缩方式（${e.method}）：${e.name}，请用普通 ZIP 重新压缩`)
  const chunks = []
  let got = 0
  let err = null
  const inf = new Inflate((chunk) => {
    if (err) return
    got += chunk.length
    budget.used += chunk.length
    if (got > LIMITS.fileBytes) err = new RunnerError('IMPORT_TOO_LARGE', `单个文件不能超过 ${MB(LIMITS.fileBytes)}：${e.name}`)
    else if (budget.used > LIMITS.totalBytes) err = new RunnerError('IMPORT_TOO_LARGE', `解压后总大小不能超过 ${MB(LIMITS.totalBytes)}`)
    else chunks.push(chunk)
  })
  const STEP = 64 * 1024
  try {
    for (let i = 0; i < data.length && !err; i += STEP) inf.push(data.subarray(i, Math.min(data.length, i + STEP)), i + STEP >= data.length)
    if (!data.length) inf.push(new Uint8Array(0), true)
  } catch (e2) {
    if (!err) throw new RunnerError('IMPORT_INVALID', `文件解压失败：${e.name}`)
  }
  if (err) throw err
  return Buffer.concat(chunks)
}

/** 在内存里校验并解压 ZIP，返回 [{ path, data }]（已去掉统一的顶层目录）。 */
export function extractZip(buf) {
  const entries = readZipEntries(buf)
  const files = []
  let declared = 0
  for (const e of entries) {
    const rel = safeEntryPath(e.name)
    if (e.isSymlink) throw new RunnerError('IMPORT_UNSAFE', `压缩包里有符号链接（${e.name}），已拒绝`)
    if (e.isDir || !rel) continue
    if (shouldSkip(rel)) continue
    if (e.encrypted) throw new RunnerError('IMPORT_INVALID', `不支持加密的压缩包：${e.name}`)
    if (e.size > LIMITS.fileBytes) throw new RunnerError('IMPORT_TOO_LARGE', `单个文件不能超过 ${MB(LIMITS.fileBytes)}：${rel}`)
    declared += e.size
    if (declared > LIMITS.totalBytes) throw new RunnerError('IMPORT_TOO_LARGE', `解压后总大小不能超过 ${MB(LIMITS.totalBytes)}`)
    files.push({ rel, e })
    if (files.length > LIMITS.files) throw new RunnerError('IMPORT_TOO_LARGE', `文件数量不能超过 ${LIMITS.files} 个`)
  }
  if (!files.length) throw new RunnerError('IMPORT_INVALID', '压缩包里没有可导入的文件')
  // 大小写不敏感的重复路径（macOS 文件系统）
  const seen = new Set()
  for (const f of files) {
    const k = f.rel.toLowerCase()
    if (seen.has(k)) throw new RunnerError('IMPORT_INVALID', `压缩包里有重复的文件：${f.rel}`)
    seen.add(k)
  }
  const budget = { used: 0 }
  const out = files.map((f) => ({ path: f.rel, data: inflateEntry(buf, f.e, budget) }))
  // 所有文件都在同一个顶层目录下时去掉这一层
  const first = out[0].path.split('/')[0]
  if (out.every((f) => f.path.includes('/') && f.path.split('/')[0] === first)) {
    for (const f of out) f.path = f.path.slice(first.length + 1)
  }
  return out
}

function writeFiles(cwd, files) {
  const root = path.resolve(cwd)
  let totalSize = 0
  for (const f of files) {
    const abs = path.resolve(root, f.path)
    const r = path.relative(root, abs)
    if (!r || r.startsWith('..') || path.isAbsolute(r)) throw new RunnerError('IMPORT_UNSAFE', `非法路径：${f.path}`)
    // 父目录不能是符号链接（工作区一开始只有 .git，这里是防御）
    let dir = path.dirname(abs)
    mkdirSync(dir, { recursive: true })
    while (dir !== root) {
      if (lstatSync(dir).isSymbolicLink()) throw new RunnerError('IMPORT_UNSAFE', `非法路径：${f.path}`)
      dir = path.dirname(dir)
    }
    if (existsSync(abs) && lstatSync(abs).isSymbolicLink()) throw new RunnerError('IMPORT_UNSAFE', `非法路径：${f.path}`)
    writeFileSync(abs, f.data, { flag: 'w' })
    totalSize += f.data.length
  }
  return totalSize
}

/** 列出工作区里的项目文件（相对路径），跳过 .git / .zhiye / node_modules。 */
export function listProjectFiles(cwd, sub = '') {
  const out = []
  const dir = path.join(cwd, sub)
  for (const name of readdirSync(dir)) {
    if (!sub && (name === '.git' || name === '.zhiye' || name === 'node_modules')) continue
    if (name === 'node_modules' || name === '.git') continue
    const rel = sub ? `${sub}/${name}` : name
    const st = lstatSync(path.join(cwd, rel))
    if (st.isSymbolicLink()) continue
    if (st.isDirectory()) out.push(...listProjectFiles(cwd, rel))
    else if (st.isFile()) out.push(rel)
  }
  return out
}

const UNSUPPORTED = (fw, why = '需要服务端运行环境') => `暂不支持部署 ${fw} 项目：${why}`

const BUILD_TOOLS = [
  ['vite', 'Vite'], ['@vue/cli-service', 'Vue CLI'], ['react-scripts', 'Create React App'], ['@craco/craco', 'CRACO'],
  ['umi', 'Umi'], ['@umijs/max', 'Umi'], ['@angular/cli', 'Angular CLI'], ['@rsbuild/core', 'Rsbuild'], ['@rspack/cli', 'Rspack'],
  ['parcel', 'Parcel'], ['webpack', 'Webpack'],
]
const UI_LIBS = [['vue', 'Vue'], ['react', 'React'], ['@angular/core', 'Angular'], ['svelte', 'Svelte'], ['solid-js', 'Solid'], ['preact', 'Preact']]
const SERVER_LIBS = ['express', 'koa', '@nestjs/core', 'fastify', 'egg', '@midwayjs/core', 'hapi', '@hapi/hapi']

/**
 * 识别项目类型（与服务端 ProjectAnalyzer 的规则一致）。files 是相对路径列表，readText 读取文件。
 * projectKind：static（直接部署文件）/ vite（安装依赖后执行 build 脚本，历史原因沿用这个名字，不限于 Vite）。
 */
export function detectFramework(files, readText) {
  const has = (f) => files.includes(f)
  const result = (framework, projectKind, unsupportedReason = null, extra = {}) => ({ framework, deployable: !unsupportedReason, unsupportedReason, projectKind: unsupportedReason ? null : projectKind, ...extra })
  const htmlLabel = () => (files.filter((f) => /\.html?$/i.test(f)).length === 1 ? 'HTML' : '静态站点')
  if (has('package.json')) {
    let pkg = null
    try { pkg = JSON.parse(readText('package.json')) } catch {}
    if (!pkg || typeof pkg !== 'object') return result('未知', null, 'package.json 格式不正确，无法识别项目类型')
    const deps = { ...(pkg.peerDependencies || {}), ...(pkg.devDependencies || {}), ...(pkg.dependencies || {}) }
    const d = (n) => Object.prototype.hasOwnProperty.call(deps, n)
    const build = String(pkg.scripts?.build || '')
    if (d('next')) {
      const cfg = ['next.config.js', 'next.config.mjs', 'next.config.ts', 'next.config.cjs'].filter(has).map(readText).join('\n')
      if (/output\s*:\s*['"]export['"]/.test(cfg) || /next export/.test(build)) return result('Next.js（静态导出）', 'vite', null, { buildTool: 'next' })
      return result('Next.js', null, UNSUPPORTED('Next.js', "需要服务端运行环境。如果项目可以静态导出（next.config 里 output: 'export'），请开启后重新导入"))
    }
    if (d('nuxt') || d('nuxt3')) return result('Nuxt.js', null, UNSUPPORTED('Nuxt.js', '需要服务端运行环境。如果项目可以 nuxt generate 生成静态站点，请上传生成后的 .output/public 目录'))
    if (d('@tarojs/taro') || d('@dcloudio/uni-app')) return result(d('@tarojs/taro') ? 'Taro' : 'uni-app', null, '暂不支持部署小程序 / 跨端项目，请导入它构建出的 H5 产物（包含 index.html 的目录）')
    const ui = UI_LIBS.find(([n]) => d(n))?.[1] ?? null
    const tool = BUILD_TOOLS.find(([n]) => d(n))?.[1] ?? null
    if (!ui && !tool) {
      if (SERVER_LIBS.some(d)) return result('Node.js 服务端', null, UNSUPPORTED('Node.js 服务端'))
      if (has('index.html')) return result(htmlLabel(), 'static')
      return result('未知', null, '无法识别项目类型：package.json 里没有前端框架或构建工具，目录里也没有 index.html')
    }
    if (!build.trim() && tool !== 'Vite') {
      if (has('index.html') && !tool) return result(htmlLabel(), 'static')
      return result(ui || tool, null, `无法构建这个 ${ui || tool} 项目：package.json 里没有 build 脚本。请在 scripts 里添加 build 命令后重新导入`)
    }
    const framework = !tool ? ui : !ui || ['Angular CLI', 'Create React App', 'Umi'].includes(tool) ? tool : `${tool} + ${ui}`
    return result(framework, 'vite', null, { buildTool: tool || 'script' })
  }
  if (has('requirements.txt') || has('pyproject.toml') || has('manage.py')) return result('Python', null, UNSUPPORTED('Python'))
  if (has('go.mod')) return result('Go', null, UNSUPPORTED('Go'))
  if (has('pom.xml') || has('build.gradle') || has('build.gradle.kts')) return result('Java', null, UNSUPPORTED('Java'))
  if (has('composer.json')) return result('PHP', null, UNSUPPORTED('PHP'))
  if (has('index.html')) return result(htmlLabel(), 'static')
  return result('未知', null, '无法识别项目类型：根目录没有 index.html')
}

export function selectRoot(files, rootDir) {
  if (!rootDir) return files
  const dir = safeEntryPath(String(rootDir))
  if (!dir) return files
  const prefix = dir + '/'
  const out = files.filter((f) => f.path.startsWith(prefix)).map((f) => ({ ...f, path: f.path.slice(prefix.length) }))
  if (!out.length) throw new RunnerError('IMPORT_INVALID', `压缩包里没有 ${dir}/ 目录`)
  return out
}

/** import 命令 */
export function importArchive(cwd, { archivePath, format, folderName, rootDir } = {}) {
  if (!archivePath || typeof archivePath !== 'string' || !path.isAbsolute(archivePath)) throw new RunnerError('BAD_REQUEST', 'archivePath 必须是绝对路径')
  if (!existsSync(archivePath)) throw new RunnerError('IMPORT_INVALID', '找不到上传的文件')
  const existing = readdirSync(cwd).filter((n) => n !== '.git' && n !== '.zhiye')
  if (existing.length) throw new RunnerError('BAD_STATE', `工作区不是空的：${existing.slice(0, 5).join(', ')}`)
  const size = statSync(archivePath).size
  let files
  if (format === 'html') {
    if (size > LIMITS.fileBytes) throw new RunnerError('IMPORT_TOO_LARGE', `单个文件不能超过 ${MB(LIMITS.fileBytes)}`)
    const data = readFileSync(archivePath)
    if (!data.toString('utf8').trim()) throw new RunnerError('IMPORT_INVALID', 'HTML 文件是空的')
    files = [{ path: 'index.html', data }]
  } else if (format === 'zip') {
    if (size > 50 * 1024 * 1024) throw new RunnerError('IMPORT_TOO_LARGE', '压缩包不能超过 50 MB')
    files = selectRoot(extractZip(readFileSync(archivePath)), rootDir)
  } else throw new RunnerError('BAD_REQUEST', `format 只能是 zip 或 html：${format}`)

  // 根目录只有一个 html 且不叫 index.html 时，改名为 index.html
  const rootHtml = files.filter((f) => !f.path.includes('/') && /\.html?$/i.test(f.path))
  const hasPkg = files.some((f) => f.path === 'package.json')
  if (!hasPkg && rootHtml.length === 1 && rootHtml[0].path !== 'index.html' && !files.some((f) => f.path === 'index.html')) {
    if (files.filter((f) => /\.html?$/i.test(f.path)).length === 1) rootHtml[0].path = 'index.html'
  }
  const totalSize = writeFiles(cwd, files)
  const list = listProjectFiles(cwd)
  const det = detectFramework(list, (f) => readFileSync(path.join(cwd, f), 'utf8'))
  log(`import ${format} folder=${folderName ?? ''} root=${rootDir ?? ''} files=${list.length} size=${totalSize} → ${det.framework}`)
  return { ...det, fileCount: list.length, totalSize }
}

function onPath(bin, env) {
  for (const dir of String(env.PATH || '').split(path.delimiter)) {
    if (dir && existsSync(path.join(dir, bin))) return path.join(dir, bin)
  }
  return null
}

export function pickPackageManager(cwd, env = process.env) {
  const has = (f) => existsSync(path.join(cwd, f))
  if (has('package-lock.json') || has('npm-shrinkwrap.json')) return { pm: 'npm', lock: true }
  if (has('pnpm-lock.yaml')) return onPath('pnpm', env) ? { pm: 'pnpm', lock: true } : { pm: 'npm', lock: false, note: '项目使用 pnpm，平台没有 pnpm，改用 npm 按 package.json 安装，依赖版本可能和本地不完全一致' }
  if (has('yarn.lock')) return { pm: 'npm', lock: false, hideLock: 'yarn.lock', note: '项目使用 yarn，已改用 npm 按 package.json 安装（没有使用 yarn.lock），依赖版本可能和本地不完全一致' }
  return { pm: 'npm', lock: false }
}

const ERESOLVE = /ERESOLVE|peer dep|Could not resolve dependency|conflicting peer dependency/i
const LOCK_OUT_OF_SYNC = /can only install packages when your package\.json and package-lock\.json|lockfile.*(not up to date|outdated|incompatible)|ERR_PNPM_OUTDATED_LOCKFILE|ERR_PNPM_LOCKFILE|frozen-lockfile/i

/** install 命令（仅 vite 导入项目）。返回 { ok, notes, packageManager }，失败时带 log。 */
export async function installDeps(cwd, { env = process.env } = {}) {
  if (!existsSync(path.join(cwd, 'package.json'))) throw new RunnerError('BAD_STATE', '没有 package.json')
  const nm = path.join(cwd, 'node_modules')
  if (existsSync(nm) && lstatSync(nm).isSymbolicLink()) throw new RunnerError('BAD_STATE', 'node_modules 是共享依赖的符号链接，不能在这里安装')
  const choice = pickPackageManager(cwd, env)
  const notes = choice.note ? [choice.note] : []
  const runEnv = { ...env, NODE_ENV: 'development', npm_config_update_notifier: 'false', npm_config_fund: 'false', npm_config_audit: 'false', CI: '1' }
  const store = path.join(env.npm_config_cache ? path.dirname(env.npm_config_cache) : os.tmpdir(), 'zhiye-pnpm-store')
  const attempts = choice.pm === 'pnpm'
    ? [['pnpm', ['install', '--frozen-lockfile', '--ignore-scripts', '--prod=false', '--reporter=append-only', `--store-dir=${store}`]],
       ['pnpm', ['install', '--no-frozen-lockfile', '--ignore-scripts', '--prod=false', '--reporter=append-only', `--store-dir=${store}`], '锁文件和 package.json 不一致，已按 package.json 重新解析依赖']]
    : [['npm', [choice.lock ? 'ci' : 'install', '--include=dev', '--ignore-scripts', '--no-audit', '--no-fund', '--loglevel=warn', '--no-update-notifier']]]
  const hidden = choice.hideLock ? path.join(cwd, '.zhiye', choice.hideLock) : null
  if (hidden) { mkdirSync(path.dirname(hidden), { recursive: true }); renameSync(path.join(cwd, choice.hideLock), hidden) }
  try {
    let last = null
    for (let i = 0; i < attempts.length; i++) {
      const [bin, args, why] = attempts[i]
      if (why) notes.push(why)
      last = await runInstall(cwd, bin, args, runEnv)
      if (last.ok) {
        for (const n of notes) emitLog(`提示：${n}`)
        flushLogs()
        return { ok: true, notes, packageManager: bin }
      }
      if (last.timedOut) break
      if (bin === 'npm' && args[0] === 'ci' && LOCK_OUT_OF_SYNC.test(last.out)) attempts.push(['npm', ['install', ...args.slice(1)], 'package-lock.json 和 package.json 不一致，已改用 npm install'])
      else if (bin === 'npm' && ERESOLVE.test(last.out) && !args.includes('--legacy-peer-deps')) attempts.push(['npm', [...args, '--legacy-peer-deps'], '依赖的 peer 版本有冲突，已用 --legacy-peer-deps 安装（和 npm 6 的行为一致）'])
      else if (bin === 'pnpm' && i === 0 && !LOCK_OUT_OF_SYNC.test(last.out)) break
    }
    const head = last.timedOut ? '安装依赖超时（5 分钟）' : `安装依赖失败（${last.cmd} 退出码 ${last.code}）`
    return { ok: false, notes, log: summarize(`${head}\n${installHint(last.out)}${last.out}`) }
  } finally {
    if (hidden && existsSync(hidden)) renameSync(hidden, path.join(cwd, choice.hideLock))
  }
}

async function runInstall(cwd, bin, args, env) {
  const cmd = `${bin} ${args[0]}`
  emitLog(`$ ${bin} ${args.join(' ')}`)
  const t = Date.now()
  const r = await run(bin, args, { cwd, env, timeoutMs: 5 * 60_000, onLine: emitLog })
  flushLogs()
  log(`${cmd} exit=${r.code} in ${Date.now() - t}ms`)
  return { ok: r.code === 0 && !r.timedOut, code: r.code, timedOut: r.timedOut, out: r.out, cmd }
}

export function installHint(out) {
  if (/E404|404 Not Found|is not in this registry|ERR_PNPM_FETCH_404/i.test(out)) return '提示：有依赖在公共 npm 镜像里找不到，通常是公司私有包或已下架的包。\n'
  if (/ENOTFOUND|ECONNREFUSED|ETIMEDOUT|EAI_AGAIN|network/i.test(out)) return '提示：访问 npm 源失败，可能依赖了私有源地址或 Git 仓库地址。\n'
  if (/ERESOLVE/i.test(out)) return '提示：依赖版本互相冲突。\n'
  if (/EBADENGINE|Unsupported engine/i.test(out)) return '提示：依赖要求的 Node 版本和平台（Node 22）不一致。\n'
  return ''
}
