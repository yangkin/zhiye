import { writeFileSync } from 'node:fs'

const args = Object.fromEntries(process.argv.slice(2).map((x) => {
  const i = x.indexOf('=')
  return [i < 0 ? x.slice(2) : x.slice(2, i), i < 0 ? 'true' : x.slice(i + 1)]
}))
const base = (args.base || 'https://zhiye.csot.tcl.com').replace(/\/$/, '')
const publicUrl = args.public || ''
const levels = (args.levels || '5,20,50,100').split(',').map(Number)
const durationSec = Number(args.duration || 15)
const out = args.out || ''
const targets = [{ name: '首页 HTML', url: base + '/' }, { name: '元数据 API', url: base + '/api/meta' }]

const home = await fetch(base + '/').then((r) => r.text())
const script = home.match(/<script[^>]+src="([^"]+\.js)"/)?.[1]
if (script) targets.push({ name: '首页主 JS', url: new URL(script, base).href })
if (publicUrl) targets.push({ name: '公开应用 HTML', url: publicUrl })

function percentile(sorted, p) {
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1)] : 0
}

async function runStage(target, concurrency) {
  const started = Date.now()
  const deadline = started + durationSec * 1000
  const timings = []
  const status = {}
  let bytes = 0
  let failed = 0
  async function worker() {
    while (Date.now() < deadline) {
      const one = performance.now()
      const ctrl = AbortSignal.timeout(15000)
      try {
        const res = await fetch(target.url, { cache: 'no-store', signal: ctrl })
        const body = await res.arrayBuffer()
        const ms = performance.now() - one
        timings.push(ms)
        status[res.status] = (status[res.status] || 0) + 1
        bytes += body.byteLength
        if (!res.ok) failed++
      } catch (e) {
        timings.push(performance.now() - one)
        failed++
        const name = e?.name || 'Error'
        status[name] = (status[name] || 0) + 1
      }
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker))
  const elapsed = (Date.now() - started) / 1000
  timings.sort((a, b) => a - b)
  const count = timings.length
  return {
    target: target.name,
    url: target.url,
    concurrency,
    durationSec: Number(elapsed.toFixed(2)),
    requests: count,
    success: count - failed,
    failed,
    errorRate: count ? Number((failed * 100 / count).toFixed(3)) : 0,
    rps: Number((count / elapsed).toFixed(2)),
    latencyMs: {
      p50: Math.round(percentile(timings, 0.50)),
      p95: Math.round(percentile(timings, 0.95)),
      p99: Math.round(percentile(timings, 0.99)),
      max: Math.round(timings[timings.length - 1] || 0),
      avg: count ? Math.round(timings.reduce((a, b) => a + b, 0) / count) : 0,
    },
    megabytes: Number((bytes / 1024 / 1024).toFixed(2)),
    status,
  }
}

const startedAt = new Date().toISOString()
const results = []
for (const target of targets) {
  for (const concurrency of levels) {
    const result = await runStage(target, concurrency)
    results.push(result)
    console.log(JSON.stringify(result))
    await new Promise((r) => setTimeout(r, 2000))
  }
}
const report = { startedAt, generatedAt: new Date().toISOString(), base, publicUrl, durationSec, levels, results }
if (out) {
  writeFileSync(out, JSON.stringify(report, null, 2))
  console.log(`REPORT=${out}`)
}
if (results.some((r) => r.errorRate > 0)) process.exitCode = 2
