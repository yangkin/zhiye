import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const PUBLIC_HOSTS = new Set(['registry.npmjs.org', 'registry.npmmirror.com'])

export function rewriteNpmTarballUrl(value, cdn) {
  try {
    const url = new URL(value)
    if (!PUBLIC_HOSTS.has(url.hostname.toLowerCase())) return value
    const parts = url.pathname.split('/')
    const dash = parts.lastIndexOf('-')
    if (dash <= 1 || dash !== parts.length - 2) return value
    const name = parts.slice(1, dash).join('/')
    const file = parts[dash + 1]
    const base = name.split('/').pop()
    const version = file.startsWith(base + '-') && file.endsWith('.tgz') ? file.slice(base.length + 1, -4) : ''
    if (!name || !version) return value
    return cdn.replace(/[/]+$/, '') + '/' + name + '/' + version + '/' + file
  } catch {
    return value
  }
}

export function rewriteNpmTarballs(cwd, cdn) {
  if (!cdn) return 0
  const file = path.join(cwd, 'package-lock.json')
  if (!existsSync(file)) return 0
  const lock = JSON.parse(readFileSync(file, 'utf8'))
  let changed = 0
  const walk = (value) => {
    if (!value || typeof value !== 'object') return
    if (typeof value.resolved === 'string') {
      const rewritten = rewriteNpmTarballUrl(value.resolved, cdn)
      if (rewritten !== value.resolved) {
        value.resolved = rewritten
        changed++
      }
    }
    Object.values(value).forEach(walk)
  }
  walk(lock)
  if (changed) writeFileSync(file, JSON.stringify(lock, null, 2) + '\n')
  return changed
}
