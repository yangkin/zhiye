// 安全守卫：在 Pi 执行工具之前检查调用，违规时返回中文原因（Pi 会把原因交回模型）。
// 这是第二道防线；第一道是 Seatbelt / 容器沙箱。
import path from 'node:path'
import { existsSync, realpathSync } from 'node:fs'

const LOCK_FILES = new Set(['package-lock.json', 'npm-shrinkwrap.json', 'pnpm-lock.yaml', 'yarn.lock', 'bun.lockb', 'bun.lock'])
const PROTECTED_DIRS = ['node_modules', '.git', '.zhiye', '.pi']
// 模板项目允许写入的位置：src/、public/、index.html，以及「开发skill」的 skill/、「技术文档」的 docs/
const TEMPLATE_DIRS = ['src', 'public', 'skill', 'docs']
// 云服务的 schema.sql 和 policies.json 允许写（只有 SQL / 规则，不能借 cloud/ 放服务端代码）
const CLOUD_SCHEMA = (rel) => rel === 'cloud/schema.sql' || rel === 'cloud/policies.json' || (rel.startsWith('cloud/') && rel.endsWith('.sql'))
// 云函数：functions/{name}.ts（单层，由平台的函数运行服务执行），以及类型定义和 tsconfig
const FUNCTIONS = (rel) => /^functions\/[a-z][a-z0-9_-]{0,47}\.ts$/.test(rel) || rel === 'functions/tsconfig.json' || rel === 'functions/zhiye-fn.d.ts'
const TEMPLATE_WRITABLE = (rel) => rel === 'index.html' || TEMPLATE_DIRS.some((d) => rel === d || rel.startsWith(d + '/')) || CLOUD_SCHEMA(rel) || FUNCTIONS(rel)

function isInside(root, p) {
  const rel = path.relative(root, p)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

/** 把工具里的路径解析成绝对路径（Pi 允许以 @ 开头、~ 开头）。 */
export function resolveToolPath(cwd, raw) {
  let p = String(raw ?? '').trim()
  if (p.startsWith('@')) p = p.slice(1)
  if (p === '~' || p.startsWith('~/')) return path.join('/__home__', p.slice(1)) // 视为工作区外
  return path.resolve(cwd, p)
}

/** 最近的已存在祖先目录的真实路径 + 余下部分（防止通过符号链接写到工作区外）。 */
function realResolve(abs) {
  let cur = abs
  const rest = []
  while (!existsSync(cur)) {
    const parent = path.dirname(cur)
    if (parent === cur) break
    rest.unshift(path.basename(cur))
    cur = parent
  }
  try { return path.join(realpathSync(cur), ...rest) } catch { return abs }
}

/** 检查写入目标，返回原因或 null。 */
const SCRIPT_WRITABLE = (rel) => {
  const segs = rel.split('/')
  // 脚本项目没有依赖管理：package.json / tsconfig 这类配置文件也不能写
  if (['package.json', 'package-lock.json', 'tsconfig.json', 'jsconfig.json'].includes(rel)) return false
  if (segs.length === 1) return ['scripts', 'examples', 'docs', 'tmp'].includes(rel) || /\.(py|js|mjs|cjs|sh|md|txt|json|csv)$/i.test(rel)
  return ['scripts', 'examples', 'docs', 'tmp'].includes(segs[0])
}

export function checkWritePath(cwd, raw, { projectKind = 'template' } = {}) {
  if (!raw || typeof raw !== 'string') return '缺少文件路径'
  const root = realResolve(path.resolve(cwd))
  const abs = resolveToolPath(cwd, raw)
  if (!isInside(path.resolve(cwd), abs)) return `不允许写工作区以外的路径：${raw}`
  const rel = path.relative(path.resolve(cwd), abs).split(path.sep).join('/')
  if (rel === '') return '不允许写工作区根目录本身'
  const segs = rel.split('/')
  for (const d of PROTECTED_DIRS) if (segs.includes(d)) return `不允许修改 ${d}/ 目录下的文件：${rel}`
  const base = segs[segs.length - 1]
  if (/^\.env/i.test(base)) return `不允许修改环境变量文件：${rel}`
  if (LOCK_FILES.has(base)) return `不允许修改锁文件：${rel}`
  const templated = projectKind === 'template' || projectKind === 'vue'
  if (templated && base === 'package.json') return '不允许修改 package.json：模板项目的依赖是固定的，请只使用已安装的依赖'
  if (templated && !TEMPLATE_WRITABLE(rel)) return `模板项目只能修改 src/、public/、index.html、skill/、docs/、cloud/schema.sql、cloud/policies.json、functions/*.ts：${rel}`
  // 脚本项目：根目录的脚本/README，加上 scripts/、examples/、docs/
  if (projectKind === 'script' && !SCRIPT_WRITABLE(rel)) return `脚本项目只能写根目录的脚本和 README，以及 scripts/、examples/、docs/：${rel}`
  // 符号链接逃逸
  const real = realResolve(abs)
  if (!isInside(root, real)) return `不允许通过符号链接写到工作区以外：${rel}`
  const realRel = path.relative(root, real).split(path.sep)
  for (const d of PROTECTED_DIRS) if (realRel.includes(d)) return `不允许修改 ${d}/ 目录下的文件：${rel}`
  return null
}

/** 检查读取目标（允许读 node_modules 等，用于查看类型定义）。 */
export function checkReadPath(cwd, raw, { agentDir } = {}) {
  if (!raw || typeof raw !== 'string') return '缺少文件路径'
  const abs = resolveToolPath(cwd, raw)
  // 织页放进 agent 目录的参考技能（{agentDir}/skills/**）只读开放
  if (agentDir && isInside(path.join(path.resolve(agentDir), 'skills'), abs) && !abs.split(path.sep).includes('..')) return null
  if (!isInside(path.resolve(cwd), abs)) return `不允许读取工作区以外的路径：${raw}`
  const rel = path.relative(path.resolve(cwd), abs).split(path.sep)
  const base = rel[rel.length - 1] || ''
  if (/^\.env/i.test(base)) return `不允许读取环境变量文件：${raw}`
  if (rel[0] === '.pi') return `不允许读取 .pi 目录：${raw}`
  return null
}

// ---------------- bash ----------------

/** 粗略的 shell 分词：按 ; && || | & 换行 $( ` 分段，每段再按空白拆词（处理引号）。 */
export function splitCommands(cmd) {
  const segments = []
  let cur = []
  let word = ''
  let hasWord = false
  let quote = null
  const pushWord = () => { if (hasWord) cur.push(word); word = ''; hasWord = false }
  const pushSeg = (sep) => { pushWord(); if (cur.length) segments.push({ words: cur, sep }); cur = [] }
  for (let i = 0; i < cmd.length; i++) {
    const c = cmd[i]
    if (quote) {
      if (c === quote) { quote = null; continue }
      if (c === '\\' && quote === '"' && i + 1 < cmd.length) { word += cmd[++i]; continue }
      if (quote === '"' && c === '$' && cmd[i + 1] === '(') { pushSeg('$(') ; i++; continue }
      if (quote === '"' && c === '`') { pushSeg('`'); continue }
      word += c; continue
    }
    if (c === "'" || c === '"') { quote = c; hasWord = true; continue }
    if (c === '\\' && i + 1 < cmd.length) { word += cmd[++i]; hasWord = true; continue }
    if (c === '#' && !hasWord && (i === 0 || /\s/.test(cmd[i - 1]))) { while (i < cmd.length && cmd[i] !== '\n') i++; pushSeg('\n'); continue }
    if (c === '\n' || c === ';') { pushSeg(c); continue }
    if (c === '&') {
      if (cmd[i + 1] === '&') { pushSeg('&&'); i++; continue }
      if (cmd[i - 1] === '>' || cmd[i + 1] === '>') { word += c; hasWord = true; continue } // 2>&1, &>
      pushSeg('&'); continue
    }
    if (c === '|') { if (cmd[i + 1] === '|') i++; pushSeg('|'); continue }
    if (c === '$' && cmd[i + 1] === '(') { pushSeg('$('); i++; continue }
    if (c === '`' || c === '(' || c === ')' || c === '{' && !hasWord || c === '}' && !hasWord) { pushSeg(c); continue }
    if (c === '>' || c === '<') {
      let op = c
      if (hasWord && /^(\d+|&)$/.test(word)) { op = word + c; word = ''; hasWord = false } else pushWord()
      while (cmd[i + 1] === '>' || cmd[i + 1] === '&' || cmd[i + 1] === '|') op += cmd[++i]
      if (op.endsWith('&')) while (/[\d-]/.test(cmd[i + 1] ?? '')) op += cmd[++i]
      cur.push(op); continue
    }
    if (/\s/.test(c)) { pushWord(); continue }
    word += c; hasWord = true
  }
  pushSeg('')
  return segments
}

const NET_CMDS = new Set(['curl', 'wget', 'nc', 'ncat', 'netcat', 'ssh', 'scp', 'sftp', 'rsync', 'ftp', 'telnet', 'aria2c', 'httpie', 'http', 'https'])
const PKG_MANAGERS = new Set(['pnpm', 'yarn', 'bun', 'bunx', 'pip', 'pip3', 'pipx', 'brew', 'apt', 'apt-get', 'yum', 'dnf', 'apk', 'gem', 'cargo', 'go', 'deno', 'corepack', 'volta', 'nvm'])
const PRIV_CMDS = new Set(['sudo', 'su', 'doas', 'chown', 'chroot', 'launchctl', 'systemctl', 'osascript', 'open'])
const SERVER_CMDS = new Set(['serve', 'http-server', 'live-server', 'nodemon', 'pm2', 'forever', 'nohup', 'screen', 'tmux', 'webpack-dev-server', 'next', 'nuxt'])
const GIT_READONLY = new Set(['status', 'diff', 'log', 'show', 'ls-files', 'grep', 'blame', 'rev-parse', 'branch', 'describe', 'shortlog', 'cat-file', 'ls-tree'])
const NPM_ALLOWED = new Set(['run', 'run-script', 'test', 't', 'ls', 'list', 'view', 'version', '-v', '--version', 'help', 'config', 'pkg', 'explain', 'why', 'outdated', 'prefix', 'root', 'bin'])
const DEV_SCRIPTS = new Set(['dev', 'start', 'serve', 'preview', 'watch'])
const WRAPPERS = new Set(['env', 'time', 'command', 'exec', 'xargs', 'nice', 'timeout', 'stdbuf', 'builtin'])

function stripEnvAssignments(words) {
  let i = 0
  while (i < words.length && /^[A-Za-z_][A-Za-z0-9_]*=/.test(words[i])) i++
  return words.slice(i)
}

function unwrap(words) {
  let w = stripEnvAssignments(words)
  for (let guard = 0; guard < 5 && w.length; guard++) {
    const name = path.basename(w[0])
    if (!WRAPPERS.has(name)) break
    w = w.slice(1)
    // 跳过 wrapper 的选项和参数（timeout 30 / env -i / xargs -n1）
    while (w.length && (w[0].startsWith('-') || (name === 'timeout' && /^\d/.test(w[0])))) w = w.slice(1)
    w = stripEnvAssignments(w)
  }
  return w
}

function checkRm(cwd, args) {
  const targets = args.filter((a) => !a.startsWith('-'))
  for (const t of targets) {
    if (t === '/' || t === '/*' || t === '~' || t.startsWith('~/')) return `不允许删除工作区以外的路径：${t}`
    const abs = resolveToolPath(cwd, t)
    if (!isInside(path.resolve(cwd), abs)) return `不允许删除工作区以外的路径：${t}`
    const rel = path.relative(path.resolve(cwd), abs).split(path.sep)
    if (rel.length === 1 && rel[0] === '') return '不允许删除整个工作区'
    if (PROTECTED_DIRS.includes(rel[0])) return `不允许删除 ${rel[0]}/`
    if (/[$`]/.test(t)) return `不允许删除由变量拼出的路径：${t}`
  }
  return null
}

function checkRedirects(cwd, words, opts) {
  for (let i = 0; i < words.length; i++) {
    const w = words[i]
    if (/^\d*>{1,2}\|?$|^&>{1,2}$/.test(w)) {
      const target = words[i + 1]
      if (!target || /^&\d$/.test(target) || target === '/dev/null' || target === '/dev/stdout' || target === '/dev/stderr') continue
      const r = checkWritePath(cwd, target, opts)
      if (r) return r
    }
  }
  return null
}

function dropRedirects(words) {
  const out = []
  for (let i = 0; i < words.length; i++) {
    if (/^(\d*|&)[<>]/.test(words[i])) { if (!/[<>]&\d*$/.test(words[i]) && !/^\d*>&\d+$/.test(words[i])) i++; continue }
    out.push(words[i])
  }
  return out
}

/** 检查一条 bash 命令，返回原因或 null。 */
export function checkBash(cwd, command, opts = {}) {
  if (typeof command !== 'string' || !command.trim()) return '命令为空'
  if (/\bsudo\b/.test(command)) return '不允许使用 sudo'
  const segments = splitCommands(command)
  for (let si = 0; si < segments.length; si++) {
    const seg = segments[si]
    if (seg.sep === '&') {
      return '不允许在后台运行命令（不要启动长期运行的服务）'
    }
    const r = checkRedirects(cwd, seg.words, opts)
    if (r) return r
    const words = unwrap(dropRedirects(seg.words))
    if (!words.length) continue
    const name = path.basename(words[0])
    const args = words.slice(1)
    const sub = args.find((a) => !a.startsWith('-'))
    if (PRIV_CMDS.has(name)) return `不允许执行 ${name}`
    if (NET_CMDS.has(name)) return `不允许联网：${name}（沙箱内没有外网，也不需要下载任何东西）`
    if (PKG_MANAGERS.has(name)) return `不允许安装依赖或使用 ${name}：请只使用已安装的依赖`
    if (SERVER_CMDS.has(name)) return `不允许启动长期运行的服务：${name}`
    if (name === 'git') {
      const gsub = args.filter((a) => !a.startsWith('-'))[0]
      if (!gsub || !GIT_READONLY.has(gsub)) return `不允许执行 git ${gsub ?? ''}：版本由织页管理，只能用只读的 git 命令（status/diff/log/show）`.replace(/\s+：/, '：')
      if (gsub === 'branch' && args.some((a) => a !== 'branch' && !a.startsWith('--list') && a !== '-a' && a !== '-v' && a !== '--show-current')) return '不允许修改 git 分支'
    }
    if (name === 'npm') {
      if (!sub || !NPM_ALLOWED.has(sub)) return `不允许执行 npm ${sub ?? ''}：不要安装或修改依赖`.trim()
      if (sub === 'run' || sub === 'run-script') {
        const script = args.filter((a) => !a.startsWith('-'))[1]
        if (script && DEV_SCRIPTS.has(script)) return `不允许启动长期运行的服务：npm run ${script}`
      }
      if (sub === 'config' && args.includes('set')) return '不允许修改 npm 配置'
    }
    if (name === 'npx') {
      const bin = args.find((a) => !a.startsWith('-'))
      const npxFlags = bin ? args.slice(0, args.indexOf(bin)) : args
      if (npxFlags.some((a) => a === '-y' || a === '--yes' || a === '-p' || a.startsWith('--package') || a === '-c' || a === '--call')) return '不允许用 npx 下载软件包'
      if (!bin) return 'npx 缺少命令'
      if (!/^[A-Za-z0-9_.@/-]+$/.test(bin) || bin.includes('@') && !bin.startsWith('@')) return `不允许用 npx 下载软件包：${bin}`
      const local = path.join(cwd, 'node_modules', '.bin', path.basename(bin))
      if (!existsSync(local)) return `不允许用 npx 下载软件包：${bin} 没有安装在项目里`
      const rest = args.slice(args.indexOf(bin) + 1)
      const r2 = checkViteLike(path.basename(bin), rest)
      if (r2) return r2
    }
    const r3 = checkViteLike(name, args)
    if (r3) return r3
    if (name === 'node' && args.some((a) => /(^|\/)(vite|server|serve)(\.m?js)?$/.test(a)) && !args.includes('build')) return '不允许启动长期运行的服务'
    if ((name === 'python' || name === 'python3') && args.includes('http.server')) return '不允许启动长期运行的服务：http.server'
    if (/^python(3(\.\d+)?)?$/.test(name) && args[0] === '-m' && ['pip', 'venv', 'ensurepip', 'virtualenv'].includes(args[1])) return `不允许安装依赖：python -m ${args[1]}`
    if (name === 'rm' || name === 'rmdir' || name === 'unlink' || name === 'shred') {
      const rr = checkRm(cwd, args)
      if (rr) return rr
    }
    if (name === 'mv' || name === 'cp' || name === 'ln' || name === 'tee' || name === 'touch' || name === 'mkdir' || name === 'install' || name === 'chmod' || name === 'truncate') {
      const targets = args.filter((a) => !a.startsWith('-'))
      const writes = name === 'mv' || name === 'cp' || name === 'ln' || name === 'install' ? targets.slice(-1).concat(name === 'mv' ? targets.slice(0, -1) : []) : name === 'chmod' ? targets.slice(1) : targets
      for (const t of writes) {
        const r = checkWritePath(cwd, t, opts)
        if (r) return r
      }
      if (name === 'ln') {
        for (const t of targets.slice(0, -1)) if (!isInside(path.resolve(cwd), resolveToolPath(cwd, t))) return `不允许创建指向工作区以外的链接：${t}`
      }
    }
    if (name === 'sed' && args.some((a) => /^-[a-zA-Z]*i/.test(a) || a === '--in-place')) {
      const hasScriptOpt = args.some((a) => a === '-e' || a === '-f' || a.startsWith('--expression') || a.startsWith('--file'))
      const nonOpt = args.filter((a, i) => a !== '' && !a.startsWith('-') && !['-e', '-f'].includes(args[i - 1]))
      const files = hasScriptOpt ? nonOpt : nonOpt.slice(1)
      for (const t of files) { const r = checkWritePath(cwd, t, opts); if (r) return r }
    }
    if (name === 'cd') {
      const t = args[0]
      if (t && t !== '.' && !isInside(path.resolve(cwd), resolveToolPath(cwd, t))) return `不允许进入工作区以外的目录：${t}`
      if (!t) return '不允许进入工作区以外的目录：~'
    }
  }
  return null
}

function checkViteLike(name, args) {
  if (name === 'vite') {
    const sub = args.find((a) => !a.startsWith('-'))
    if (sub !== 'build' && sub !== 'optimize') return '不允许启动开发服务器（vite / vite preview）；织页会自动构建和预览'
    if (args.includes('--watch') || args.includes('-w')) return '不允许以监听模式运行构建'
  }
  if (name === 'tsc' && (args.includes('--watch') || args.includes('-w'))) return '不允许以监听模式运行 tsc'
  return null
}

/** Pi tool_call 事件的统一检查。返回原因或 null。 */
export function checkToolCall(toolName, input, { cwd, projectKind = 'template', agentDir }) {
  const opts = { projectKind }
  input = input || {}
  switch (toolName) {
    case 'read':
      return checkReadPath(cwd, input.path, { agentDir })
    case 'write':
    case 'edit':
      return checkWritePath(cwd, input.path, opts)
    case 'bash':
      return checkBash(cwd, input.command, opts)
    case 'todo_write':
    case 'request_confirm':
    case 'screenshot':
      return null
    default:
      return `不允许使用工具 ${toolName}`
  }
}

/** Pi 扩展工厂：注册 tool_call 守卫，并把每次调用写入审计日志（stderr）。 */
export function createGuardExtension({ cwd, projectKind, agentDir, log = () => {} }) {
  return (pi) => {
    pi.on('tool_call', async (event) => {
      const reason = checkToolCall(event.toolName, event.input, { cwd, projectKind, agentDir })
      log(`[audit] tool=${event.toolName} ${reason ? 'BLOCK ' + reason : 'allow'} input=${JSON.stringify(event.input).slice(0, 300)}`)
      if (reason) return { block: true, reason: `安全守卫拦截：${reason}` }
      return undefined
    })
  }
}
