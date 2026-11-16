import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 织页 Vue 模板：第一版只支持对话修改（预览不注入可视化编辑桥）。导出源码后在本地运行不受影响。
export default defineConfig({
  base: './',
  plugins: [vue(), tailwindcss()],
  build: { chunkSizeWarningLimit: 2000 },
})
