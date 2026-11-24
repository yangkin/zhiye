import express from 'express'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const DIST_DIR = path.join(ROOT, 'dist')
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, '.data')
const STORE_FILE = path.join(DATA_DIR, 'store.json')
const PORT = Number(process.env.PORT || 8080)

fs.mkdirSync(DATA_DIR, { recursive: true })

function loadStore() {
  if (!fs.existsSync(STORE_FILE)) {
    return { items: [] }
  }
  const raw = fs.readFileSync(STORE_FILE, 'utf8')
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.items)) {
      throw new Error('store.json 结构不合法（缺少 items 数组）')
    }
    return parsed
  } catch (e) {
    // 严格保护已有数据（FS-07）：文件已存在但 JSON 损坏时拒绝当作空列表覆写，避免永久丢失原数据
    console.error('[store] data file corrupted, refusing to overwrite:', e.message)
    const err = new Error('持久数据文件 store.json 已损坏，为避免覆盖原数据已拒绝读写，请检查或恢复备份文件。')
    err.status = 500
    throw err
  }
}

function saveStore(data) {
  fs.mkdirSync(DATA_DIR, { recursive: true })
  const tmp = STORE_FILE + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf8')
  fs.renameSync(tmp, STORE_FILE)
}

function currentUser(req) {
  const id = req.header('X-Zhiye-User-Id') || ''
  if (!id) return null
  return {
    id,
    loginName: req.header('X-Zhiye-User-Login') || '',
    name: req.header('X-Zhiye-User-Name') || id,
    admin: req.header('X-Zhiye-User-Admin') === 'true',
  }
}

const app = express()
app.use(express.json({ limit: '5mb' }))

app.get('/api/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'zhiye-fullstack-app',
    dataDir: DATA_DIR,
    hasDatabaseUrl: Boolean(process.env.DATABASE_URL),
    timestamp: Date.now(),
  })
})

app.get('/api/me', (req, res) => {
  res.json({ user: currentUser(req) })
})

app.get('/api/items', (_req, res, next) => {
  try {
    const store = loadStore()
    res.json({ items: store.items || [] })
  } catch (e) {
    next(e)
  }
})

app.post('/api/items', (req, res, next) => {
  try {
    const title = String(req.body?.title || '').trim()
    if (!title) return res.status(400).json({ message: 'title 不能为空' })
    const store = loadStore()
    const item = {
      id: 'item_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      title,
      createdAt: new Date().toISOString(),
    }
    store.items = [item, ...(store.items || [])]
    saveStore(store)
    res.status(201).json(item)
  } catch (e) {
    next(e)
  }
})

if (fs.existsSync(DIST_DIR)) {
  app.use(express.static(DIST_DIR, { fallthrough: true }))
  app.get('*', (req, res, next) => {
    // 缺失的 /api/*、/assets/* 或带静态文件后缀的请求一律返回 404，不回退 index.html（防止脚本请求返回 HTML）
    if (req.path.startsWith('/api/') || req.path.startsWith('/assets/') || /\.[a-z0-9]{1,8}$/i.test(req.path)) {
      return res.status(404).json({ message: 'Not Found', path: req.path })
    }
    res.sendFile(path.join(DIST_DIR, 'index.html'))
  })
}

app.use((err, _req, res, _next) => {
  const status = err?.status || 500
  res.status(status).json({ message: err?.message || 'Internal Server Error' })
})

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[fullstack] listening on http://0.0.0.0:${PORT} (DATA_DIR=${DATA_DIR})`)
})
