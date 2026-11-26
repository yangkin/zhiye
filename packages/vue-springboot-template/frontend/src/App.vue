<script setup lang="ts">
import { onMounted, ref } from 'vue'

interface HealthInfo {
  ok: boolean
  runtime: string
  framework: string
  time: string
}

const health = ref<HealthInfo | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const res = await fetch('/api/health')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    health.value = await res.json()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="min-h-screen w-full bg-slate-50 p-8">
    <div class="w-full rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <h1 class="text-xl font-semibold text-slate-900">Vue 3 + Spring Boot 全栈应用骨架</h1>
      <p class="mt-2 text-sm text-slate-600">
        前端运行在 Vue 3 + Vite + Tailwind CSS，后端运行在 Spring Boot 3.5（支持托管 PostgreSQL、华星 SSO 与平台托管企业连接器）。
      </p>
      <div class="mt-4 rounded-xl bg-slate-50 p-4 text-sm">
        <span v-if="loading" class="text-slate-500">正在探测 Spring Boot 后端健康状态…</span>
        <span v-else-if="error" class="text-red-600">后端暂未就绪：{{ error }}</span>
        <span v-else-if="health" class="text-emerald-700">
          ✓ 后端已连接 · {{ health.framework }}（{{ health.time }}）
        </span>
      </div>
    </div>
  </div>
</template>
