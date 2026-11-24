import { useEffect, useState } from 'react'

export default function App() {
  const [health, setHealth] = useState<{ ok: boolean; service: string } | null>(null)

  useEffect(() => {
    fetch('/api/health')
      .then((r) => r.json())
      .then(setHealth)
      .catch(() => setHealth(null))
  }, [])

  return (
    <main className="min-h-screen bg-slate-50 p-8 text-slate-900">
      <div className="mx-auto max-w-3xl rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">织页全栈应用</h1>
        <p className="mt-2 text-sm text-slate-600">
          前后端同域运行：{health?.ok ? `后端已就绪（${health.service}）` : '正在连接后端 API…'}
        </p>
      </div>
    </main>
  )
}
