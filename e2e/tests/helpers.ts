import { expect, type APIRequestContext } from '@playwright/test'

export const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

export class Api {
  constructor(readonly req: APIRequestContext) {}

  async json<T = any>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
    const res = await this.req.fetch(path, {
      method,
      data: body === undefined ? undefined : body,
      headers: { 'Idempotency-Key': crypto.randomUUID(), ...headers },
    })
    const text = await res.text()
    if (!res.ok()) throw new ApiError(res.status(), text)
    return (text ? JSON.parse(text) : null) as T
  }
  get<T = any>(path: string, headers: Record<string, string> = {}) { return this.json<T>('GET', path, undefined, headers) }
  post<T = any>(path: string, body: unknown = {}, headers?: Record<string, string>) { return this.json<T>('POST', path, body, headers) }
  patch<T = any>(path: string, body: unknown, headers?: Record<string, string>) { return this.json<T>('PATCH', path, body, headers) }
  put<T = any>(path: string, body: unknown) { return this.json<T>('PUT', path, body) }

  async expectError(method: string, path: string, body: unknown, status: number, code?: string, headers: Record<string, string> = {}) {
    try {
      await this.json(method, path, body, headers)
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError)
      const err = e as ApiError
      expect(err.status, err.body).toBe(status)
      if (code) expect(err.parsed?.code, err.body).toBe(code)
      expect(typeof err.parsed?.message).toBe('string')
      return err
    }
    throw new Error(`期望 ${method} ${path} 返回 ${status}，但成功了`)
  }

  /** 轮询任务直到结束（真实模型，可能要几分钟） */
  async waitTask(id: string, { timeoutMs = 12 * 60_000, until }: { timeoutMs?: number; until?: (t: any) => boolean } = {}) {
    const start = Date.now()
    let last: any
    while (Date.now() - start < timeoutMs) {
      last = await this.get(`/api/tasks/${id}`)
      if (until ? until(last) : TERMINAL.has(last.status)) return last
      await new Promise((r) => setTimeout(r, 1500))
    }
    throw new Error(`任务 ${id} 超时，最后状态 ${JSON.stringify(last)}`)
  }

  /** 读取已结束任务的 SSE 事件 */
  async events(id: string, after = 0): Promise<any[]> {
    const res = await this.req.get(`/api/tasks/${id}/events?after=${after}`, { timeout: 60_000 })
    expect(res.ok()).toBeTruthy()
    expect(res.headers()['content-type']).toContain('text/event-stream')
    const out: any[] = []
    for (const block of (await res.text()).split(/\n\n/)) {
      const data = block.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n')
      if (data) out.push(JSON.parse(data))
    }
    return out
  }

  /** 创建应用并等待创造计划生成 */
  async createApp(prompt: string, type = '日常开发', mode = '快速') {
    const app = await this.post('/api/apps', { type, prompt, mode, model: 'auto', assetIds: [] })
    expect(app.phase).toBe('planning')
    expect(app.activeTask?.type).toBe('plan')
    const plan = await this.waitTask(app.activeTask.id, { timeoutMs: 4 * 60_000 })
    expect(plan.status, JSON.stringify(plan.error)).toBe('SUCCEEDED')
    const detail = await this.get(`/api/apps/${app.id}`)
    expect(detail.phase).toBe('plan')
    expect(detail.planDoc.length).toBeGreaterThan(20)
    return detail
  }

  async generate(appId: string) {
    const task = await this.post(`/api/apps/${appId}/generate`)
    const done = await this.waitTask(task.id)
    return done
  }

  async setFaults(f: { llm?: 'none' | 'error'; build?: 'none' | 'error' }) {
    return this.post('/api/dev/faults', { llm: 'none', build: 'none', ...f })
  }
}

export class ApiError extends Error {
  parsed: any
  constructor(readonly status: number, readonly body: string) {
    super(`HTTP ${status}: ${body.slice(0, 500)}`)
    try { this.parsed = JSON.parse(body) } catch { this.parsed = null }
  }
}

export const SITE_ORIGIN = process.env.ZHIYE_SITE_ORIGIN || 'http://127.0.0.1:8083'
/** 站点网关端口：跟 ZHIYE_SITE_ORIGIN 走，kind 里端口转发到别的端口时用例不用改 */
export const SITE_PORT = new URL(SITE_ORIGIN).port || (SITE_ORIGIN.startsWith('https:') ? '443' : '80')

/** Windows 的 Node 不一定解析 *.localhost；API 请求改连站点端口并保留原始 Host。 */
export function siteGet(req: APIRequestContext, rawUrl: string) {
  const target = new URL(rawUrl)
  return req.get(SITE_ORIGIN + target.pathname + target.search, { headers: { Host: target.host } })
}

/** 在页面里把任意 CSS 颜色（含 oklch）换算成 [r, g, b]，用于比较色相 */
export async function rgbOf(locator: import('@playwright/test').Locator): Promise<[number, number, number]> {
  return locator.evaluate((el) => {
    const c = document.createElement('canvas'); c.width = c.height = 1
    const ctx = c.getContext('2d')!
    ctx.fillStyle = getComputedStyle(el).color
    ctx.fillRect(0, 0, 1, 1)
    const d = ctx.getImageData(0, 0, 1, 1).data
    return [d[0], d[1], d[2]] as [number, number, number]
  })
}
