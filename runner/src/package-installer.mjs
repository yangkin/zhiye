// 受控依赖安装工具（install_package）：
// 仅允许安装白名单内的成熟安全依赖，npm 强制 --ignore-scripts 杜绝供应链脚本执行，支持企业内网镜像源配置。
import { existsSync, lstatSync, readFileSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { Type } from 'typebox'
import { run, log } from './util.mjs'

export const DEFAULT_ALLOWED_NPM = new Set([
  'echarts', 'd3', 'chart.js', 'recharts', 'lucide-react', '@lucide/vue',
  'dayjs', 'date-fns', 'lodash-es', 'zod', 'clsx', 'tailwind-merge',
  '@tanstack/react-table', '@tanstack/react-query', 'zustand', 'immer',
  'framer-motion', 'mermaid', 'qrcode', 'qrcode.react', 'marked', 'markdown-it',
  'dompurify', 'jspdf', 'html2canvas', 'xlsx', 'papaparse', 'fuse.js',
  'express', 'cors', 'helmet', 'compression', 'multer', 'uuid', 'nanoid',
  'pg', 'mysql2', 'better-sqlite3', 'ioredis', 'ws', 'dotenv', 'pino', 'tsx',
])

export const DEFAULT_ALLOWED_PIP = new Set([
  'fastapi', 'uvicorn', 'pydantic', 'starlette', 'flask', 'gunicorn', 'requests', 'httpx',
  'sqlalchemy', 'psycopg2-binary', 'psycopg', 'pymysql', 'redis', 'pandas', 'numpy',
  'openpyxl', 'python-multipart', 'python-dotenv', 'jinja2', 'aiofiles', 'pyyaml',
])

const PKG_NAME_RE = /^(@[a-z0-9._-]+\/)?[a-z0-9._-]+$/i
const PKG_SPEC_RE = /^((?:@[a-z0-9._-]+\/)?[a-z0-9._-]+)(?:@([a-z0-9.^~><=_-]+))?$/i

export function allowedSet(ecosystem, env = process.env) {
  const base = ecosystem === 'pip' ? new Set(DEFAULT_ALLOWED_PIP) : new Set(DEFAULT_ALLOWED_NPM)
  const extra = String(env.ZHIYE_ALLOWED_PACKAGES || '').split(/[\s,;]+/).map((s) => s.trim().toLowerCase()).filter(Boolean)
  for (const x of extra) base.add(x)
  return base
}

export function validatePackageRequest(pkgSpec, ecosystem = 'npm', env = process.env) {
  const raw = String(pkgSpec || '').trim()
  if (!raw || raw.length > 100) return { ok: false, reason: '依赖名称为空或过长' }
  if (/[;&|`$<>\\]/.test(raw)) return { ok: false, reason: '依赖名称包含非法字符' }
  const m = PKG_SPEC_RE.exec(raw)
  if (!m) return { ok: false, reason: `依赖格式不合法：${raw}` }
  const name = m[1].toLowerCase()
  if (!PKG_NAME_RE.test(name)) return { ok: false, reason: `依赖包名不合法：${name}` }
  const allow = allowedSet(ecosystem, env)
  if (!allow.has(name)) {
    return {
      ok: false,
      reason: `包「${name}」不在平台安全白名单内。当前允许的常用包包括：${[...allow].slice(0, 20).join(', ')}…（管理员可通过 ZHIYE_ALLOWED_PACKAGES 扩展）`,
    }
  }
  return { ok: true, name, spec: m[2] ? `${name}@${m[2]}` : name }
}

export async function installPackage(cwd, { packageName, ecosystem = 'npm', dev = false } = {}, env = process.env) {
  const eco = ecosystem === 'pip' ? 'pip' : 'npm'
  const v = validatePackageRequest(packageName, eco, env)
  if (!v.ok) return { ok: false, text: `拒绝安装：${v.reason}` }

  if (eco === 'npm') {
    const pkgPath = path.join(cwd, 'package.json')
    if (!existsSync(pkgPath)) return { ok: false, text: '当前目录没有 package.json，无法安装 npm 依赖。' }
    const nmPath = path.join(cwd, 'node_modules')
    if (existsSync(path.join(nmPath, v.name, 'package.json'))) {
      return { ok: true, text: `✓ 依赖 ${v.name} 已预装在 node_modules 中，可直接导入使用（无需重复安装）。` }
    }
    let savedSymlinkTarget = null
    try {
      if (lstatSync(nmPath).isSymbolicLink()) savedSymlinkTarget = readlinkSync(nmPath)
    } catch {}
    const savedPkgJson = readFileSync(pkgPath, 'utf8')

    const args = ['install', '--no-audit', '--no-fund', '--ignore-scripts']
    if (dev) args.push('-D')
    if (env.ZHIYE_NPM_REGISTRY) args.push(`--registry=${env.ZHIYE_NPM_REGISTRY}`)
    args.push(v.spec)
    const r = await run('npm', args, { cwd, env, timeoutMs: 90_000 })
    log(`install_package npm ${v.spec} code=${r.code}`)
    if (r.code !== 0) {
      // 关键保护：离线沙箱下 npm install 失败时会误删共享 node_modules 符号链接，必须自动恢复避免整个工作区构建瘫痪
      if (savedSymlinkTarget) {
        try {
          if (existsSync(nmPath)) {
            const st = lstatSync(nmPath)
            if (!st.isSymbolicLink()) rmSync(nmPath, { recursive: true, force: true })
          }
          if (!existsSync(nmPath)) symlinkSync(savedSymlinkTarget, nmPath)
        } catch (e) {
          log(`restore node_modules symlink failed: ${e.message}`)
        }
      }
      try {
        writeFileSync(pkgPath, savedPkgJson, 'utf8')
      } catch {}
      return { ok: false, text: `安装 ${v.spec} 失败（退出码 ${r.code}；已自动恢复原有预装 node_modules，请直接复用已预装依赖）：\n${(r.out || '').slice(-1200)}` }
    }
    return { ok: true, text: `✓ 已安全安装 npm 依赖 ${v.spec}（已禁用 install scripts）` }
  }

  const args = ['-m', 'pip', 'install', '--no-cache-dir']
  if (env.ZHIYE_PIP_INDEX_URL) args.push('-i', env.ZHIYE_PIP_INDEX_URL)
  args.push(v.name)
  const r = await run('python3', args, { cwd, env, timeoutMs: 90_000 })
  log(`install_package pip ${v.name} code=${r.code}`)
  if (r.code !== 0) {
    return { ok: false, text: `安装 Python 依赖 ${v.name} 失败（退出码 ${r.code}）：\n${(r.out || '').slice(-1200)}` }
  }
  return { ok: true, text: `✓ 已安装 Python 依赖 ${v.name}` }
}

export function createPackageInstallExtension({ cwd, projectKind, env = process.env }) {
  return (pi) => {
    pi.registerTool({
      name: 'install_package',
      label: '安装白名单依赖',
      description: '从企业安全白名单按需安装成熟依赖包（如 echarts、zod、@tanstack/react-table、mermaid、qrcode、express、pg、fastapi 等）。npm 强制启用 --ignore-scripts 保障安全；未在白名单内的包会被拦截。',
      parameters: Type.Object({
        packageName: Type.String({ description: '包名或带版本规格，如 echarts、mermaid 或 zod@3' }),
        ecosystem: Type.Optional(Type.Union([Type.Literal('npm'), Type.Literal('pip')], { description: '包生态：npm（默认）或 pip' })),
        dev: Type.Optional(Type.Boolean({ description: '仅 npm 有效：是否安装到 devDependencies' })),
      }),
      async execute(_id, params) {
        if (projectKind === 'skill' || projectKind === 'static') {
          return { content: [{ type: 'text', text: `${projectKind} 项目不支持安装第三方依赖包。` }], details: { ok: false } }
        }
        const r = await installPackage(cwd, params || {}, env)
        return { content: [{ type: 'text', text: r.text }], details: { ok: r.ok } }
      },
    })
  }
}
