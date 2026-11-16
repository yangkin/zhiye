// 回归：反复加载同一个预览不能泄漏数据库连接。
// 背景：预览页注入云服务配置时如果用 JdbcClient.query(...).stream() 而不关闭，
// 每请求泄漏一个连接，20 次之后 site 进程连接池耗尽，预览全部 500（表现为「收起再展开就白屏」）。
// 这条用例只有「反复请求同一预览」才能抓到，单次 E2E 抓不到。
import { test, expect } from '@playwright/test'
import path from 'node:path'
import { readFileSync } from 'node:fs'
import { Api, SITE_ORIGIN } from './helpers'

const FIX = path.join(__dirname, '..', 'fixtures')
async function importHtml(request: import('@playwright/test').APIRequestContext, name: string) {
  const a = new Api(request)
  const up = await request.post('/api/imports/upload', { multipart: { file: { name, mimeType: 'text/html', buffer: readFileSync(path.join(FIX, 'garden.html')) } } })
  expect(up.status()).toBe(200)
  const manifest = await up.json()
  const created = await a.post('/api/imports', { uploadId: manifest.uploadId, name: name.replace(/\.html$/, '') })
  const done = await a.waitTask(created.task.id, { timeoutMs: 5 * 60_000 })
  expect(done.status, JSON.stringify(done.error)).toBe('SUCCEEDED')
  const detail = await a.get(`/api/apps/${created.app.id}`)
  return { api: a, detail }
}

test('同一预览连续加载 40 次：全部 200，且响应时间不会越拖越久（连接不泄漏）', async ({ request }) => {
  test.setTimeout(5 * 60_000)
  const { api, detail } = await importHtml(request, '预览压测.html')
  const previewUrl = new URL(detail.previewUrl).pathname // 后端给的是 /p/{buildId}/index.html
  expect(previewUrl).toMatch(/^\/p\//)

  const times: number[] = []
  for (let i = 0; i < 40; i++) {
    const t0 = Date.now()
    const res = await request.get(SITE_ORIGIN + previewUrl)
    times.push(Date.now() - t0)
    expect(res.status(), `第 ${i + 1} 次加载预览`).toBe(200)
    const body = await res.text()
    expect(body).toContain('<!doctype html')
  }
  // 前 5 次和后 5 次的中位数：泄漏时后面的请求会拖到 30 秒超时级别
  const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]
  const first = median(times.slice(0, 5))
  const last = median(times.slice(-5))
  expect(last, `后 5 次中位数 ${last}ms，前 5 次 ${first}ms`).toBeLessThan(Math.max(2000, first * 10))
  void api
})