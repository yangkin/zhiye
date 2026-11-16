// 云服务：启用、默认表 CRUD、文件存储、令牌失效、关闭后不可用
import { test, expect } from '@playwright/test'
import { Api } from './helpers'

test('云服务：启用 → 默认 notes 表 → CRUD → 存储上传下载 → 禁用后不可用', async ({ request }) => {
  const api = new Api(request)
  const app = await api.post('/api/apps', {
    type: '日常开发',
    prompt: '做一个内部公告记录的小工具',
    mode: '快速',
    model: 'auto',
    assetIds: [],
  })

  // 未启用时是 off
  const off = await api.get(`/api/apps/${app.id}/cloud`)
  expect(off.enabled).toBe(false)

  // 启用（没有版本时给一张默认的 notes 表）
  const on = await api.post(`/api/apps/${app.id}/cloud/enable`)
  expect(on.enabled).toBe(true)
  expect(on.tables.map((t: any) => t.name)).toContain('notes')
  expect(typeof on.token).toBe('string')

  const headers = { Authorization: `Bearer ${on.token}` }

  // 空表
  const empty = await api.get(`/cloud/${app.id}/db/notes?limit=10`, headers)
  expect(empty.total).toBe(0)

  // 插入
  const created = await api.post(`/cloud/${app.id}/db/notes`, { title: '第一条', content: '来自测试' }, headers)
  expect(created.id).toBeTruthy()
  expect(created.title).toBe('第一条')

  // 列表
  const list = await api.get(`/cloud/${app.id}/db/notes`, headers)
  expect(list.total).toBe(1)
  expect(list.rows[0].title).toBe('第一条')

  // 更新
  const updated = await api.patch(`/cloud/${app.id}/db/notes/${created.id}`, { content: '改过了' }, headers)
  expect(updated.content).toBe('改过了')

  // 不存在的字段被拒
  await api.expectError('POST', `/cloud/${app.id}/db/notes`, { nope: 'x' }, 400, undefined, headers)

  // 存储：上传 → 列表 → 下载
  const up = await api.json('POST', `/cloud/${app.id}/storage?path=docs/hello.txt`, 'hello cloud', headers)
  expect(up.size).toBeGreaterThan(0)
  const files = await api.get(`/cloud/${app.id}/storage`, headers)
  expect(files).toContain('docs/hello.txt')
  const dl = await api.req.get(`/cloud/${app.id}/storage?path=docs/hello.txt`, { headers })
  expect(dl.status()).toBe(200)
  expect(await dl.text()).toBe('hello cloud')

  // 无效令牌 / 错应用
  await api.expectError('GET', `/cloud/${app.id}/db/notes`, undefined, 401)
  await api.expectError('GET', `/cloud/${app.id}/db/notes`, undefined, 401, 'CLOUD_TOKEN_REQUIRED')

  // 禁用后不可用
  await api.post(`/api/apps/${app.id}/cloud/disable`)
  await api.expectError('GET', `/cloud/${app.id}/db/notes`, undefined, 403, 'CLOUD_DISABLED', headers)
})
