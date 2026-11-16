import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, cpSync, symlinkSync, existsSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { vueLint, verify } from '../src/verify.mjs'
import { checkWritePath } from '../src/guard.mjs'
import { systemPrompt } from '../src/prompts.mjs'

const ZHIYE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const CACHE = path.join(ZHIYE, 'runtime/vue-template-cache/node_modules')

test('Vue 检查：createWebHistory 和 React 依赖被拒绝，createWebHashHistory 通过', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'vue-lint-'))
  mkdirSync(path.join(ws, 'src/views'), { recursive: true })
  writeFileSync(path.join(ws, 'src/router.ts'), "import { createRouter, createWebHashHistory } from 'vue-router'\n")
  assert.deepEqual(vueLint(ws), [])
  writeFileSync(path.join(ws, 'src/views/Bad.vue'), "<script setup lang=\"ts\">\nimport { createWebHistory } from 'vue-router'\nimport { useState } from 'react'\n</script>\n")
  const p = vueLint(ws)
  assert.equal(p.length, 2)
  assert.match(p[0], /src\/views\/Bad\.vue.*createWebHashHistory/)
  assert.match(p[1], /不能引入 React/)
})

test('Vue 项目的写入范围与 React 模板一致', () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'vue-guard-'))
  const o = { projectKind: 'vue' }
  assert.equal(checkWritePath(ws, 'src/App.vue', o), null)
  assert.equal(checkWritePath(ws, 'src/views/Home.vue', o), null)
  assert.equal(checkWritePath(ws, 'cloud/schema.sql', o), null)
  assert.match(checkWritePath(ws, 'package.json', o), /package\.json/)
  assert.match(checkWritePath(ws, 'vite.config.ts', o), /只能修改/)
})

test('Vue 系统提示：Vue 写法、哈希路由、截图自查；脚本项目不带页面相关说明', () => {
  const p = systemPrompt('vue')
  assert.match(p, /script setup lang="ts"/)
  assert.match(p, /createWebHashHistory/)
  assert.match(p, /vue-tsc/)
  assert.match(p, /screenshot/)
  assert.doesNotMatch(p, /lucide-react|recharts/)
  const s = systemPrompt('script')
  assert.match(s, /命令行脚本项目/)
  assert.doesNotMatch(s, /src\/App\.tsx|screenshot|zhiye\.cloud/)
})

test('Vue 模板预装依赖：路由/图表/图标/日期/Excel/CSV 能通过 vue-tsc 和构建，冒烟测试看到内容', { skip: !existsSync(CACHE) && '没有 Vue 模板依赖缓存', timeout: 240_000 }, async () => {
  const ws = mkdtempSync(path.join(os.tmpdir(), 'vue-deps-'))
  cpSync(path.join(ZHIYE, 'packages/vue-template'), ws, { recursive: true })
  symlinkSync(CACHE, path.join(ws, 'node_modules'))
  mkdirSync(path.join(ws, 'src/views'), { recursive: true })
  writeFileSync(path.join(ws, 'src/router.ts'), `import { createRouter, createWebHashHistory } from 'vue-router'
import Home from './views/Home.vue'
export default createRouter({ history: createWebHashHistory(), routes: [{ path: '/', component: Home }, { path: '/about', component: () => import('./views/About.vue') }] })
`)
  writeFileSync(path.join(ws, 'src/views/About.vue'), '<template><p>关于</p></template>\n')
  writeFileSync(path.join(ws, 'src/views/Home.vue'), `<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { Download } from '@lucide/vue'
import dayjs from 'dayjs'
import Papa from 'papaparse'
import { readSheet } from 'read-excel-file/browser'
import writeXlsxFile from 'write-excel-file/browser'

const props = defineProps<{ title?: string }>()
const rows = ref<unknown[][]>([])
const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | undefined
onMounted(() => {
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value)
  chart.setOption({ xAxis: { type: 'category', data: ['A'] }, yAxis: { type: 'value' }, series: [{ type: 'bar', data: [3] }] })
})
onBeforeUnmount(() => chart?.dispose())
async function onFile(e: Event) { const f = (e.target as HTMLInputElement).files?.[0]; if (f) rows.value = await readSheet(f) }
const exportXlsx = () => writeXlsxFile([[{ value: '列' }]]).toFile('a.xlsx')
const csv = () => { rows.value = Papa.parse<string[]>('a,b\\n1,2').data }
</script>

<template>
  <main class="p-4">
    <h1>{{ props.title ?? dayjs('2026-01-02').format('YYYY年M月D日') }}</h1>
    <input type="file" aria-label="上传 Excel" @change="onFile" />
    <button @click="exportXlsx"><Download class="w-4 h-4" />导出</button>
    <button @click="csv">CSV</button>
    <p>共 {{ rows.length }} 行</p>
    <div ref="chartEl" style="height: 200px"></div>
    <RouterLink to="/about">关于</RouterLink>
  </main>
</template>
`)
  writeFileSync(path.join(ws, 'src/App.vue'), '<template><RouterView /></template>\n')
  writeFileSync(path.join(ws, 'src/main.ts'), `import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

createApp(App).use(router).mount('#app')
`)
  const r = await verify(ws, { projectKind: 'vue' })
  const byName = Object.fromEntries(r.checks.map((c) => [c.name, c]))
  assert.equal(byName['类型检查'].ok, true, byName['类型检查'].detail)
  assert.equal(byName['构建预览版'].ok, true, byName['构建预览版'].detail)
  assert.equal(byName['构建发布版'].ok, true, byName['构建发布版'].detail)
  assert.equal(existsSync(path.join(ws, '.zhiye/dist-publish/index.html')), true)
  assert.equal(r.editable, false)
  if (process.env.ZHIYE_CHROME_PATH) assert.equal(byName['冒烟测试'].ok, true, byName['冒烟测试'].detail)

  // 类型错误要被 vue-tsc 拦下
  writeFileSync(path.join(ws, 'src/views/About.vue'), '<script setup lang="ts">\nconst n: number = "x"\n</script>\n<template><p>{{ n }}</p></template>\n')
  const bad = await verify(ws, { projectKind: 'vue' })
  const tc = bad.checks.find((c) => c.name === '类型检查')
  assert.equal(tc.ok, false)
  assert.match(tc.detail, /About\.vue/)
})
