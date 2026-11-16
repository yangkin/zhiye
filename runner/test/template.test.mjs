import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, cpSync, symlinkSync, existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { templateLint, verify } from '../src/verify.mjs'

const ZHIYE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CACHE = path.join(ZHIYE, 'runtime/template-cache/node_modules')

test('模板检查：BrowserRouter 被拒绝，HashRouter 通过', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'tpl-lint-'))
  mkdirSync(path.join(ws, 'src/pages'), { recursive: true })
  writeFileSync(path.join(ws, 'src/App.tsx'), "import { HashRouter } from 'react-router'\n")
  assert.deepEqual(templateLint(ws), [])
  writeFileSync(path.join(ws, 'src/pages/Nav.tsx'), "import { BrowserRouter } from 'react-router'\n")
  const p = templateLint(ws)
  assert.equal(p.length, 1)
  assert.match(p[0], /src\/pages\/Nav\.tsx.*HashRouter/)
})

test('模板预装依赖：图表/图标/日期/路由/Excel/CSV 都能通过类型检查和构建', { skip: !existsSync(CACHE) && '没有模板依赖缓存' , timeout: 180_000 }, async () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'tpl-deps-'))
  cpSync(path.join(ZHIYE, 'packages/app-template'), ws, { recursive: true })
  symlinkSync(CACHE, path.join(ws, 'node_modules'))
  writeFileSync(path.join(ws, 'src/App.tsx'), `import { useState } from 'react'
import { HashRouter, Routes, Route, Link } from 'react-router'
import { BarChart, Bar, XAxis, YAxis, ResponsiveContainer } from 'recharts'
import { Download } from 'lucide-react'
import dayjs from 'dayjs'
import Papa from 'papaparse'
import { readSheet } from 'read-excel-file/browser'
import writeXlsxFile from 'write-excel-file/browser'

function Home() {
  const [rows, setRows] = useState<unknown[][]>([])
  return (
    <main>
      <h1>{dayjs('2026-01-02').format('YYYY年M月D日')}</h1>
      <input type="file" aria-label="上传 Excel" onChange={async (e) => { const f = e.target.files?.[0]; if (f) setRows(await readSheet(f)) }} />
      <button onClick={() => writeXlsxFile([[{ value: '列' }]]).toFile('a.xlsx')}><Download size={16} />导出</button>
      <button onClick={() => setRows(Papa.parse<string[]>('a,b\\n1,2').data)}>CSV</button>
      <p>共 {rows.length} 行</p>
      <div style={{ height: 200 }}><ResponsiveContainer><BarChart data={[{ n: 'A', v: 3 }]}><XAxis dataKey="n" /><YAxis /><Bar dataKey="v" /></BarChart></ResponsiveContainer></div>
      <Link to="/about">关于</Link>
    </main>
  )
}
export default function App() {
  return <HashRouter><Routes><Route path="/" element={<Home />} /><Route path="/about" element={<p>关于</p>} /></Routes></HashRouter>
}
`)
  const r = await verify(ws, { projectKind: 'template' })
  const byName = Object.fromEntries(r.checks.map((c) => [c.name, c]))
  assert.equal(byName['类型检查'].ok, true, byName['类型检查'].detail)
  assert.equal(byName['构建预览版'].ok, true, byName['构建预览版'].detail)
  assert.equal(byName['构建发布版'].ok, true, byName['构建发布版'].detail)
})
