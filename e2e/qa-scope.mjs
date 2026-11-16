// 真实模型：超大需求在会话和计划阶段的处理
import { request } from '@playwright/test'
const BASE = 'http://127.0.0.1:8080'
const r = await request.newContext({ baseURL: BASE })
const H = () => ({ 'Idempotency-Key': crypto.randomUUID() })
const BIG = '帮我做一套覆盖全厂的管理系统：财务总账和应收应付、采购、仓库、生产排程、质量管理、设备维护、人事和薪资、OA 审批，要有手机 App 给车间用，审批结果要自动写回 SAP，还要每天晚上 12 点自动跑报表发邮件给领导。'
const conv = await (await r.post('/api/conversations', { data: {}, headers: H() })).json()
const res = await r.post(`/api/conversations/${conv.id}/ask`, { data: { content: BIG }, headers: H(), timeout: 300000 })
let text = ''
for (const block of (await res.text()).split('\n\n')) {
  const ev = block.match(/^event: (\w+)/m)?.[1]; const d = block.match(/^data: (.*)$/m)?.[1]
  if (ev === 'delta' && d) text += JSON.parse(d).text
}
console.log('=== 会话回复 ===\n' + text + '\n')
const app = await (await r.post('/api/apps', { data: { type: '日常开发', prompt: BIG, mode: '快速', model: 'auto', assetIds: [] }, headers: H() })).json()
for (;;) { const t = await (await r.get('/api/tasks/' + app.activeTask.id)).json(); if (['SUCCEEDED','FAILED','CANCELLED'].includes(t.status)) { console.log('plan task', t.status, JSON.stringify(t.result ?? t.error)); break } await new Promise(s => setTimeout(s, 1500)) }
const d = await (await r.get('/api/apps/' + app.id)).json()
console.log('=== 计划 ===\n' + d.planDoc)
console.log('\nAPP', app.id, 'CONV', conv.id)
